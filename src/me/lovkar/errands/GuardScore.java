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
        public int kills;
        public int raiderKills;
        public double damageTaken;
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

    public static int score(Entry e) {
        // Lovkar: never below zero - the damage malus only eats into EARNED
        // points, a freshly bruised guard with no kills sits at 0, not -6.
        return Math.max(0, e.raiderKills * 15 + Math.max(0, e.kills - e.raiderKills) * 10
                - (int) Math.round(e.damageTaken / 2.0));
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

    /** Called from LivingDeathEvent: credit the kill if a guard did it. */
    public static void onKill(LivingEntity victim, net.minecraft.world.entity.Entity killer) {
        try {
            load();
            if (!(killer instanceof AbstractEntityCitizen guard) || !isGuard(guard)) {
                return;
            }
            boolean raider = victim instanceof AbstractEntityMinecoloniesRaider;
            if (!raider && !(victim instanceof Enemy)) {
                return; // only monsters count - no cows on the leaderboard
            }
            ICitizenData data = guard.getCitizenData();
            Entry e = entryFor(guard, data);
            e.kills++;
            if (raider) e.raiderKills++;
            dirty = true;
            sidebarDirty = true;
            checkLeadChange(guard, data, e);
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
            if (e.colonyId == colonyId && (e.kills > 0 || e.damageTaken > 0)) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparingInt(GuardScore::score).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /** Leaderboard text for the voice tool. */
    public static String leaderboardText(int colonyId) {
        List<Entry> top = top(colonyId, 5);
        if (top.isEmpty()) {
            return "No guard has any score yet - the board fills as guards kill monsters and raiders.";
        }
        StringBuilder sb = new StringBuilder("GUARD LEADERBOARD (raider kill 15, monster kill 10, -1 per 2 damage taken): ");
        int rank = 1;
        for (Entry e : top) {
            sb.append(rank++).append(". ").append(e.name).append(" ").append(score(e))
                    .append(" pts (").append(e.kills).append(" kills, ").append(e.raiderKills)
                    .append(" raiders, ").append((int) e.damageTaken).append(" dmg taken); ");
        }
        return sb.toString();
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
            StringBuilder sb = new StringBuilder("\n\nGUARD LEADERBOARD: you have ")
                    .append(score(mine)).append(" points (").append(mine.kills).append(" kills, #")
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

    // ---- vanilla scoreboard sidebar ----

    public static void setSidebar(MinecraftServer server, int colonyId, boolean visible) {
        sidebarVisible = visible;
        sidebarColony = colonyId;
        sidebarDirty = true;
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
                    Component.literal("Guard Leaderboard"), ObjectiveCriteria.RenderType.INTEGER, true, null);
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
        sidebarVisible = false;
    }
}
