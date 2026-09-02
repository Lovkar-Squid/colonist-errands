package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.colonyEvents.EventStatus;
import com.minecolonies.api.colony.colonyEvents.IColonyEvent;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Watches every colony's raider manager. On raid start: figures out WHICH
 * DIRECTION the raid comes from (spawn points vs. colony center), alarms all
 * players in chat, and sends the nearest guard to warn the player in person
 * (with the alarm written into their mc_talking memory so they know what they
 * are shouting about). Also warns the evening before ("will raid tonight") and
 * announces the victory. The direction is exposed for defend_here 'raid' mode.
 */
public final class RaidWatcher {

    private static final Map<Integer, Boolean> RAIDED = new HashMap<>();
    private static final Map<Integer, Long> WARNED_DAY = new HashMap<>();   // colony -> MC day already warned
    private static final Map<Integer, double[]> RAID_VEC = new HashMap<>();
    private static final Map<Integer, String> RAID_DIR = new HashMap<>();
    /** colonyId -> where the raiders appear(ed); bounds the defensive line so it stands between them and the colony. */
    private static final Map<Integer, BlockPos> RAID_SPAWN = new HashMap<>();
    private static final Set<Long> HANDLED_RAID_EVENTS = new HashSet<>(); // colonyId<<32 | eventId
    private static final Map<Integer, Long> PINNED_EVENT = new HashMap<>(); // colony -> pinpointed event key
    private static final Set<Integer> AUTO_DEFENSE = new HashSet<>(); // colonies whose line WE formed
    /** Colonies whose raid state we have already sampled once this session. */
    private static final Set<Integer> PRIMED = new HashSet<>();
    /** colonyId -> consecutive 30 s checks with the raid flag up but NO raider alive. */
    private static final Map<Integer, Integer> GHOST_RAID = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int GHOST_CHECKS_BEFORE_CLOSING = 10; // 10 x 30 s = 5 minutes
    /** colonyId -> when our line went up, so a raid that never resolves cannot pin the guards forever. */
    private static final Map<Integer, Long> DEFENSE_SINCE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LINE_MAX_MS = 20 * 60_000L;
    private static int tickCounter = 0;

    private RaidWatcher() {
    }

    /** Colony display name for alarm messages - two colonies now, no guessing. */
    private static String colonyName(IColony colony) {
        try {
            String n = colony.getName();
            return n == null || n.isBlank() ? ("colony " + colony.getID()) : n;
        } catch (Throwable t) {
            return "the colony";
        }
    }

