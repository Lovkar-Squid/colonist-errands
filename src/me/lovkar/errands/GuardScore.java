package me.lovkar.errands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's idea #33: GUARDS LEADERBOARD + rivalry. Every guard earns a combat
 * score - raider kills 15, other monster kills 10, minus 1 per two points of
 * damage TAKEN (many kills with little damage = the ideal guard). Kills are
 * credited through LivingDeathEvent (arrow kills too - the source entity is
 * the shooting guard), damage through LivingDamageEvent.Post.
 *
 * Display: the guard_leaderboard voice tool (a citizen reads the top), an
 * optional vanilla scoreboard SIDEBAR, and a post-raid MVP line. Rivalry is
 * roleplay: every guard's prompt carries their score, rank and nearest rival,
 * and taking the lead writes proud/stung memories. Combat AI stays untouched.
 */
public final class GuardScore {

    private GuardScore() {
    }

    public static final class Entry {
        public String name = "";
        public int colonyId;
        /** Career totals - never reset. The memorial and the roll of honour read these. */
        public int kills;
        public int raiderKills;
        public double damageTaken;
        /** This week's numbers - wiped every Minecraft week. The live board reads these. */
        public int weekKills;
        public int weekRaiderKills;
        public double weekDamageTaken;
        /** Helped bring something down without landing the last blow. */
        public int assists;
        public int raiderAssists;
        public int weekAssists;
        public int weekRaiderAssists;
        /** Weeks finished first, and weeks finished in the top three. */
        public int wins;
        public int podiums;
        /** Best weekly score they have ever put up. */
        public int bestWeek;
        /** Where they finished last week (1..3, or 0 for nowhere) and with what score. */
        public int lastWeekRank;
        public int lastWeekScore;
        /** Fallen guards leave the live board and move to the memorial ([[Fallen]]). */
        public boolean dead;
        transient int raidKillsSnapshot = -1;
    }

