package me.marko.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.AIOneTimeEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardFollowModeSetting;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import me.sshcrack.mc_talking.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side errand engine: walking, waiting/holding, following, messenger
 * chains, courier fetch chains, plus guard-follow and defense-formation
 * overrides with restore. Citizens on errands are marked busy so mc_talking's
 * mixins keep MineColonies' work AI from reclaiming them.
 */
public final class ErrandManager {

    public enum Kind { TO_BUILDING, TO_POS, WAIT, FOLLOW, MESSENGER, CONTACT_PLAYER, FETCH_PICKUP, FETCH_DELIVER,
        DELIVER_BUILDING, GUIDE }

    public static final String GROUP_GATHER = "gather";
    public static final String GROUP_EAT = "eat";

    public static final class Errand {
        final Kind kind;
        final AbstractEntityCitizen citizen;
        final BlockPos targetPos;      // walk target / WAIT anchor
        final String targetName;
        final UUID playerId;
        final IBuilding building;      // MESSENGER delivery building / FETCH warehouse
        final double arriveDistSq;
        final String group;            // e.g. GROUP_GATHER - dismissable together
        final boolean holdOnArrival;   // TO_POS -> convert to WAIT hold at the target
        final Item fetchItem;          // FETCH_*
        final int fetchCount;          // FETCH_PICKUP: requested; FETCH_DELIVER: collected
        int age = 0;
        int walkTicks = 0;
        final int timeout;
        IBuilding destBuilding;              // DELIVER chain: final destination building
        AbstractEntityCitizen targetCitizen; // GUIDE: the citizen we lead the player to
        int collectedSoFar = 0;              // FETCH chain: items already gathered at previous warehouses
        java.util.Set<BlockPos> visitedWarehouses; // FETCH chain: warehouses already emptied

        Errand(Kind kind, AbstractEntityCitizen citizen, BlockPos targetPos, String targetName,
               UUID playerId, IBuilding building, double arriveDistSq, int timeout,
               String group, boolean holdOnArrival, Item fetchItem, int fetchCount) {
            this.kind = kind;
            this.citizen = citizen;
            this.targetPos = targetPos;
            this.targetName = targetName;
            this.playerId = playerId;
            this.building = building;
            this.arriveDistSq = arriveDistSq;
            this.timeout = timeout;
            this.group = group;
            this.holdOnArrival = holdOnArrival;
            this.fetchItem = fetchItem;
            this.fetchCount = fetchCount;
        }
    }

    private record PendingStart(Kind kind, AbstractEntityCitizen citizen, BlockPos pos, String name,
                                UUID playerId, int timeout, double arriveDistSq,
                                String group, boolean holdOnArrival) {
    }

    public static final class GuardFollow {
        final AbstractBuildingGuards building;
        final String previousTask;
        final String previousFollowMode;
        final UUID playerId;
        int age = 0;

        GuardFollow(AbstractBuildingGuards building, String previousTask, String previousFollowMode, UUID playerId) {
            this.building = building;
            this.previousTask = previousTask;
            this.previousFollowMode = previousFollowMode;
            this.playerId = playerId;
        }
    }

    private static final class DefenseEntry {
        final AbstractBuildingGuards building;
        final String previousTask;

        DefenseEntry(AbstractBuildingGuards building, String previousTask) {
            this.building = building;
            this.previousTask = previousTask;
        }
    }

    private static final class DelayedTask {
        int ticksLeft;
        final Runnable task;

        DelayedTask(int ticksLeft, Runnable task) {
            this.ticksLeft = ticksLeft;
            this.task = task;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new HashMap<>();
    private static final ArrayDeque<PendingStart> PENDING = new ArrayDeque<>();
    private static final List<DelayedTask> DELAYED = new ArrayList<>();
    private static final Map<BlockPos, GuardFollow> GUARD_FOLLOWS = new HashMap<>();
    private static final List<DefenseEntry> DEFENSE = new ArrayList<>();
    private static int defenseAge = 0;

    private static final double ARRIVE_BUILDING_SQ = 3.5 * 3.5 * 4;
    private static final double ARRIVE_POS_SQ = 6.25;
    private static final double FOLLOW_GAP_SQ = 5.0 * 5.0;
    private static final int GUARD_FOLLOW_MAX_TICKS = 20 * 60 * 20;   // 20 min
    private static final int DEFENSE_MAX_TICKS = 20 * 60 * 30;        // 30 min
    private static final int HOLD_TICKS = 20 * 60 * 10;               // gather hold: 10 min
    private static int tickCounter = 0;

    private ErrandManager() {
    }

    // ------------------------------------------------------------------ starts

    public static synchronized void startBuildingErrand(AbstractEntityCitizen citizen, BlockPos pos, String name) {
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.TO_BUILDING, citizen, pos, name, null, null,
                ARRIVE_BUILDING_SQ, 20 * 240, null, false, null, 0));
        ConversationManager.markBusy(citizen);
    }