    public static void tick(MinecraftServer server) {
        if (++tickCounter % 100 != 0) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                int id = colony.getID();
                var rm = colony.getRaiderManager();
                boolean raided;
                try {
                    raided = rm.isRaided();
                } catch (Throwable t) {
                    continue;
                }
                // A raid that is still running when the world loads (pirates
                // stuck out at sea keep isRaided() true forever) must not be
                // announced as if it had just started - seed the state silently
                // on the first pass and only react to real changes after that.
                if (!PRIMED.contains(id)) {
                    PRIMED.add(id);
                    RAIDED.put(id, raided);
                    if (raided) {
                        ColonistErrands.LOGGER.info("[Alarm] Colony {} loaded with a raid already in progress - no new alarm", id);
                    }
                    continue;
                }
                boolean was = RAIDED.getOrDefault(id, false);
                if (raided && !was) {
                    onRaidStart(server, colony);
                } else if (!raided && was) {
                    onRaidEnd(server, colony);
                }
                RAIDED.put(id, raided);

                // Lovkar's pirate raid stayed "active" with every pirate dead and
                // /mc kill raider reporting 0 entities. A SHIP raid counts as
                // active while its spawners, raiders OR respawns are non-empty -
                // the ship parked offshore kept the event alive forever, and with
                // it the alarm, the defense line and the whole colony's nerves.
                // So: raid flag up, not a single raider alive anywhere for five
                // minutes -> close the event ourselves, exactly the way
                // MineColonies' own kill command closes it.
                try {
                    if (raided && tickCounter % 600 == 0) {
                        int alive = countRaiders(colony);
                        if (alive == 0) {
                            int n = GHOST_RAID.merge(id, 1, Integer::sum);
                            if (n >= GHOST_CHECKS_BEFORE_CLOSING) {
                                GHOST_RAID.remove(id);
                                closeStuckRaid(server, colony);
                            }
                        } else if (alive > 0) {
                            GHOST_RAID.remove(id);
                        }
                    } else if (!raided) {
                        GHOST_RAID.remove(id);
                    }
                } catch (Throwable ignored) {
                }

                // Lovkar's pirate raid never ended (raiders stuck out at sea), so
                // the line stayed up and the whole guard force was pinned to the
                // shore. A line is a temporary order, never a life sentence.
                try {
                    Long since = DEFENSE_SINCE.get(id);
                    if (since != null && ErrandManager.hasActiveDefense(id)
                            && System.currentTimeMillis() - since > LINE_MAX_MS) {
                        int n = ErrandManager.standDownDefense(id);
                        AUTO_DEFENSE.remove(id);
                        DEFENSE_SINCE.remove(id);
                        if (n > 0) {
                            broadcast(server, "[Alarm] The line at " + colonyName(colony) + " has held for twenty minutes - "
                                    + "the guards are going back to their normal patrols. If attackers are still out there "
                                    + "(pirates like to sit on their ship), the towers cover the colony better on patrol.");
                            ColonistErrands.LOGGER.info("[Defense] Line timed out after 20 min - {} tower(s) released", n);
                        }
                    } else if (since != null && !ErrandManager.hasActiveDefense(id)) {
                        DEFENSE_SINCE.remove(id);
                    }
                } catch (Throwable ignored) {
                }

                // Lovkar's fix: ordering the line only AFTER raiders spawn is too
                // late (marching takes minutes, combat AI overrides posts). The
                // scheduled raid event already knows its spawn point BEFORE the
                // raiders appear - pinpoint it and form the line EARLY, automatically.
                scanScheduledRaids(server, colony);
                checkPinnedEvent(server, colony, raided);
                if (tickCounter % 1200 == 0) {
                    repairTowerSettings(colony); // heal settings broken by pre-1.9.2 unsupported set() calls
                }

                // Lovkar's raid-night report: the old warning fired the moment
                // willRaidTonight turned true (often in the MORNING - felt "a
                // night early"), and the old retraction fired on the flag's
                // falling edge - which also happens when MineColonies turns the
                // flag into the actual raid event, so it said "scout was wrong"
                // seconds before the real raid. New rules: warn only around
                // DUSK of the raid night (once per day), and retract ONLY when
                // a pinpointed raid event is truly canceled (checkPinnedEvent).
                try {
                    if (!raided && rm.willRaidTonight()
                            && !PINNED_EVENT.containsKey(id) && !RAID_VEC.containsKey(id)) {
                        long dayTime = colony.getWorld().getDayTime();
                        long day = dayTime / 24000L;
                        long time = dayTime % 24000L;
                        if (time >= 11000 && time <= 21000 && WARNED_DAY.getOrDefault(id, -1L) != day) {
                            WARNED_DAY.put(id, day);
                            broadcast(server, "[Alarm] Scouts of " + colonyName(colony) + " report: a raid is expected TONIGHT - "
                                    + "the watch will call the direction as soon as the attackers are spotted.");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("RaidWatcher tick failed", t);
        }
    }

    /** Detects scheduled (pre-spawn) raid events, announces the pinpointed direction and forms the line. */
    private static void scanScheduledRaids(MinecraftServer server, IColony colony) {
        try {
            for (IColonyEvent ev : colony.getEventManager().getEvents().values()) {
                if (!(ev instanceof IColonyRaidEvent)) continue;
                EventStatus st = ev.getStatus();
                if (st == EventStatus.DONE || st == EventStatus.CANCELED) continue;
                long key = ((long) colony.getID() << 32) | (ev.getID() & 0xFFFFFFFFL);
                if (!HANDLED_RAID_EVENTS.add(key)) continue;

                BlockPos spawn = null;
                try {
                    Object pos = ev.getClass().getMethod("getSpawnPos").invoke(ev);
                    if (pos instanceof BlockPos bp) spawn = bp;
                } catch (Throwable ignored) {
                }
                BlockPos center = colony.getCenter();
                if (spawn == null || center == null) continue;
                double dx = spawn.getX() - center.getX();
                double dz = spawn.getZ() - center.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1) continue;
                double[] out = {dx / len, dz / len};
                String dirName = dirName8(dx, dz);
                RAID_VEC.put(colony.getID(), out);
                RAID_DIR.put(colony.getID(), dirName);
                RAID_SPAWN.put(colony.getID(), spawn);
                PINNED_EVENT.put(colony.getID(), key);
                ColonistErrands.LOGGER.info("[Alarm] Scheduled raid pinpointed: from the {} (spawn {})", dirName, spawn.toShortString());
                broadcast(server, "[Alarm] Scouts pinpointed the raid on " + colonyName(colony) + ": attackers approach "
                        + "from the " + dirName.toUpperCase() + " - they will be here within MINUTES!");
                if (!ErrandManager.hasActiveDefense(colony.getID())) {
                    int placed = formLineToward(colony, out, dirName, spawn);
                    if (placed > 0) {
                        AUTO_DEFENSE.add(colony.getID());
                        DEFENSE_SINCE.put(colony.getID(), System.currentTimeMillis());
                        broadcast(server, "[Alarm] " + placed + " guard tower(s) are forming a defensive line at "
                                + colonyName(colony) + "'s " + dirName + " border!");
                    }
                } else {
                    ColonistErrands.LOGGER.info("[Alarm] Defense already active - not auto-forming");
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("scheduled raid scan failed", t);
        }
    }

    /**
     * The HONEST retraction (replaces the old willRaidTonight falling-edge one,
     * which also fired when the flag was consumed into the real raid event):
     * only when a PINPOINTED raid event truly disappears or is canceled without
     * the raid ever starting do we stand down and correct the scouts.
     */
    private static void checkPinnedEvent(MinecraftServer server, IColony colony, boolean raided) {
        Long key = PINNED_EVENT.get(colony.getID());
        if (key == null) {
            return;
        }
        if (raided) {
            PINNED_EVENT.remove(colony.getID()); // it came true - onRaidEnd handles the rest
            return;
        }
        boolean stillComing = false;
        try {
            int eventId = (int) (key & 0xFFFFFFFFL);
            IColonyEvent ev = colony.getEventManager().getEvents().get(eventId);
            if (ev != null) {
                EventStatus st = ev.getStatus();
                stillComing = st != EventStatus.DONE && st != EventStatus.CANCELED;
            }
        } catch (Throwable ignored) {
        }
        if (stillComing) {
            return;
        }
        PINNED_EVENT.remove(colony.getID());
        RAID_VEC.remove(colony.getID());
        RAID_DIR.remove(colony.getID());
        RAID_SPAWN.remove(colony.getID());
        broadcast(server, "[Alarm] The raiders turned back - no attack on " + colonyName(colony) + " after all. Stand easy.");
        DEFENSE_SINCE.remove(colony.getID());
        if (AUTO_DEFENSE.remove(colony.getID())) {
            int n = ErrandManager.standDownDefense(colony.getID());
            if (n > 0) {
                broadcast(server, "[Alarm] " + n + " guard tower(s) of " + colonyName(colony)
                        + " stand down and return to their duties.");
            }
        }
        ColonistErrands.LOGGER.info("[Alarm] Pinned raid event vanished without a raid - stood down");
    }

    /** Once a minute: un-break any tower setting an older addon version damaged (index -1). */
    private static void repairTowerSettings(IColony colony) {
        try {
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (!(b instanceof AbstractBuildingGuards tower)) continue;
                try {
                    GuardTaskSetting s = tower.getSetting(AbstractBuildingGuards.GUARD_TASK);
                    if (s != null) GuardSettings.repair(s);
                    com.minecolonies.core.colony.buildings.modules.settings.GuardFollowModeSetting fm =
                            tower.getSetting(AbstractBuildingGuards.FOLLOW_MODE);
                    if (fm != null) GuardSettings.repair(fm);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Same line math as defend_here's border mode (see DefenseLine): the anchor sits on
     * the axis of the attack just past the outermost building, between the raiders and
     * the colony, pulled back toward the town hall when the ground there is water.
     */
    private static int formLineToward(IColony colony, double[] out, String dirName, BlockPos spawn) {
        int placed = 0;
        try {
            java.util.ArrayList<AbstractBuildingGuards> towers = new java.util.ArrayList<>();
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (!(b instanceof AbstractBuildingGuards g)) continue;
                // A tower with no guards can't defend anything (under
                // construction / unmanned) - its settings may not even be
                // initialized (StringSetting.getValue threw for 2 such towers
                // in Lovkar's raid log). Skip it and keep the count honest.
                try {
                    if (g.getAllAssignedCitizen() == null || g.getAllAssignedCitizen().isEmpty()) {
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                towers.add(g);
            }
            if (towers.isEmpty()) return 0;
            int[] anchor = DefenseLine.anchor(colony, out, spawn, towers.size());
            if (anchor == null) {
                ColonistErrands.LOGGER.info("[Defense] AUTO line toward the {} - no dry ground, towers stay on their normal tasks",
                        dirName);
                return 0;
            }
            int ax = anchor[0];
            int az = anchor[1];
            double px = -out[1];
            double pz = out[0];
            int spacing = DefenseLine.SPACING;
            for (AbstractBuildingGuards tower : towers) {
                try {
                    int k = (placed + 1) / 2 * ((placed % 2 == 0) ? 1 : -1);
                    int postX = ax + (int) Math.round(px * spacing * k);
                    int postZ = az + (int) Math.round(pz * spacing * k);
                    // Lovkar's pirate raid: posts computed from the colony bounds
                    // landed in the SEA and the guards drowned walking to them.
                    BlockPos post = ErrandManager.safePost(colony.getWorld(), postX, postZ);
                    if (post == null) {
                        ColonistErrands.LOGGER.info("[Defense] No dry ground near {},{} - leaving one tower on its normal task",
                                postX, postZ);
                        continue;
                    }
                    GuardTaskSetting s = tower.getSetting(AbstractBuildingGuards.GUARD_TASK);
                    if (s == null) continue;
                    String prev = GuardSettings.value(s, GuardTaskSetting.PATROL);
                    if (!GuardSettings.set(s, GuardTaskSetting.GUARD)) {
                        continue; // this tower doesn't offer GUARD - skip, never break its setting
                    }
                    tower.setGuardPos(post);
                    ErrandManager.rallyTo(tower, post, colony.getWorld());
                    try {
                        tower.markDirty();
                    } catch (Throwable ignored) {
                    }
                    ErrandManager.kickGuardAI(tower);
                    ErrandManager.registerDefense(tower, prev);
                    placed++;
                } catch (Throwable t) {
                    ColonistErrands.LOGGER.warn("auto defense failed for a tower", t);
                }
            }
            ColonistErrands.LOGGER.info("[Defense] AUTO line toward the {} - {} tower(s)", dirName, placed);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("formLineToward failed", t);
        }
        return placed;
    }

    /** How many MineColonies raiders are alive in this world; -1 when we cannot tell. */
    private static int countRaiders(IColony colony) {
        try {
            if (!(colony.getWorld() instanceof net.minecraft.server.level.ServerLevel level)) {
                return -1;
            }
            return level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(
                            com.minecolonies.api.entity.mobs.AbstractEntityMinecoloniesRaider.class),
                    e -> e.isAlive()).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Marks every still-"active" raid event DONE, which is what finally ends it. */
    private static void closeStuckRaid(MinecraftServer server, IColony colony) {
        int ended = 0;
        try {
            for (com.minecolonies.api.colony.colonyEvents.IColonyEvent ev
                    : colony.getEventManager().getEvents().values()) {
                if (ev instanceof com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent raid
                        && raid.isRaidActive()) {
                    ev.setStatus(com.minecolonies.api.colony.colonyEvents.EventStatus.DONE);
                    ended++;
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Alarm] Could not close a stuck raid", t);
            return;
        }
        if (ended > 0) {
            ColonistErrands.LOGGER.info("[Alarm] Closed {} stuck raid event(s) in colony {} - no raider alive for five minutes",
                    ended, colony.getID());
            broadcast(server, "[Alarm] Not an attacker left standing near " + colonyName(colony)
                    + " - the watch is calling the raid over. (Their ship may still be sitting offshore; "
                    + "it cannot hurt anyone now.)");
        }
    }

    private static void onRaidStart(MinecraftServer server, IColony colony) {
        String dirName = "unknown direction";
        try {
            List<BlockPos> spawns = colony.getRaiderManager().getLastSpawnPoints();
            BlockPos center = colony.getCenter();
            if (spawns != null && !spawns.isEmpty() && center != null) {
                double dx = 0, dz = 0;
                for (BlockPos p : spawns) {
                    dx += p.getX() - center.getX();
                    dz += p.getZ() - center.getZ();
                }
                dx /= spawns.size();
                dz /= spawns.size();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 1) {
                    RAID_VEC.put(colony.getID(), new double[]{dx / len, dz / len});
                    dirName = dirName8(dx, dz);
                    RAID_DIR.put(colony.getID(), dirName);
                    RAID_SPAWN.put(colony.getID(), new BlockPos(center.getX() + (int) Math.round(dx), center.getY(),
                            center.getZ() + (int) Math.round(dz)));
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Raid direction calc failed", t);
        }
        ColonistErrands.LOGGER.info("[Alarm] Raid on colony {} from the {}", colony.getID(), dirName);
        GuardScore.raidStart(colony.getID());
        broadcast(server, "[Alarm] RAID on " + colonyName(colony) + "! Attackers are coming from the "
                + dirName.toUpperCase() + "!");

        // If the pre-spawn scan didn't catch this raid, form the line now.
        double[] vec = RAID_VEC.get(colony.getID());
        if (!ErrandManager.hasActiveDefense(colony.getID()) && vec != null) {
            int placed = formLineToward(colony, vec, dirName, RAID_SPAWN.get(colony.getID()));
            if (placed > 0) {
                AUTO_DEFENSE.add(colony.getID());
                DEFENSE_SINCE.put(colony.getID(), System.currentTimeMillis()); // the 20-minute cap applies here too
                broadcast(server, "[Alarm] " + placed + " guard tower(s) rush to form a defensive line at "
                        + colonyName(colony) + "'s " + dirName + " border!");
            }
        }

        // The nearest guard runs to warn the player in person - in multiplayer
        // that's the player CLOSEST to the colony (the one actually there).
        try {
            ServerPlayer player = null;
            double bestP = Double.MAX_VALUE;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                double d;
                try {
                    d = sp.blockPosition().distSqr(colony.getCenter());
                } catch (Throwable t) {
                    d = Double.MAX_VALUE / 2;
                }
                if (d < bestP) {
                    bestP = d;
                    player = sp;
                }
            }
            if (player == null) return;
            AbstractEntityCitizen guard = null;
            double best = Double.MAX_VALUE;
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd == null || !(cd.getWorkBuilding() instanceof AbstractBuildingGuards)) continue;
                Optional<AbstractEntityCitizen> opt = cd.getEntity();
                if (opt == null || opt.isEmpty()) continue;
                AbstractEntityCitizen g = opt.get();
                if (!g.isAlive() || g.level() != player.level()) continue;
                double d = g.distanceToSqr(player);
                if (d < best) {
                    best = d;
                    guard = g;
                }
            }
            if (guard != null) {
                try {
                    ((CitizenDataMemoryExtended) guard.getCitizenData()).mc_talking$getOrInitializeMemory()
                            .addEvent("ALARM! A raid is attacking " + colonyName(colony) + " RIGHT NOW from the " + dirName
                                    + "! I am running to warn " + player.getGameProfile().getName()
                                    + " so they can organize the defense!");
                } catch (Throwable ignored) {
                }
                ErrandManager.enqueueContactPlayer(guard, player.getUUID());
                ColonistErrands.LOGGER.info("[Alarm] {} runs to warn the player", guard.getCitizenData().getName());
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Raid warner failed", t);
        }
    }

    private static void onRaidEnd(MinecraftServer server, IColony colony) {
        RAID_VEC.remove(colony.getID());
        RAID_DIR.remove(colony.getID());
        RAID_SPAWN.remove(colony.getID());
        PINNED_EVENT.remove(colony.getID());
        broadcast(server, "[Alarm] The raid on " + colonyName(colony) + " is over - the colony held!");
        try {
            String mvp = GuardScore.raidMvp(colony.getID());
            if (mvp != null) {
                broadcast(server, mvp);
            }
        } catch (Throwable ignored) {
        }
        DEFENSE_SINCE.remove(colony.getID());
        if (AUTO_DEFENSE.remove(colony.getID())) {
            int n = ErrandManager.standDownDefense(colony.getID());
            if (n > 0) {
                broadcast(server, "[Alarm] " + n + " guard tower(s) of " + colonyName(colony)
                        + " stand down and return to their duties.");
            }
        }
    }

    private static void broadcast(MinecraftServer server, String msg) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(msg));
        }
    }

    /** Direction of the ACTIVE raid as a unit vector {x,z}, or null. */
    public static double[] raidVector(IColony colony) {
        return RAID_VEC.get(colony.getID());
    }

    public static String raidDirName(IColony colony) {
        return RAID_DIR.getOrDefault(colony.getID(), "unknown direction");
    }

    /** Where the ACTIVE raid's attackers appear(ed), or null. */
    public static BlockPos raidSpawn(IColony colony) {
        return RAID_SPAWN.get(colony.getID());
    }

    public static String dirName8(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
        if (angle < 0) angle += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(angle / 45.0) % 8];
    }

    public static void clearAll() {
        RAIDED.clear();
        WARNED_DAY.clear();
        PINNED_EVENT.clear();
        RAID_VEC.clear();
        RAID_DIR.clear();
        RAID_SPAWN.clear();
        HANDLED_RAID_EVENTS.clear();
        AUTO_DEFENSE.clear();
        DEFENSE_SINCE.clear();
        PRIMED.clear();
        GHOST_RAID.clear();
    }
}
