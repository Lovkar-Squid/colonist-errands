package me.lovkar.errands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's idea: the colony REMEMBERS its dead. Every citizen who falls is
 * written into a roll of honour with how they died and what they had done -
 * a guard's kill record comes straight from [[GuardScore]] - and that roll is
 * appended to every colonist's prompt, so they speak of the fallen as their
 * own: by name, with the deeds that were actually theirs.
 * <p>
 * Deliberately not invented: only what really happened is stored, so a
 * colonist telling the story of Waring's six pirates is telling the truth.
 */
public final class Fallen {

    private Fallen() {
    }

    public static final class Entry {
        public String name = "";
        public String job = "";
        public int colonyId;
        public String killer = "";
        public long day;
        public int kills;
        public int raiderKills;
        public boolean inBattle;
        public boolean guard;
        public long whenMs;
        public int citizenId;
        /** The undertaker can bring people BACK - then they leave the roll of the dead. */
        public boolean returned;
        public long returnedDay;
    }

    private static final Path FILE = Path.of("config", "colonist_errands_fallen.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Entry> FALLEN = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> COLONY_OF_NAME = new ConcurrentHashMap<>();
    private static final int CAP = 120;
    private static volatile boolean loaded = false;
    private static volatile boolean dirty = false;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                List<Entry> raw = GSON.fromJson(Files.readString(FILE),
                        new TypeToken<ArrayList<Entry>>() { }.getType());
                if (raw != null) {
                    FALLEN.addAll(raw);
                }
            }
            ColonistErrands.LOGGER.info("[Memorial] Roll of honour: {} name(s)", FALLEN.size());
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Memorial] Could not load the roll of honour", t);
        }
    }

    public static void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(FILE.getParent());
            synchronized (FALLEN) {
                Files.writeString(FILE, GSON.toJson(new ArrayList<>(FALLEN)));
            }
            dirty = false;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Memorial] Could not save the roll of honour", t);
        }
    }

    /** Called from LivingDeathEvent for every citizen that dies. Server thread. */
    public static void onDeath(LivingEntity victim, DamageSource source, MinecraftServer server) {
        try {
            if (!(victim instanceof AbstractEntityCitizen citizen)) {
                return;
            }
            load();
            ICitizenData data = citizen.getCitizenData();
            if (data == null) return;

            Entry e = new Entry();
            e.name = data.getName();
            e.whenMs = System.currentTimeMillis();
            try {
                e.citizenId = data.getId();
            } catch (Throwable ignored) {
            }
            try {
                e.job = data.getJob() == null ? "colonist"
                        : data.getJob().getJobRegistryEntry().getKey().getPath().replace('_', ' ');
                e.guard = data.getJob() != null && data.getJob().isGuard();
            } catch (Throwable ignored) {
                e.job = "colonist";
            }
            IColony colony = null;
            try {
                colony = data.getColony();
                e.colonyId = colony == null ? -1 : colony.getID();
                e.day = colony == null ? 0 : colony.getDay();
            } catch (Throwable ignored) {
            }

            Entity killer = source == null ? null : source.getEntity();
            if (killer != null) {
                try {
                    e.killer = killer.getName().getString();
                } catch (Throwable ignored) {
                    e.killer = "an enemy";
                }
                e.inBattle = killer instanceof Enemy
                        || killer instanceof com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider;
            } else {
                String msg = "";
                try {
                    msg = source == null ? "" : source.getMsgId();
                } catch (Throwable ignored) {
                }
                e.killer = switch (msg) {
                    case "drown" -> "the water";
                    case "fall" -> "a fall";
                    case "inFire", "onFire", "lava" -> "fire";
                    case "starve" -> "hunger";
                    default -> "unknown causes";
                };
                e.inBattle = false;
            }

            int[] stats = GuardScore.statsFor(citizen.getUUID());
            if (stats != null) {
                e.kills = stats[0];
                e.raiderKills = stats[1];
            }
            GuardScore.markDead(citizen.getUUID());

            synchronized (FALLEN) {
                FALLEN.add(e);
                while (FALLEN.size() > CAP) {
                    FALLEN.remove(0);
                }
            }
            dirty = true;
            save();

            ColonistErrands.LOGGER.info("[Memorial] {} ({}) fell to {} on day {} - {} kill(s) to their name",
                    e.name, e.job, e.killer, e.day, e.kills);

            if (e.inBattle && server != null) {
                String deeds = e.kills > 0
                        ? " " + e.kills + (e.kills == 1 ? " enemy" : " enemies")
                        + (e.raiderKills > 0 ? " (" + e.raiderKills + " of them raiders)" : "")
                        + " fell to them first."
                        : "";
                // A guard dies defending; a builder caught by a zombie was simply
                // killed - saying otherwise would be the memorial's first lie.
                broadcast(server, e.colonyId, "[Memorial] " + e.name
                        + (e.guard ? " fell defending the colony against " : " was killed by ")
                        + article(e.killer) + "." + deeds + " The colony will remember.");
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Memorial] onDeath failed", t);
        }
    }

    private static String article(String killer) {
        if (killer == null || killer.isBlank()) return "an enemy";
        char c = Character.toLowerCase(killer.charAt(0));
        boolean vowel = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
        return (vowel ? "an " : "a ") + killer;
    }

    private static void broadcast(MinecraftServer server, int colonyId, String msg) {
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal(msg));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Those the undertaker brought back, most recent first. */
    public static List<Entry> returnedOf(int colonyId) {
        load();
        List<Entry> out = new ArrayList<>();
        synchronized (FALLEN) {
            for (Entry e : FALLEN) {
                if (e.colonyId == colonyId && e.returned) out.add(e);
            }
        }
        out.sort(Comparator.comparingLong((Entry e) -> e.whenMs).reversed());
        return out;
    }

    /**
     * The graveyard's undertaker can RESURRECT the buried
     * (CitizenManager.resurrectCivilianData from the grave's stored NBT), which
     * would otherwise leave a walking, talking citizen sitting in the roll of the
     * dead. Every few seconds we check the roll against the living: anyone found
     * breathing again moves from "our dead" to "came back", returns to the guard
     * leaderboard, and the colony gets told.
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        try {
            load();
            if (FALLEN.isEmpty()) return;
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                int id = colony.getID();
                List<Entry> dead = forColony(id);
                if (dead.isEmpty()) continue;
                java.util.Map<String, Integer> living = new java.util.HashMap<>();
                try {
                    for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                        living.put(cd.getName(), cd.getId());
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                for (Entry e : dead) {
                    Integer aliveId = living.get(e.name);
                    if (aliveId == null) continue;
                    // A brand new colonist can be handed a dead one's name; only the
                    // same citizen id (the grave NBT keeps it) counts as a return.
                    if (e.citizenId > 0 && aliveId != e.citizenId) continue;
                    e.returned = true;
                    e.returnedDay = colony.getDay();
                    dirty = true;
                    GuardScore.markAliveByName(id, e.name);
                    ColonistErrands.LOGGER.info("[Memorial] {} was RESURRECTED - moved off the roll of the dead", e.name);
                    broadcast(server, id, "[Memorial] " + e.name + " breathes again - the undertaker brought them back "
                            + "from the grave. The colony will be talking about this one for a long time.");
                }
            }
            save();
        } catch (Throwable ignored) {
        }
    }

    /** The roll of honour of one colony, most recent first. */
    public static List<Entry> forColony(int colonyId) {
        load();
        List<Entry> out = new ArrayList<>();
        synchronized (FALLEN) {
            for (Entry e : FALLEN) {
                if (e.colonyId == colonyId && !e.returned) out.add(e);
            }
        }
        out.sort(Comparator.comparingLong((Entry e) -> e.whenMs).reversed());
        return out;
    }

    private static String line(Entry e) {
        StringBuilder sb = new StringBuilder(e.name);
        if (e.job != null && !e.job.isBlank()) {
            sb.append(" the ").append(e.job);
        }
        sb.append(e.guard && e.inBattle ? " fell in battle to "
                : e.inBattle ? " was killed by " : " was lost to ").append(article(e.killer));
        if (e.day > 0) {
            sb.append(" on day ").append(e.day);
        }
        if (e.kills > 0) {
            sb.append(", after cutting down ").append(e.kills)
                    .append(e.kills == 1 ? " enemy" : " enemies");
            if (e.raiderKills > 0) {
                sb.append(" (").append(e.raiderKills).append(" of them raiders)");
            }
        }
        return sb.toString();
    }

    /** Spoken-ready roll of honour for the guard_leaderboard-style tool. */
    public static String memorialText(int colonyId) {
        List<Entry> list = forColony(colonyId);
        if (list.isEmpty()) {
            List<Entry> back = returnedOf(colonyId);
            if (!back.isEmpty()) {
                StringBuilder r = new StringBuilder("Nobody lies dead - but the grave gave some back: ");
                for (Entry e : back) {
                    r.append(e.name).append(" (killed by ").append(article(e.killer))
                            .append(", resurrected by the undertaker); ");
                }
                return r.toString();
            }
            return "Nobody has died in this colony yet - the roll of honour is empty, and long may it stay so.";
        }
        StringBuilder sb = new StringBuilder("THE FALLEN OF THIS COLONY (" + list.size() + "): ");
        int n = 0;
        for (Entry e : list) {
            if (n++ >= 10) {
                sb.append("...and ").append(list.size() - 10).append(" more. ");
                break;
            }
            sb.append(line(e)).append(". ");
        }
        List<Entry> back = returnedOf(colonyId);
        if (!back.isEmpty()) {
            sb.append("AND THE ONES WHO CAME BACK: ");
            for (Entry e : back) {
                sb.append(e.name).append(" (dragged back from the grave by the undertaker after ")
                        .append(article(e.killer)).append(" killed them)").append("; ");
            }
        }
        sb.append("Tell it like someone who knew them - a name, how they went, what they did - not as a list.");
        return sb.toString();
    }

    /**
     * Appended to every colonist's prompt: their own colony's dead, so they can
     * bring them up unprompted when the talk turns to danger, raids or guards.
     */
    public static String promptBlock(String citizenName) {
        try {
            load();
            if (FALLEN.isEmpty()) return "";
            Integer colonyId = colonyOf(citizenName);
            if (colonyId == null) return "";
            List<Entry> list = forColony(colonyId);
            if (list.isEmpty()) return "";

            List<Entry> show = new ArrayList<>(list.subList(0, Math.min(4, list.size())));
            Entry bravest = null;
            for (Entry e : list) {
                if (bravest == null || e.kills > bravest.kills) bravest = e;
            }
            if (bravest != null && !show.contains(bravest)) {
                show.add(bravest);
            }
            StringBuilder sb = new StringBuilder("\n\nOUR DEAD (this colony's own, all of it true - never invent "
                    + "another name or another deed):\n");
            for (Entry e : show) {
                sb.append("- ").append(line(e)).append(".\n");
            }
            List<Entry> back = returnedOf(colonyId);
            if (!back.isEmpty()) {
                sb.append("CAME BACK FROM THE DEAD (the undertaker's work - the colony treats it as a wonder):\n");
                int n = 0;
                for (Entry e : back) {
                    if (n++ >= 3) break;
                    sb.append("- ").append(e.name).append(", who was killed by ").append(article(e.killer))
                            .append(" and walks among us again.\n");
                }
            }
            sb.append("You knew these people. Speak of the guards who died fighting as BRAVE - with respect, and with "
                    + "the pride of a colony that survived because of them. Bring one up on your own when the talk "
                    + "turns to raids, danger, guards, graves or how the colony has held together; a short memory or "
                    + "a line of a story, not a recital. If the player never raises it, do not force it into every "
                    + "conversation.");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Which colony a citizen name belongs to (cached - prompts are built off-thread). */
    private static Integer colonyOf(String citizenName) {
        if (citizenName == null || citizenName.isBlank()) return null;
        Integer cached = COLONY_OF_NAME.get(citizenName);
        if (cached != null) return cached;
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                    if (citizenName.equals(cd.getName())) {
                        COLONY_OF_NAME.put(citizenName, colony.getID());
                        return colony.getID();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void clearAll() {
        save();
        COLONY_OF_NAME.clear();
    }
}