    private static final Path FILE = Path.of("config", "colonist_errands_guard_scores.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Entry> SCORES = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile boolean dirty = false;
    /** colonyId -> last leaderboard leader uuid (for lead-change memories). */
    private static final Map<Integer, UUID> LEADERS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LEAD_MEMO_COOLDOWN = new ConcurrentHashMap<>();
    private static volatile boolean sidebarVisible = false;
    private static volatile int sidebarColony = -1;
    private static volatile boolean sidebarDirty = false;
    private static volatile boolean sidebarInitDone = false;
    /** Remembers the on-screen board across restarts - the vanilla scoreboard
     *  objective itself is saved in the WORLD, so without this a leaderboard
     *  switched on in an older session hangs there frozen (Lovkar saw a board
     *  still showing pre-clamp negative scores). */
    private static final Path SIDEBAR_FILE = Path.of("config", "colonist_errands_guard_sidebar.txt");

    /** This week's score - what the leaderboard, the sidebar and the rivalry use. */
    public static int score(Entry e) {
        return scoreOf(e.weekRaiderKills, e.weekKills, e.weekRaiderAssists, e.weekAssists, e.weekDamageTaken);
    }

    /** Career score - never reset, used by the memorial. */
    public static int careerScore(Entry e) {
        return scoreOf(e.raiderKills, e.kills, e.raiderAssists, e.assists, e.damageTaken);
    }

    /** At this much damage per fight the penalty is at its maximum. */
    private static final double FULL_MALUS_AT = 30.0;
    /** ...and even then it only ever eats this share of what they earned. */
    private static final double MAX_MALUS_FRACTION = 0.35;

    /**
     * Week 11 closed with three guards tied on 10 points, and Benedict - who had
     * TWICE the kills of the other two - came third. The old malus subtracted
     * TOTAL damage taken, and total damage grows with every fight you turn up to.
     * So a knight in the front line was punished for the role, a guard who killed
     * one thing and stayed out of trouble kept a perfect score, and past a point
     * extra fighting stopped paying at all.
     * <p>
     * The malus is now a RATE, not a volume: damage taken per fight. Sloppiness
     * still costs up to a third of what you earned, but because the penalty is a
     * fraction of the work rather than a subtraction from it, one more kill always
     * raises your score. Efficiency is what separates the guards, not how many
     * fights their job put them in.
     */
    private static int scoreOf(int raiderKills, int kills, int raiderAssists, int assists, double damageTaken) {
        int earned = raiderKills * 15 + Math.max(0, kills - raiderKills) * 10
                + raiderAssists * 6 + Math.max(0, assists - raiderAssists) * 4;
        int engagements = kills + assists;
        if (earned <= 0 || engagements <= 0 || damageTaken <= 0) {
            return Math.max(0, earned);
        }
        double perFight = damageTaken / engagements;
        double fraction = Math.min(MAX_MALUS_FRACTION, perFight / FULL_MALUS_AT);
        return Math.max(0, earned - (int) Math.round(earned * fraction));
    }

    /** Combat record of one citizen, for the memorial: {kills, raiderKills, score}. Null when they never fought. */
    public static int[] statsFor(java.util.UUID id) {
        try {
            load();
            Entry e = SCORES.get(id);
            if (e == null) return null;
            return new int[]{e.kills, e.raiderKills, careerScore(e)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** The undertaker brought them back - put them back on the live board. */
    public static void markAliveByName(int colonyId, String name) {
        try {
            load();
            for (Entry e : SCORES.values()) {
                if (e.colonyId == colonyId && e.dead && e.name.equals(name)) {
                    e.dead = false;
                    dirty = true;
                    sidebarDirty = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Takes a fallen guard off the live leaderboard (their deeds live on in the memorial). */
    public static void markDead(java.util.UUID id) {
        try {
            load();
            Entry e = SCORES.get(id);
            if (e != null && !e.dead) {
                e.dead = true;
                dirty = true;
                sidebarDirty = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                Map<String, Entry> raw = GSON.fromJson(Files.readString(FILE),
                        new TypeToken<LinkedHashMap<String, Entry>>() { }.getType());
                if (raw != null) {
                    raw.forEach((k, v) -> {
                        try {
                            SCORES.put(UUID.fromString(k), v);
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }
            ColonistErrands.LOGGER.info("[Guards] Loaded {} guard score(s)", SCORES.size());
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Guards] Could not load guard scores", t);
        }
    }

    private static void save() {
        try {
            Map<String, Entry> raw = new LinkedHashMap<>();
            SCORES.forEach((k, v) -> raw.put(k.toString(), v));
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(raw));
            dirty = false;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Guards] Could not save guard scores", t);
        }
    }

    private static Entry entryFor(AbstractEntityCitizen guard, ICitizenData data) {
        Entry e = SCORES.computeIfAbsent(guard.getUUID(), k -> new Entry());
        e.name = data.getName();
        e.colonyId = data.getColony() == null ? e.colonyId : data.getColony().getID();
        return e;
    }

    private static boolean isGuard(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            return data != null && data.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- assists: who wore the thing down, not just who finished it ----

    /** Damage every guard has dealt to one hostile, until it dies or is forgotten. */
    private static final class Wounds {
        final Map<UUID, Float> byGuard = new ConcurrentHashMap<>();
        float maxHealth = 20f;
        long lastMs = System.currentTimeMillis();
    }

    /** victim entity uuid -> who has been hitting it. */
    private static final Map<UUID, Wounds> WOUNDS = new ConcurrentHashMap<>();
    /** An assist needs real work: this share of the victim's health, or 2 hearts. */
    private static final double ASSIST_MIN_SHARE = 0.15;
    private static final double ASSIST_MIN_FLAT = 4.0;
    private static final long WOUNDS_TTL_MS = 90_000L;

    /** Called from LivingDamageEvent.Post: a guard hit a hostile - remember it. */
    public static void onDamageDealt(LivingEntity victim, net.minecraft.world.entity.Entity source, float amount) {
        try {
            if (amount <= 0 || victim == null) {
                return;
            }
            if (!(victim instanceof AbstractEntityMinecoloniesRaider) && !(victim instanceof Enemy)) {
                return;
            }
            if (!(source instanceof AbstractEntityCitizen guard) || !isGuard(guard)) {
                return;
            }
            Wounds w = WOUNDS.computeIfAbsent(victim.getUUID(), k -> new Wounds());
            w.lastMs = System.currentTimeMillis();
            try {
                w.maxHealth = Math.max(1f, victim.getMaxHealth());
            } catch (Throwable ignored) {
            }
            w.byGuard.merge(guard.getUUID(), amount, Float::sum);
        } catch (Throwable ignored) {
        }
    }

    /** Called from LivingDeathEvent: credit the kill, and the helpers. */
    public static void onKill(LivingEntity victim, net.minecraft.world.entity.Entity killer) {
        try {
            load();
            boolean raider = victim instanceof AbstractEntityMinecoloniesRaider;
            if (!raider && !(victim instanceof Enemy)) {
                WOUNDS.remove(victim.getUUID());
                return; // only monsters count - no cows on the leaderboard
            }
            UUID killerId = null;
            if (killer instanceof AbstractEntityCitizen guard && isGuard(guard)) {
                killerId = guard.getUUID();
                ICitizenData data = guard.getCitizenData();
                Entry e = entryFor(guard, data);
                e.kills++;
                e.weekKills++;
                if (raider) {
                    e.raiderKills++;
                    e.weekRaiderKills++;
                }
                dirty = true;
                sidebarDirty = true;
                checkLeadChange(guard, data, e);
            }
            creditAssists(victim, killerId, raider);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Everyone who did real damage to this thing, except whoever finished it,
     * gets an assist. The player landing the last blow does not rob the guards
     * who softened it up - killerId is simply null in that case.
     */
    private static void creditAssists(LivingEntity victim, UUID killerId, boolean raider) {
        try {
            Wounds w = WOUNDS.remove(victim.getUUID());
            if (w == null) {
                return;
            }
            double need = Math.min(ASSIST_MIN_FLAT, w.maxHealth * ASSIST_MIN_SHARE);
            for (Map.Entry<UUID, Float> hit : w.byGuard.entrySet()) {
                if (hit.getKey().equals(killerId) || hit.getValue() < need) {
                    continue;
                }
                Entry e = SCORES.get(hit.getKey());
                if (e == null) {
                    continue;
                }
                e.assists++;
                e.weekAssists++;
                if (raider) {
                    e.raiderAssists++;
                    e.weekRaiderAssists++;
                }
                dirty = true;
                sidebarDirty = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Forget wounds on things that wandered off or despawned. */
    private static void purgeWounds() {
        try {
            long now = System.currentTimeMillis();
            WOUNDS.entrySet().removeIf(en -> now - en.getValue().lastMs > WOUNDS_TTL_MS);
        } catch (Throwable ignored) {
        }
    }

    /** Called from LivingDamageEvent.Post on citizens: accumulate damage taken. */
    public static void onDamaged(LivingEntity entity, float amount) {
        try {
            load();
            if (amount <= 0 || !(entity instanceof AbstractEntityCitizen guard) || !isGuard(guard)) {
                return;
            }
            Entry e = entryFor(guard, guard.getCitizenData());
            e.damageTaken += amount;
            e.weekDamageTaken += amount;
            dirty = true;
            sidebarDirty = true;
        } catch (Throwable ignored) {
        }
    }

    private static void checkLeadChange(AbstractEntityCitizen guard, ICitizenData data, Entry e) {
        try {
            int colonyId = e.colonyId;
            UUID topId = null;
            int topScore = Integer.MIN_VALUE;
            for (Map.Entry<UUID, Entry> en : SCORES.entrySet()) {
                if (en.getValue().colonyId != colonyId) continue;
                int s = score(en.getValue());
                if (s > topScore) {
                    topScore = s;
                    topId = en.getKey();
                }
            }
            if (topId == null) return;
            UUID prev = LEADERS.put(colonyId, topId);
            if (prev == null || prev.equals(topId) || !topId.equals(guard.getUUID())) {
                return; // no change, or someone else holds it
            }
            long now = System.currentTimeMillis();
            Long cd = LEAD_MEMO_COOLDOWN.get(colonyId);
            if (cd != null && now - cd < 5 * 60_000L) return;
            LEAD_MEMO_COOLDOWN.put(colonyId, now);
            try {
                ((CitizenDataMemoryExtended) data).mc_talking$getOrInitializeMemory()
                        .addEvent("I just took the LEAD on the guard leaderboard (" + score(e)
                                + " points)! I am the best guard in the colony right now and PROUD of it.");
            } catch (Throwable ignored) {
            }
            Entry prevEntry = SCORES.get(prev);
            if (prevEntry != null) {
                ColonistErrands.LOGGER.info("[Guards] {} took the leaderboard lead from {} ({} pts)",
                        e.name, prevEntry.name, score(e));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Top guards of a colony, best first. */
    public static List<Entry> top(int colonyId, int limit) {
        load();
        List<Entry> list = new ArrayList<>();
        for (Entry e : SCORES.values()) {
            if (e.colonyId == colonyId && !e.dead && (e.weekKills > 0 || e.weekAssists > 0 || e.weekDamageTaken > 0)) {
                list.add(e);
            }
        }
        // Week 11 closed with three guards tied on 10 points and the crown went to
        // whichever the map happened to iterate first - and Benedict, who had TWICE
        // the kills, came third. Ties now break on real work: kills, then raider
        // kills, then who took less damage doing it, then the name so it is stable.
        list.sort(Comparator.comparingInt(GuardScore::score).reversed()
                .thenComparing(Comparator.comparingInt((Entry e) -> e.weekKills).reversed())
                .thenComparing(Comparator.comparingInt((Entry e) -> e.weekRaiderKills).reversed())
                .thenComparing(Comparator.comparingInt((Entry e) -> e.weekAssists).reversed())
                .thenComparingDouble(e -> e.weekDamageTaken)
                .thenComparing(e -> e.name));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /** Leaderboard text for the voice tool. */
    public static String leaderboardText(int colonyId) {
        List<Entry> top = top(colonyId, 5);
        String season = "This is a WEEKLY board - it wipes every Minecraft week and the top three are rewarded. "
                + daysLeft + (daysLeft == 1 ? " day" : " days") + " left in week "
                + (currentWeek < 0 ? "one" : String.valueOf(currentWeek)) + ". ";
        if (top.isEmpty()) {
            return season + "Nobody has scored yet this week - the board fills again as guards kill monsters and "
                    + "raiders." + pastWinners(colonyId);
        }
        StringBuilder sb = new StringBuilder("GUARD LEADERBOARD THIS WEEK (raider kill 15, monster kill 10, "
                + "raider assist 6, monster assist 4 - an assist is for anyone who did real damage to something "
                + "someone else finished off - then up to a third off for taking a beating, measured PER FIGHT, "
                + "so turning up to more fights never costs you): ");
        int rank = 1;
        for (Entry e : top) {
            sb.append(rank++).append(". ").append(e.name).append(" ").append(score(e))
                    .append(" pts (").append(e.weekKills).append(" kills, ").append(e.weekRaiderKills)
                    .append(" raiders, ").append(e.weekAssists).append(" assists, ")
                    .append((int) e.weekDamageTaken).append(" dmg taken); ");
        }
        return season + sb + pastWinners(colonyId);
    }

    /** Who has already won a week here - the colony's own hall of fame. */
    private static String pastWinners(int colonyId) {
        try {
            List<Entry> winners = new ArrayList<>();
            for (Entry e : SCORES.values()) {
                if (e.colonyId == colonyId && e.wins > 0) {
                    winners.add(e);
                }
            }
            if (winners.isEmpty()) {
                return " No week has been won here yet.";
            }
            winners.sort(Comparator.comparingInt((Entry e) -> e.wins).reversed());
            StringBuilder sb = new StringBuilder(" Past champions: ");
            int n = 0;
            for (Entry e : winners) {
                if (n++ >= 3) break;
                sb.append(e.name).append(" (").append(e.wins)
                        .append(e.wins == 1 ? " win" : " wins").append(", best week ")
                        .append(e.bestWeek).append(" pts); ");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Prompt block for guards: own score, rank, nearest rival - fuels the rivalry. */
    public static String promptLine(String citizenName) {
        try {
            load();
            Entry mine = null;
            for (Entry e : SCORES.values()) {
                if (e.name.equals(citizenName)) {
                    mine = e;
                    break;
                }
            }
            if (mine == null) {
                return "";
            }
            List<Entry> all = top(mine.colonyId, Integer.MAX_VALUE);
            int rank = all.indexOf(mine) + 1;
            if (rank <= 0) return "";
            StringBuilder sb = new StringBuilder("\n\nGUARD LEADERBOARD: the board is a WEEKLY contest - it wipes "
                    + "every Minecraft week and the top three are rewarded with real training. ")
                    .append(daysLeft).append(daysLeft == 1 ? " day" : " days")
                    .append(" of this week left. You have ")
                    .append(score(mine)).append(" points this week (").append(mine.weekKills).append(" kills and ")
                    .append(mine.weekAssists).append(" assists, #")
                    .append(rank).append(" of ").append(all.size()).append(" guards). ");
            if (rank == 1) {
                if (all.size() > 1) {
                    Entry second = all.get(1);
                    sb.append(second.name).append(" is ").append(score(mine) - score(second))
                            .append(" points behind you - you are PROUD of your lead and intend to keep it.");
                } else {
                    sb.append("You lead the board - so far unchallenged.");
                }
            } else {
                Entry ahead = all.get(rank - 2);
                sb.append(ahead.name).append(" is ").append(score(ahead) - score(mine))
                        .append(" points AHEAD of you - you are competitive about it and intend to beat them.");
            }
            if (mine.lastWeekRank == 1) {
                sb.append(" You were LAST WEEK'S CHAMPION with ").append(mine.lastWeekScore)
                        .append(" points, and you wear that title with pride.");
            } else if (mine.lastWeekRank > 1) {
                sb.append(" Last week you finished number ").append(mine.lastWeekRank)
                        .append(" with ").append(mine.lastWeekScore).append(" points.");
            }
            if (mine.wins > 0) {
                sb.append(" You have won the week ").append(mine.wins)
                        .append(mine.wins == 1 ? " time" : " times").append(" in all");
                if (mine.bestWeek > 0) {
                    sb.append(", your best week was ").append(mine.bestWeek).append(" points");
                }
                sb.append(".");
            }
            sb.append(" Career: ").append(mine.kills).append(" kills and ").append(mine.assists)
                    .append(" assists in all, ").append(careerScore(mine))
                    .append(" lifetime points - that part never resets.");
            sb.append(" You brag or grumble about the leaderboard when it fits the conversation.");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---- raid MVP ----

    private static final Map<Integer, Map<UUID, Integer>> RAID_SNAPSHOT = new HashMap<>();

    public static void raidStart(int colonyId) {
        try {
            load();
            Map<UUID, Integer> snap = new HashMap<>();
            SCORES.forEach((id, e) -> {
                if (e.colonyId == colonyId) snap.put(id, e.kills);
            });
            RAID_SNAPSHOT.put(colonyId, snap);
        } catch (Throwable ignored) {
        }
    }

    /** MVP line for the ended raid, or null when nobody scored. */
    public static String raidMvp(int colonyId) {
        try {
            Map<UUID, Integer> snap = RAID_SNAPSHOT.remove(colonyId);
            Entry best = null;
            int bestDelta = 0;
            for (Map.Entry<UUID, Entry> en : SCORES.entrySet()) {
                Entry e = en.getValue();
                if (e.colonyId != colonyId) continue;
                int before = snap == null ? 0 : snap.getOrDefault(en.getKey(), 0);
                int delta = e.kills - before;
                if (delta > bestDelta) {
                    bestDelta = delta;
                    best = e;
                }
            }
            if (best == null) return null;
            return "[Guards] Raid MVP: " + best.name + " with " + bestDelta
                    + " kill(s) this raid - " + score(best) + " points total on the leaderboard.";
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- weekly season (Lovkar: "da niso cel cas isti na vrhu") ----

    /**
     * The board is a SEASON, not a career. Every Minecraft week (7 days) the live
     * scores are wiped, the top three are rewarded, and everybody starts level
     * again - so a guard who joined the colony late, or who had one bad week, can
     * still take the crown. Career totals are kept separately and never reset;
     * the memorial reads those.
     */
    private static final Path WEEK_FILE = Path.of("config", "colonist_errands_guard_week.txt");
    private static final long TICKS_PER_DAY = 24000L;
    private static final long DAYS_PER_WEEK = 7L;
    /** Skill XP for first, second and third place (secondary skill gets half). */
    private static final double[] PRIZE_XP = {120.0, 70.0, 40.0};
    /** The Minecraft week the live board belongs to; -1 until seeded. */
    private static volatile long currentWeek = -1;
    /** Whole Minecraft days left in this week, for the prompt. */
    private static volatile int daysLeft = 7;

    private static long weekOf(MinecraftServer server) {
        try {
            return server.overworld().getDayTime() / TICKS_PER_DAY / DAYS_PER_WEEK;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long readWeek() {
        try {
            if (Files.exists(WEEK_FILE)) {
                return Long.parseLong(Files.readString(WEEK_FILE).trim());
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void writeWeek() {
        try {
            Files.createDirectories(WEEK_FILE.getParent());
            Files.writeString(WEEK_FILE, Long.toString(currentWeek));
        } catch (Throwable ignored) {
        }
    }

    private static void checkWeek(MinecraftServer server) {
        try {
            long week = weekOf(server);
            if (week < 0) {
                return;
            }
            try {
                daysLeft = (int) (DAYS_PER_WEEK - (server.overworld().getDayTime() / TICKS_PER_DAY) % DAYS_PER_WEEK);
            } catch (Throwable ignored) {
            }
            if (currentWeek < 0) {
                currentWeek = readWeek();
                if (currentWeek < 0) {
                    // First run on this world: today's week starts now. Never award
                    // on the seeding pass - nobody has played a week yet.
                    currentWeek = week;
                    writeWeek();
                    ColonistErrands.LOGGER.info("[Guards] Weekly leaderboard season starts at Minecraft week {}", week);
                    return;
                }
            }
            if (week <= currentWeek) {
                return;
            }
            long ended = currentWeek;
            currentWeek = week;
            writeWeek();
            endWeek(server, ended);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Guards] Weekly rollover failed", t);
        }
    }

    /** Close the week: reward the podium, tell everyone, wipe the live board. */
    private static void endWeek(MinecraftServer server, long endedWeek) {
        java.util.Set<Integer> colonies = new java.util.LinkedHashSet<>();
        for (Entry e : SCORES.values()) {
            colonies.add(e.colonyId);
        }
        for (int colonyId : colonies) {
            try {
                List<Entry> podium = top(colonyId, 3);
                com.minecolonies.api.colony.IColony colony = colonyById(colonyId);
                if (!podium.isEmpty() && colony != null) {
                    StringBuilder chat = new StringBuilder("[Guards] Week " + endedWeek
                            + " is over. ");
                    for (int i = 0; i < podium.size(); i++) {
                        Entry e = podium.get(i);
                        int pts = score(e);
                        e.lastWeekRank = i + 1;
                        e.lastWeekScore = pts;
                        e.podiums++;
                        if (i == 0) {
                            e.wins++;
                        }
                        String prize = awardXp(colony, e.name, PRIZE_XP[Math.min(i, PRIZE_XP.length - 1)]);
                        chat.append(i == 0 ? "CHAMPION: " : (i + 1) + ". ")
                                .append(e.name).append(" ").append(pts).append(" pts (")
                                .append(e.weekKills).append(" kills, ").append(e.weekAssists).append(" assists)")
                                .append(prize == null ? "" : " - " + prize)
                                .append(". ");
                        memory(colony, e.name, i == 0
                                ? "I WON the guard leaderboard for week " + endedWeek + " with " + pts
                                + " points - champion of the colony, and the whole colony heard it. That is "
                                + (e.wins == 1 ? "my first win" : "win number " + e.wins) + ". The board has just "
                                + "been wiped for the new week and I mean to defend the title."
                                : "I finished number " + (i + 1) + " on the guard leaderboard for week " + endedWeek
                                + " with " + pts + " points - on the podium, but not the top. The board has just "
                                + "been wiped for the new week and this time I want the crown.");
                    }
                    chat.append("Everyone starts from zero for week ").append(endedWeek + 1).append(".");
                    broadcast(server, chat.toString());
                    ColonistErrands.LOGGER.info("[Guards] Week {} closed in colony {} - champion {} ({} pts)",
                            endedWeek, colonyId, podium.get(0).name, score(podium.get(0)));
                }
                // Everyone else who fought this week hears the reset too.
                for (Entry e : SCORES.values()) {
                    if (e.colonyId != colonyId) continue;
                    if (e.lastWeekRank == 0 && (e.weekKills > 0 || e.weekAssists > 0) && colony != null) {
                        memory(colony, e.name, "The guard leaderboard has just reset for the new week - "
                                + "everybody is back to zero, me included. I did not make the podium last week, "
                                + "so this is my chance to climb.");
                    }
                    e.bestWeek = Math.max(e.bestWeek, score(e));
                    if (e.lastWeekRank == 0) {
                        e.lastWeekScore = score(e);
                    }
                    e.weekKills = 0;
                    e.weekRaiderKills = 0;
                    e.weekAssists = 0;
                    e.weekRaiderAssists = 0;
                    e.weekDamageTaken = 0;
                }
                // Ranks are only meaningful for the week just closed; clear the rest.
                for (Entry e : SCORES.values()) {
                    if (e.colonyId == colonyId && e.lastWeekRank > 3) {
                        e.lastWeekRank = 0;
                    }
                }
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("[Guards] Could not close the week for colony {}", colonyId, t);
            }
        }
        LEADERS.clear();
        dirty = true;
        sidebarDirty = true;
        save();
    }

    /**
     * The prize: real MineColonies skill experience in the guard's own primary and
     * secondary skill. MineColonies caps skill level at (home level + 1) * 10, so
     * a champion living in a shack gains less - which is fair, and gives the player
     * a reason to house their best fighters properly.
     */
    private static String awardXp(com.minecolonies.api.colony.IColony colony, String name, double xp) {
        try {
            ICitizenData data = citizenByName(colony, name);
            if (data == null) {
                return null;
            }
            com.minecolonies.api.entity.citizen.Skill primary = null;
            com.minecolonies.api.entity.citizen.Skill secondary = null;
            com.minecolonies.api.colony.buildings.IBuilding hut = data.getWorkBuilding();
            if (hut != null) {
                for (com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule m
                        : hut.getModules(com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule.class)) {
                    if (primary == null || m.hasAssignedCitizen(data)) {
                        primary = m.getPrimarySkill();
                        secondary = m.getSecondarySkill();
                        if (m.hasAssignedCitizen(data)) {
                            break;
                        }
                    }
                }
            }
            if (primary == null) {
                primary = com.minecolonies.api.entity.citizen.Skill.Adaptability;
                secondary = com.minecolonies.api.entity.citizen.Skill.Strength;
            }
            data.getCitizenSkillHandler().addXpToSkill(primary, xp, data);
            String text = primary.name() + " +" + (int) xp;
            if (secondary != null && secondary != primary) {
                data.getCitizenSkillHandler().addXpToSkill(secondary, xp / 2.0, data);
                text = text + " and " + secondary.name() + " +" + (int) (xp / 2.0);
            }
            data.markDirty(10);
            return text;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Guards] Could not award the weekly prize to {}", name, t);
            return null;
        }
    }

    private static void memory(com.minecolonies.api.colony.IColony colony, String name, String text) {
        try {
            ICitizenData data = citizenByName(colony, name);
            if (data == null) return;
            ((CitizenDataMemoryExtended) data).mc_talking$getOrInitializeMemory().addEvent(text);
        } catch (Throwable ignored) {
        }
    }

    private static ICitizenData citizenByName(com.minecolonies.api.colony.IColony colony, String name) {
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (name.equals(cd.getName())) {
                    return cd;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static com.minecolonies.api.colony.IColony colonyById(int id) {
        try {
            for (com.minecolonies.api.colony.IColony c
                    : com.minecolonies.api.colony.IColonyManager.getInstance().getAllColonies()) {
                if (c.getID() == id) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void broadcast(MinecraftServer server, String msg) {
        try {
            for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal(msg));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---- vanilla scoreboard sidebar ----

    /**
     * Server start: a vanilla scoreboard objective lives in the world save, so one
     * we created in an earlier session is still on screen - frozen, with whatever
     * numbers were current back then. Either adopt it (the player left it on, so
     * redraw it with today's scores) or clear it away.
     */
    private static void restoreSidebar(MinecraftServer server) {
        try {
            if (Files.exists(SIDEBAR_FILE)) {
                sidebarColony = Integer.parseInt(Files.readString(SIDEBAR_FILE).trim());
                sidebarVisible = true;
                sidebarDirty = true;
                ColonistErrands.LOGGER.info("[Guards] On-screen leaderboard was left on - redrawing it for colony {}",
                        sidebarColony);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Scoreboard board = server.getScoreboard();
            Objective obj = board.getObjective("guard_score");
            if (obj != null) {
                board.removeObjective(obj);
                ColonistErrands.LOGGER.info("[Guards] Cleared a stale leaderboard the world was still displaying");
            }
        } catch (Throwable ignored) {
        }
    }

    public static void setSidebar(MinecraftServer server, int colonyId, boolean visible) {
        sidebarVisible = visible;
        sidebarColony = colonyId;
        sidebarDirty = true;
        sidebarInitDone = true;
        try {
            if (visible) {
                Files.createDirectories(SIDEBAR_FILE.getParent());
                Files.writeString(SIDEBAR_FILE, Integer.toString(colonyId));
            } else {
                Files.deleteIfExists(SIDEBAR_FILE);
            }
        } catch (Throwable ignored) {
        }
        if (!visible) {
            try {
                Scoreboard board = server.getScoreboard();
                Objective obj = board.getObjective("guard_score");
                if (obj != null) {
                    board.removeObjective(obj);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (!sidebarInitDone) {
            sidebarInitDone = true;
            load();
            restoreSidebar(server);
        }
        if (server.getTickCount() % 100 == 0) {
            checkWeek(server);
            purgeWounds();
        }
        if (server.getTickCount() % 600 == 0 && dirty) {
            save();
        }
        if (!sidebarVisible || !sidebarDirty || server.getTickCount() % 100 != 0) {
            return;
        }
        sidebarDirty = false;
        try {
            Scoreboard board = server.getScoreboard();
            Objective obj = board.getObjective("guard_score");
            if (obj != null) {
                board.removeObjective(obj);
            }
            obj = board.addObjective("guard_score", ObjectiveCriteria.DUMMY,
                    Component.literal(currentWeek < 0 ? "Guard Leaderboard" : "Guards - Week " + currentWeek), ObjectiveCriteria.RenderType.INTEGER, true, null);
            for (Entry e : top(sidebarColony, 10)) {
                board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(e.name), obj).set(score(e));
            }
            board.setDisplayObjective(DisplaySlot.SIDEBAR, obj);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Guards] Sidebar update failed", t);
        }
    }

    public static void saveNow() {
        if (loaded && dirty) {
            save();
        }
        LEADERS.clear();
        LEAD_MEMO_COOLDOWN.clear();
        RAID_SNAPSHOT.clear();
        WOUNDS.clear();
        sidebarVisible = false;
        sidebarInitDone = false;
        currentWeek = -1;
    }
}