    public static synchronized void startPosErrand(AbstractEntityCitizen citizen, BlockPos pos, String name,
                                                   int timeout, double arriveDistSq) {
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.TO_POS, citizen, pos, name, null, null,
                arriveDistSq, timeout, null, false, null, 0));
        ConversationManager.markBusy(citizen);
    }

    public static synchronized void startWaitErrand(AbstractEntityCitizen citizen, int minutes) {
        BlockPos anchor = citizen.blockPosition();
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.WAIT, citizen, anchor, "waiting spot", null, null,
                0, minutes * 60 * 20, null, false, null, 0));
        ConversationManager.markBusy(citizen);
    }

    public static synchronized void startFollowErrand(AbstractEntityCitizen citizen, UUID playerId) {
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.FOLLOW, citizen, null, "player", playerId, null,
                0, 20 * 300, null, false, null, 0));
        ConversationManager.markBusy(citizen);
    }

    public static synchronized void startMessengerErrand(AbstractEntityCitizen citizen, IBuilding building,
                                                         UUID playerId, String name) {
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.MESSENGER, citizen, building.getPosition(), name,
                playerId, building, ARRIVE_BUILDING_SQ, 20 * 240, null, false, null, 0));
        ConversationManager.markBusy(citizen);
        ColonistErrands.LOGGER.info("[Errand] {} -> messenger to '{}' at {}", safeName(citizen), name,
                building.getPosition().toShortString());
    }

    /** Walk to the restaurant and, on arrival, actually EAT from the building's stock. */
    public static synchronized void startEatErrand(AbstractEntityCitizen citizen, IBuilding restaurant, UUID playerId) {
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.TO_BUILDING, citizen, restaurant.getPosition(), "restaurant",
                playerId, restaurant, ARRIVE_BUILDING_SQ, 20 * 240, GROUP_EAT, false, null, 0));
        ConversationManager.markBusy(citizen);
        ColonistErrands.LOGGER.info("[Errand] {} -> going to EAT at the restaurant", safeName(citizen));
    }

    public static synchronized void startFetchErrand(AbstractEntityCitizen citizen, IBuilding warehouse,
                                                     UUID playerId, Item item, int count) {
        ERRANDS.put(citizen.getUUID(), new Errand(Kind.FETCH_PICKUP, citizen, warehouse.getPosition(), "warehouse",
                playerId, warehouse, ARRIVE_BUILDING_SQ, 20 * 240, null, false, item, count));
        ConversationManager.markBusy(citizen);
        ColonistErrands.LOGGER.info("[Errand] {} -> fetching {}x {} from warehouse", safeName(citizen), count,
                item.getDescription().getString());
    }

    /** Courier logistics: pick up at the warehouse, then deliver into another building's racks. */
    public static synchronized void startDeliverErrand(AbstractEntityCitizen citizen, IBuilding warehouse,
                                                       IBuilding dest, String destName, UUID playerId,
                                                       Item item, int count) {
        Errand e = new Errand(Kind.FETCH_PICKUP, citizen, warehouse.getPosition(), destName,
                playerId, warehouse, ARRIVE_BUILDING_SQ, 20 * 240, null, false, item, count);
        e.destBuilding = dest;
        ERRANDS.put(citizen.getUUID(), e);
        ConversationManager.markBusy(citizen);
        ColonistErrands.LOGGER.info("[Courier] {} -> delivering {}x {} from warehouse to '{}'",
                safeName(citizen), count, item.getDescription().getString(), destName);
    }

    /** The citizen LEADS the player to another citizen (waits when the player falls behind). */
    public static synchronized void startGuideErrand(AbstractEntityCitizen guide, AbstractEntityCitizen target,
                                                     UUID playerId, String targetName) {
        Errand e = new Errand(Kind.GUIDE, guide, null, targetName, playerId, null,
                25.0, 20 * 480, null, false, null, 0);
        e.targetCitizen = target;
        ERRANDS.put(guide.getUUID(), e);
        ConversationManager.markBusy(guide);
        ColonistErrands.LOGGER.info("[Guide] {} -> leading the player to {}", safeName(guide), targetName);
    }

    /** Plain staggered walk (everyone_home). */
    public static synchronized void enqueuePosErrand(AbstractEntityCitizen citizen, BlockPos pos, String name,
                                                     int timeout, double arriveDistSq) {
        PENDING.add(new PendingStart(Kind.TO_POS, citizen, pos, name, null, timeout, arriveDistSq, null, false));
    }

    /** Gather walk: on arrival the citizen HOLDS the spot until dismissed (or ~10 min). */
    public static synchronized void enqueueGatherErrand(AbstractEntityCitizen citizen, BlockPos pos, String name,
                                                        int timeout, double arriveDistSq) {
        PENDING.add(new PendingStart(Kind.TO_POS, citizen, pos, name, null, timeout, arriveDistSq,
                GROUP_GATHER, true));
    }

    /** Messenger part 2: citizen walks to the (moving) player and starts a conversation on arrival. */
    public static synchronized void enqueueContactPlayer(AbstractEntityCitizen citizen, UUID playerId) {
        PENDING.add(new PendingStart(Kind.CONTACT_PLAYER, citizen, null, "player", playerId, 20 * 480, 9.0,
                null, false));
    }

    // ------------------------------------------------------------ guard follow

    public static synchronized void registerGuardFollow(AbstractBuildingGuards building, String previousTask,
                                                        String previousFollowMode, UUID playerId) {
        GUARD_FOLLOWS.put(building.getPosition(), new GuardFollow(building, previousTask, previousFollowMode, playerId));
    }

    public static synchronized boolean hasGuardFollow(IBuilding building) {
        return building != null && GUARD_FOLLOWS.containsKey(building.getPosition());
    }

    public static synchronized boolean stopGuardFollowFor(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null || data.getWorkBuilding() == null) return false;
            GuardFollow gf = GUARD_FOLLOWS.remove(data.getWorkBuilding().getPosition());
            if (gf != null) {
                restoreGuard(gf);
                return true;
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("stopGuardFollowFor failed", t);
        }
        return false;
    }

    private static void restoreGuard(GuardFollow gf) {
        try {
            GuardTaskSetting setting = gf.building.getSetting(AbstractBuildingGuards.GUARD_TASK);
            if (setting != null) {
                String prev = gf.previousTask;
                if (prev == null || prev.equals(GuardTaskSetting.FOLLOW)) {
                    prev = GuardTaskSetting.PATROL;
                }
                if (!GuardSettings.set(setting, prev)) {
                    GuardSettings.repair(setting);
                }
            }
            GuardFollowModeSetting fm = gf.building.getSetting(AbstractBuildingGuards.FOLLOW_MODE);
            if (fm != null && gf.previousFollowMode != null) {
                GuardSettings.set(fm, gf.previousFollowMode);
            }
            try {
                gf.building.markDirty();
            } catch (Throwable ignored) {
            }
            kickGuardAI(gf.building);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Failed to restore guard task setting", t);
        }
    }

    /**
     * True while the citizen's guard building is player-escorting or holding the
     * defensive line - used to mute mc_talking's urgent-contact chatter for
     * guards on military duty (a crowd of escorts otherwise keeps "reporting").
     */
    public static synchronized boolean isOnMilitaryDuty(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null || data.getWorkBuilding() == null) return false;
            BlockPos pos = data.getWorkBuilding().getPosition();
            if (GUARD_FOLLOWS.containsKey(pos)) return true;
            for (DefenseEntry d : DEFENSE) {
                if (d.building.getPosition().equals(pos)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Forces every guard of the building to re-evaluate its task NOW (same trick
     * mc's setPlayerToFollow uses) - without it guards can stay stuck in their old
     * state (e.g. keep following the player) long after the setting changed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void kickGuardAI(AbstractBuildingGuards building) {
        try {
            for (ICitizenData cd : building.getAllAssignedCitizen()) {
                if (cd == null) continue;
                AbstractJobGuard job = cd.getJob(AbstractJobGuard.class);
                if (job == null || job.getWorkerAI() == null) continue;
                ((AbstractEntityAIGuard) job.getWorkerAI())
                        .registerTarget(new AIOneTimeEventTarget(AIWorkerState.PREPARING));
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("kickGuardAI failed", t);
        }
    }

    // ---------------------------------------------------------------- defense

    /** @return previous task of the building (for info), registering it for stand-down. */
    public static synchronized void registerDefense(AbstractBuildingGuards building, String previousTask) {
        DEFENSE.add(new DefenseEntry(building, previousTask));
        defenseAge = 0;
    }

    public static synchronized boolean hasActiveDefense() {
        return !DEFENSE.isEmpty();
    }

    /** Is a defense line up for THIS colony? (two colonies now - lines are independent) */
    public static synchronized boolean hasActiveDefense(int colonyId) {
        for (DefenseEntry d : DEFENSE) {
            if (colonyOf(d) == colonyId) {
                return true;
            }
        }
        return false;
    }

    private static int colonyOf(DefenseEntry d) {
        try {
            return d.building.getColony().getID();
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    /** Restores all defense-formation overrides. @return number of towers restored. */
    public static synchronized int standDownDefense() {
        return standDownDefense(null);
    }

    /**
     * Restores defense-formation overrides - only of the given colony when
     * colonyId != null (Marko + girlfriend each have a colony now; one raid
     * ending must not strip the OTHER colony's line).
     */
    public static synchronized int standDownDefense(Integer colonyId) {
        int n = 0;
        java.util.Iterator<DefenseEntry> it = DEFENSE.iterator();
        while (it.hasNext()) {
            DefenseEntry d = it.next();
            if (colonyId != null && colonyOf(d) != colonyId) {
                continue;
            }
            try {
                GuardTaskSetting s = d.building.getSetting(AbstractBuildingGuards.GUARD_TASK);
                if (s != null) {
                    String prev = d.previousTask;
                    if (prev == null || prev.equals(GuardTaskSetting.GUARD)) {
                        prev = GuardTaskSetting.PATROL;
                    }
                    if (!GuardSettings.set(s, prev)) {
                        GuardSettings.repair(s);
                    }
                }
                try {
                    d.building.markDirty();
                } catch (Throwable ignored) {
                }
                kickGuardAI(d.building);
                n++;
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("standDown failed for a tower", t);
            }
            it.remove();
        }
        return n;
    }

    // ---------------------------------------------------------------- dismiss

    /** Releases all gathered/holding citizens, ends all guard escorts and stands down the defense. */
    public static synchronized int dismissAll() {
        int n = 0;
        Iterator<Map.Entry<UUID, Errand>> it = ERRANDS.entrySet().iterator();
        while (it.hasNext()) {
            Errand e = it.next().getValue();
            if (GROUP_GATHER.equals(e.group)) {
                it.remove();
                if (e.citizen != null) release(e.citizen);
                n++;
            }
        }
        PENDING.removeIf(p -> GROUP_GATHER.equals(p.group()));
        for (GuardFollow gf : GUARD_FOLLOWS.values()) {
            restoreGuard(gf);
            n++;
        }
        GUARD_FOLLOWS.clear();
        n += standDownDefense();
        return n;
    }

    // ---------------------------------------------------------------- cancel

    public static synchronized boolean cancel(AbstractEntityCitizen citizen) {
        Errand e = ERRANDS.remove(citizen.getUUID());
        if (e != null) {
            release(citizen);
            return true;
        }
        return false;
    }

    public static synchronized boolean hasErrand(AbstractEntityCitizen citizen) {
        return ERRANDS.containsKey(citizen.getUUID());
    }

    private static void release(AbstractEntityCitizen citizen) {
        try {
            citizen.getNavigation().stop();
        } catch (Throwable ignored) {
        }
        ConversationManager.markNotBusy(citizen);
    }

    // ------------------------------------------------------------------ tick

    /** Run something on the server thread after a delay (used e.g. to let goodbye audio finish). */
    public static synchronized void runLater(int ticks, Runnable task) {
        DELAYED.add(new DelayedTask(Math.max(1, ticks), task));
    }

    public static synchronized void tick(MinecraftServer server) {
        tickCounter++;

        if (!DELAYED.isEmpty()) {
            Iterator<DelayedTask> dit = DELAYED.iterator();
            while (dit.hasNext()) {
                DelayedTask d = dit.next();
                if (--d.ticksLeft <= 0) {
                    dit.remove();
                    try {
                        d.task.run();
                    } catch (Throwable t) {
                        ColonistErrands.LOGGER.warn("Delayed task failed", t);
                    }
                }
            }
        }

        if (!PENDING.isEmpty() && tickCounter % 4 == 0) {
            PendingStart p = PENDING.poll();
            if (p != null && p.citizen() != null && p.citizen().isAlive() && !p.citizen().isRemoved()
                    && !ERRANDS.containsKey(p.citizen().getUUID())) {
                ERRANDS.put(p.citizen().getUUID(), new Errand(p.kind(), p.citizen(), p.pos(), p.name(),
                        p.playerId(), null, p.arriveDistSq(), p.timeout(), p.group(), p.holdOnArrival(), null, 0));
                ConversationManager.markBusy(p.citizen());
                if (p.kind() == Kind.CONTACT_PLAYER) {
                    ColonistErrands.LOGGER.info("[Errand] {} starting walk to the player (messenger contact)",
                            safeName(p.citizen()));
                }
            }
        }

        if (!GUARD_FOLLOWS.isEmpty()) {
            Iterator<Map.Entry<BlockPos, GuardFollow>> git = GUARD_FOLLOWS.entrySet().iterator();
            while (git.hasNext()) {
                GuardFollow gf = git.next().getValue();
                gf.age++;
                if (gf.age > GUARD_FOLLOW_MAX_TICKS) {
                    restoreGuard(gf);
                    git.remove();
                    continue;
                }
                if (gf.age % 100 == 0) {
                    ServerPlayer p = server.getPlayerList().getPlayer(gf.playerId);
                    if (p == null || !p.isAlive()) {
                        restoreGuard(gf);
                        git.remove();
                    }
                }
            }
        }

        if (!DEFENSE.isEmpty() && ++defenseAge > DEFENSE_MAX_TICKS) {
            int n = standDownDefense();
            ColonistErrands.LOGGER.info("[Defense] Auto stand-down after 30 min ({} towers)", n);
        }

        if (ERRANDS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Errand>> it = ERRANDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Errand> entry = it.next();
            Errand e = entry.getValue();
            AbstractEntityCitizen citizen = e.citizen;

            if (citizen == null || !citizen.isAlive() || citizen.isRemoved()) {
                it.remove();
                if (citizen != null) release(citizen);
                continue;
            }
            if (++e.age > e.timeout) {
                it.remove();
                release(citizen);
                if (e.kind == Kind.CONTACT_PLAYER || e.kind == Kind.FETCH_DELIVER) {
                    ServerPlayer p = server.getPlayerList().getPlayer(e.playerId);
                    if (p != null) {
                        p.sendSystemMessage(Component.literal(e.kind == Kind.FETCH_DELIVER
                                ? "[Courier] " + safeName(citizen) + " couldn't catch up with you (still has the items)."
                                : "[Messenger] " + safeName(citizen) + " couldn't catch up with you and went back to work."));
                    }
                    ColonistErrands.LOGGER.info("[Errand] {} walk-to-player timed out", safeName(citizen));
                }
                continue;
            }

            ConversationManager.markBusy(citizen);

            if (ConversationManager.getPlayerForEntity(citizen.getUUID()) != null) {
                continue;
            }

            switch (e.kind) {
                case TO_BUILDING, TO_POS, MESSENGER, FETCH_PICKUP, DELIVER_BUILDING -> {
                    e.walkTicks++;
                    double distSq = citizen.blockPosition().distSqr(e.targetPos);
                    boolean minWalkDone = (e.kind != Kind.MESSENGER && e.kind != Kind.FETCH_PICKUP) || e.walkTicks >= 40;
                    if (distSq <= e.arriveDistSq && minWalkDone) {
                        if (e.kind == Kind.DELIVER_BUILDING) {
                            stopNav(citizen);
                            deliverToBuilding(e, citizen, server);
                            it.remove();
                            release(citizen);
                        } else if (e.kind == Kind.MESSENGER) {
                            deliverMessage(e, server);
                            stopNav(citizen);
                            entry.setValue(new Errand(Kind.WAIT, citizen, citizen.blockPosition(), "delivered",
                                    null, null, 0, 60, null, false, null, 0));
                        } else if (e.kind == Kind.FETCH_PICKUP) {
                            int collectedHere = pickupFromWarehouse(e);
                            int collected = e.collectedSoFar + collectedHere;
                            stopNav(citizen);
                            // Marko's "162 in stock but only got 54": check_stock counts ALL
                            // warehouses, the pickup only searched the nearest one. If still
                            // short, walk on to the next warehouse and keep gathering.
                            if (collected < e.fetchCount) {
                                IBuilding next = nextWarehouse(e, citizen);
                                if (next != null) {
                                    Errand cont = new Errand(Kind.FETCH_PICKUP, citizen, next.getPosition(), e.targetName,
                                            e.playerId, next, ARRIVE_BUILDING_SQ, 20 * 240, null, false,
                                            e.fetchItem, e.fetchCount);
                                    cont.collectedSoFar = collected;
                                    cont.destBuilding = e.destBuilding;
                                    cont.visitedWarehouses = e.visitedWarehouses != null
                                            ? e.visitedWarehouses : new java.util.HashSet<>();
                                    cont.visitedWarehouses.add(e.building.getPosition());
                                    entry.setValue(cont);
                                    ColonistErrands.LOGGER.info("[Courier] {} has {}/{}x {} - continuing to the next warehouse",
                                            safeName(citizen), collected, e.fetchCount,
                                            e.fetchItem.getDescription().getString());
                                    continue;
                                }
                            }
                            if (collected <= 0) {
                                ServerPlayer pl = server.getPlayerList().getPlayer(e.playerId);
                                if (pl != null) {
                                    pl.sendSystemMessage(Component.literal("[Courier] " + safeName(citizen)
                                            + ": the warehouse has no " + e.fetchItem.getDescription().getString() + "."));
                                }
                                it.remove();
                                release(citizen);
                            } else if (e.destBuilding != null) {
                                notePartialPickup(e, collected, server);
                                ColonistErrands.LOGGER.info("[Errand] {} picked up {}x {} - delivering to '{}'",
                                        safeName(citizen), collected, e.fetchItem.getDescription().getString(), e.targetName);
                                entry.setValue(new Errand(Kind.DELIVER_BUILDING, citizen, e.destBuilding.getPosition(),
                                        e.targetName, e.playerId, e.destBuilding, ARRIVE_BUILDING_SQ, 20 * 480,
                                        null, false, e.fetchItem, collected));
                            } else {
                                notePartialPickup(e, collected, server);
                                ColonistErrands.LOGGER.info("[Errand] {} picked up {}x {} - delivering",
                                        safeName(citizen), collected, e.fetchItem.getDescription().getString());
                                entry.setValue(new Errand(Kind.FETCH_DELIVER, citizen, null, "player",
                                        e.playerId, null, ARRIVE_POS_SQ, 20 * 480, null, false, e.fetchItem, collected));
                            }
                        } else if (GROUP_EAT.equals(e.group) && e.building != null) {
                            stopNav(citizen);
                            feedAtRestaurant(e, server);
                            it.remove();
                            release(citizen);
                        } else if (e.holdOnArrival) {
                            stopNav(citizen);
                            entry.setValue(new Errand(Kind.WAIT, citizen, citizen.blockPosition(), e.targetName,
                                    null, null, 0, HOLD_TICKS, e.group, false, null, 0));
                        } else {
                            it.remove();
                            release(citizen);
                        }
                        continue;
                    }
                    walkTowards(citizen, e);
                }
                case WAIT -> {
                    if (e.age % 20 == 0) {
                        double distSq = citizen.blockPosition().distSqr(e.targetPos);
                        if (distSq > 9) {
                            walkTowards(citizen, e);
                        } else if (!citizen.getNavigation().isDone()) {
                            citizen.getNavigation().stop();
                        }
                    }
                }
                case FOLLOW -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(e.playerId);
                    if (player == null || !player.isAlive() || player.level() != citizen.level()) {
                        it.remove();
                        release(citizen);
                        continue;
                    }
                    double distSq = citizen.distanceToSqr((Entity) player);
                    if (distSq > FOLLOW_GAP_SQ && e.age % 20 == 0) {
                        citizen.getNavigation().moveTo((Entity) player, 1.2);
                    } else if (distSq <= FOLLOW_GAP_SQ && !citizen.getNavigation().isDone() && e.age % 20 == 0) {
                        citizen.getNavigation().stop();
                    }
                }
                case CONTACT_PLAYER, FETCH_DELIVER -> {
                    e.walkTicks++;
                    ServerPlayer player = server.getPlayerList().getPlayer(e.playerId);
                    if (player == null || !player.isAlive() || player.level() != citizen.level()) {
                        ColonistErrands.LOGGER.info("[Errand] {} walk-to-player aborted (player gone)", safeName(citizen));
                        it.remove();
                        release(citizen);
                        continue;
                    }
                    double distSq = citizen.distanceToSqr((Entity) player);
                    if (distSq <= e.arriveDistSq) {
                        it.remove();
                        release(citizen);
                        if (e.kind == Kind.FETCH_DELIVER) {
                            deliverFetched(e, citizen, player);
                        } else {
                            try {
                                if (ConversationManager.isPlayerInConversation(player.getUUID())) {
                                    ColonistErrands.LOGGER.info("[Errand] {} reached player, but player is already in a conversation - standing by", safeName(citizen));
                                } else if (!ConversationManager.canCitizenSpeak(citizen, true)) {
                                    ColonistErrands.LOGGER.info("[Errand] {} reached player, but cannot speak (sleeping/visitor) - standing by", safeName(citizen));
                                } else {
                                    ConversationManager.forceRemoveCooldown(citizen);
                                    ConversationManager.startPlayerConversation(player, citizen);
                                    ColonistErrands.LOGGER.info("[Errand] {} reached player - starting conversation", safeName(citizen));
                                }
                            } catch (Throwable t) {
                                ColonistErrands.LOGGER.warn("startPlayerConversation on messenger arrival failed", t);
                            }
                        }
                        continue;
                    }
                    if (e.age % 20 == 0) {
                        citizen.getNavigation().moveTo((Entity) player, 1.2);
                    }
                    if (e.walkTicks % 200 == 0) {
                        ColonistErrands.LOGGER.info("[Errand] {} walking to player, {} blocks away",
                                safeName(citizen), Math.round(Math.sqrt(distSq)));
                    }
                }
                case GUIDE -> {
                    AbstractEntityCitizen target = e.targetCitizen;
                    ServerPlayer player = server.getPlayerList().getPlayer(e.playerId);
                    if (target == null || !target.isAlive() || target.isRemoved()
                            || player == null || !player.isAlive() || player.level() != citizen.level()) {
                        it.remove();
                        release(citizen);
                        continue;
                    }
                    if (citizen.distanceToSqr(target) <= e.arriveDistSq) {
                        it.remove();
                        release(citizen);
                        stopNav(citizen);
                        player.sendSystemMessage(Component.literal("[Guide] " + safeName(citizen)
                                + ": here we are - " + e.targetName + " is right there."));
                        ColonistErrands.LOGGER.info("[Guide] {} led the player to {}", safeName(citizen), e.targetName);
                        continue;
                    }
                    double playerGapSq = citizen.distanceToSqr((Entity) player);
                    if (playerGapSq > 20 * 20) {
                        // Wait for the player to catch up before walking on.
                        if (e.age % 20 == 0) {
                            stopNav(citizen);
                            citizen.getLookControl().setLookAt((Entity) player, 30.0f, 30.0f);
                        }
                    } else if (e.age % 20 == 0) {
                        citizen.getNavigation().moveTo((Entity) target, 1.0);
                    }
                }
            }
        }
    }

    /** If even ALL warehouses (or the courier's bags) ran short, say so in chat. */
    private static void notePartialPickup(Errand e, int collected, MinecraftServer server) {
        if (collected >= e.fetchCount) {
            return;
        }
        try {
            ServerPlayer pl = server.getPlayerList().getPlayer(e.playerId);
            if (pl != null) {
                pl.sendSystemMessage(Component.literal("[Courier] " + safeName(e.citizen) + " only gathered "
                        + collected + " of the " + e.fetchCount + "x "
                        + e.fetchItem.getDescription().getString()
                        + " (stock ran short or their bags are full) - bringing what they could."));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Next unvisited warehouse of the courier's colony, nearest first; null when none left. */
    private static IBuilding nextWarehouse(Errand e, AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null || data.getColony() == null) return null;
            IBuilding best = null;
            double bestD = Double.MAX_VALUE;
            for (IBuilding b : data.getColony().getServerBuildingManager().getBuildings().values()) {
                if (!b.getBuildingType().getRegistryName().getPath().equals("warehouse")) continue;
                BlockPos p = b.getPosition();
                if (p.equals(e.building.getPosition())) continue;
                if (e.visitedWarehouses != null && e.visitedWarehouses.contains(p)) continue;
                double d = citizen.blockPosition().distSqr(p);
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** DELIVER_BUILDING arrival: move the carried items into the destination building's racks. */
    private static void deliverToBuilding(Errand e, AbstractEntityCitizen citizen, MinecraftServer server) {
        int delivered = 0;
        int leftover = 0;
        try {
            IItemHandler inv = citizen.getInventoryCitizen();
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack inSlot = inv.getStackInSlot(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != e.fetchItem) continue;
                ItemStack taken = inv.extractItem(slot, inSlot.getCount(), false);
                ItemStack rest = insertIntoRacks(e.building, citizen, taken);
                delivered += taken.getCount() - rest.getCount();
                if (!rest.isEmpty()) {
                    leftover += rest.getCount();
                    ItemHandlerHelper.insertItemStacked(inv, rest, false);
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("deliverToBuilding failed", t);
        }
        String itemName = e.fetchItem.getDescription().getString();
        ColonistErrands.LOGGER.info("[Courier] {} delivered {}x {} to '{}'{}", safeName(citizen), delivered,
                itemName, e.targetName, leftover > 0 ? " (" + leftover + " didn't fit - kept)" : "");
        ServerPlayer pl = server.getPlayerList().getPlayer(e.playerId);
        if (pl != null) {
            pl.sendSystemMessage(Component.literal("[Courier] " + safeName(citizen) + " delivered " + delivered
                    + "x " + itemName + " to the " + e.targetName
                    + (leftover > 0 ? " (racks full - kept " + leftover + ")." : ".")));
        }
    }

    private static ItemStack insertIntoRacks(IBuilding building, AbstractEntityCitizen citizen, ItemStack stack) {
        ItemStack rest = stack;
        try {
            for (BlockPos rackPos : building.getContainers()) {
                if (rest.isEmpty()) break;
                BlockEntity be = citizen.level().getBlockEntity(rackPos);
                if (!(be instanceof AbstractTileEntityRack rack)) continue;
                IItemHandler handler = rack.getItemHandlerCap();
                if (handler == null) continue;
                rest = ItemHandlerHelper.insertItemStacked(handler, rest, false);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("insertIntoRacks failed", t);
        }
        return rest;
    }

    private static void stopNav(AbstractEntityCitizen citizen) {
        try {
            citizen.getNavigation().stop();
        } catch (Throwable ignored) {
        }
    }

    private static void walkTowards(AbstractEntityCitizen citizen, Errand e) {
        try {
            EntityNavigationUtils.walkToPos(citizen, e.targetPos, 3, true);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("walkToPos failed, falling back to vanilla nav", t);
            if (e.age % 20 == 1) {
                citizen.getNavigation().moveTo(e.targetPos.getX(), e.targetPos.getY(), e.targetPos.getZ(), 1.1);
            }
        }
    }

    // ------------------------------------------------------------- messenger

    private static void deliverMessage(Errand e, MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(e.playerId);
        String messengerName = safeName(e.citizen);
        try {
            AbstractEntityCitizen target = null;
            AbstractEntityCitizen fallback = null;
            for (ICitizenData cd : e.building.getAllAssignedCitizen()) {
                if (cd == null) continue;
                Optional<AbstractEntityCitizen> opt = cd.getEntity();
                if (opt == null || opt.isEmpty()) continue;
                AbstractEntityCitizen b = opt.get();
                if (!b.isAlive() || b.getUUID().equals(e.citizen.getUUID())) continue;
                if (fallback == null) fallback = b;
                if (!ConversationManager.isCitizenBusy(b)) {
                    target = b;
                    break;
                }
            }
            if (target == null) target = fallback;
            if (player == null) {
                ColonistErrands.LOGGER.info("[Messenger] {} arrived at '{}' but the player is offline", messengerName, e.targetName);
                return;
            }
            if (target == null) {
                ColonistErrands.LOGGER.info("[Messenger] {} arrived at '{}' - nobody there to deliver to", messengerName, e.targetName);
                player.sendSystemMessage(Component.literal(
                        "[Messenger] " + messengerName + ": there is nobody at the " + e.targetName + " to deliver the message to."));
                return;
            }
            String targetName = safeName(target);
            enqueueContactPlayer(target, e.playerId);
            ColonistErrands.LOGGER.info("[Messenger] {} delivered at '{}' - sending {} to the player", messengerName, e.targetName, targetName);
            player.sendSystemMessage(Component.literal(
                    "[Messenger] " + messengerName + " delivered the message - " + targetName + " is coming to you."));
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Messenger delivery failed", t);
        }
    }

    // ---------------------------------------------------------------- courier

    /** Moves up to fetchCount of fetchItem from the warehouse racks into the citizen's inventory. */
    private static int pickupFromWarehouse(Errand e) {
        int collected = 0;
        try {
            IItemHandler citizenInv = e.citizen.getInventoryCitizen();
            for (BlockPos rackPos : e.building.getContainers()) {
                if (collected >= e.fetchCount) break;
                BlockEntity be = e.citizen.level().getBlockEntity(rackPos);
                if (!(be instanceof AbstractTileEntityRack rack)) continue;
                IItemHandler handler = rack.getItemHandlerCap();
                if (handler == null) continue;
                for (int slot = 0; slot < handler.getSlots() && collected < e.fetchCount; slot++) {
                    ItemStack inSlot = handler.getStackInSlot(slot);
                    if (inSlot.isEmpty() || inSlot.getItem() != e.fetchItem) continue;
                    ItemStack extracted = handler.extractItem(slot, Math.min(e.fetchCount - collected, inSlot.getCount()), false);
                    if (extracted.isEmpty()) continue;
                    int took = extracted.getCount();
                    ItemStack leftover = ItemHandlerHelper.insertItemStacked(citizenInv, extracted, false);
                    if (!leftover.isEmpty()) {
                        took -= leftover.getCount();
                        ItemStack back = handler.insertItem(slot, leftover, false);
                        if (!back.isEmpty()) {
                            // Citizen inventory AND rack full - drop it so nothing is voided.
                            e.citizen.spawnAtLocation(back);
                        }
                    }
                    collected += took;
                    if (took > 0 && collected >= e.fetchCount) break;
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("pickupFromWarehouse failed", t);
        }
        return collected;
    }

    /** Hands the fetched items over to the player. */
    private static void deliverFetched(Errand e, AbstractEntityCitizen citizen, ServerPlayer player) {
        int given = 0;
        try {
            IItemHandler inv = citizen.getInventoryCitizen();
            int remaining = e.fetchCount;
            for (int slot = 0; slot < inv.getSlots() && remaining > 0; slot++) {
                ItemStack inSlot = inv.getStackInSlot(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != e.fetchItem) continue;
                ItemStack extracted = inv.extractItem(slot, Math.min(remaining, inSlot.getCount()), false);
                if (extracted.isEmpty()) continue;
                given += extracted.getCount();
                remaining -= extracted.getCount();
                player.getInventory().placeItemBackInInventory(extracted);
            }
            String itemName = e.fetchItem.getDescription().getString();
            player.sendSystemMessage(Component.literal(
                    "[Courier] " + safeName(citizen) + " brought you " + given + "x " + itemName + "."));
            ColonistErrands.LOGGER.info("[Errand] {} delivered {}x {} to the player", safeName(citizen), given, itemName);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("deliverFetched failed", t);
        }
    }

    /** Serves the citizen real food from the restaurant's racks until they are full. */
    private static void feedAtRestaurant(Errand e, MinecraftServer server) {
        AbstractEntityCitizen citizen = e.citizen;
        int eaten = 0;
        double sat = 0;
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null) return;
            sat = data.getSaturation();
            outer:
            for (BlockPos rackPos : e.building.getContainers()) {
                BlockEntity be = citizen.level().getBlockEntity(rackPos);
                if (!(be instanceof AbstractTileEntityRack rack)) continue;
                IItemHandler handler = rack.getItemHandlerCap();
                if (handler == null) continue;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack inSlot = handler.getStackInSlot(slot);
                    if (inSlot.isEmpty()) continue;
                    FoodProperties fp = inSlot.get(DataComponents.FOOD);
                    if (fp == null || fp.nutrition() <= 0) continue;
                    while (sat < 18.0 && eaten < 6 && !handler.getStackInSlot(slot).isEmpty()) {
                        ItemStack one = handler.extractItem(slot, 1, false);
                        if (one.isEmpty()) break;
                        data.increaseSaturation(fp.nutrition());
                        sat = data.getSaturation();
                        eaten++;
                        try {
                            citizen.playSound(SoundEvents.GENERIC_EAT, 1.0f, 1.0f);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (sat >= 18.0 || eaten >= 6) break outer;
                }
            }
            try {
                data.setJustAte(true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("feedAtRestaurant failed", t);
        }
        ServerPlayer player = server.getPlayerList().getPlayer(e.playerId);
        if (eaten > 0) {
            ColonistErrands.LOGGER.info("[Restaurant] {} ate {} item(s), saturation now {}", safeName(citizen), eaten, Math.round(sat));
            if (player != null) {
                player.sendSystemMessage(Component.literal("[Restaurant] " + safeName(citizen) + " ate at the restaurant ("
                        + Math.round(sat) + "/20)."));
            }
        } else {
            ColonistErrands.LOGGER.info("[Restaurant] {} found no food at the restaurant", safeName(citizen));
            if (player != null) {
                player.sendSystemMessage(Component.literal("[Restaurant] " + safeName(citizen)
                        + ": there is no food at the restaurant!"));
            }
        }
    }

    private static String safeName(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data != null && data.getName() != null) return data.getName();
        } catch (Throwable ignored) {
        }
        return "citizen";
    }

    // ---------------------------------------------------------------- global

    public static synchronized void clearAll() {
        for (Errand e : ERRANDS.values()) {
            if (e.citizen != null) release(e.citizen);
        }
        ERRANDS.clear();
        PENDING.clear();
        DELAYED.clear();
        for (GuardFollow gf : GUARD_FOLLOWS.values()) {
            restoreGuard(gf);
        }
        GUARD_FOLLOWS.clear();
        standDownDefense();
    }
}
