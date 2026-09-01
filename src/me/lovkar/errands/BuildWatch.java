package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.jobs.AbstractJobStructure;
import com.minecolonies.core.entity.ai.workers.AbstractAISkeleton;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import me.sshcrack.mc_talking.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's builder Prudence sat at 99% on the Chef's Kitchen with every resource
 * in hand, and when asked she said she was stuck on a RESIDENCE - a building she
 * had finished long ago.
 * <p>
 * Both halves have the same cause: nobody was telling anyone the truth about the
 * build. MineColonies' own failure mode here is silent by design - in
 * {@code AbstractEntityAIStructure.structureStep} the AI returns the SAME state
 * forever when the builder cannot WALK to the next position, with no error, no
 * chat and no progress. So we watch every builder ourselves: what they are
 * really building, which stage they are on, and how long that has not moved.
 * <p>
 * "No progress" on its own is NOT a stall, though - a builder who is asleep,
 * eating, mourning, sheltering from a raid or simply waiting on a delivery has
 * not moved either, and shouting "stuck!" at 2am is just noise. So before the
 * clock is allowed to run we ask MineColonies why the builder is not working:
 * {@code CitizenAIState} is the colonist's master state machine (SLEEP, EATING,
 * SICK, MOURN, IDLE...), open requests on the hut are the material queue, and
 * {@code isIdleAtJob()} is MineColonies' own JobStatus.STUCK - missing a tool.
 * While any of those hold the stall clock is frozen and the citizen simply says
 * the honest reason instead.
 */
public final class BuildWatch {

    private BuildWatch() {
    }

    private static final class Progress {
        String order = "";
        String stage = "";
        String iterator = "";
        BlockPos standing;
        /** Non-null while the builder legitimately cannot work; the honest reason. */
        String blocked;
        /** True when the reason is a work-time problem worth a word after a while. */
        boolean waiting;
        long sinceMs = System.currentTimeMillis();
        long warnedMs = 0;
        /** Set while a build sits still during WORK time (materials, tool, paused, idle). */
        long waitSinceMs = 0;
        long waitWarnedMs = 0;
    }

    /** citizen id -> what they were doing last time we looked. */
    private static final Map<Integer, Progress> SEEN = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COLONY_OF_NAME = new ConcurrentHashMap<>();
    /** A build that has not moved for this long, with nothing blocking it, is stuck. */
    private static final long STUCK_MS = 6 * 60_000L;
    /** A build sitting still during WORK time for this long is worth a word. */
    private static final long SITTING_MS = 20 * 60_000L;
    private static final long WARN_AGAIN_MS = 15 * 60_000L;

    /**
     * The builder AI states in which blocks are actually being laid. MineColonies'
     * silent stall lives inside these: {@code structureStep} (BUILDING_STEP) and
     * {@code doMining} (MINE_BLOCK) both {@code return this.getState()} forever
     * when {@code walkToConstructionSite} fails. Every other state means the
     * builder is doing something else on purpose - fetching, dumping, waiting on a
     * delivery, paused - and the stall clock must not run.
     */
    private static final Set<String> LAYING_BLOCKS = Set.of(
            "BUILDING_STEP", "MINE_BLOCK", "START_BUILDING", "LOAD_STRUCTURE", "COMPLETE_BUILD");

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                    try {
                        Progress now = read(data, true);
                        if (now == null) {
                            SEEN.remove(data.getId());
                            continue;
                        }
                        Progress p = SEEN.get(data.getId());
                        boolean moved = p == null || !p.order.equals(now.order)
                                || !p.stage.equals(now.stage) || !p.iterator.equals(now.iterator);
                        if (moved) {
                            if (p != null) {
                                now.warnedMs = p.warnedMs;
                                now.waitWarnedMs = p.waitWarnedMs;
                            }
                            SEEN.put(data.getId(), now);
                            continue; // the build advanced - nothing to complain about
                        }
                        p.standing = now.standing;
                        p.blocked = now.blocked;
                        p.waiting = now.waiting;

                        if (p.blocked != null) {
                            // Asleep, eating, sick, mourning, on an errand, fetching materials...
                            // The builder is not failing - freeze the stall clock entirely.
                            p.sinceMs = nowMs;
                            p.warnedMs = Math.max(p.warnedMs, 0);
                            if (p.waiting) {
                                if (p.waitSinceMs == 0) {
                                    p.waitSinceMs = nowMs;
                                }
                                long sat = nowMs - p.waitSinceMs;
                                if (sat > SITTING_MS && nowMs - p.waitWarnedMs > WARN_AGAIN_MS) {
                                    p.waitWarnedMs = nowMs;
                                    broadcast(server, "[Build] " + data.getName() + " has been sitting on "
                                            + p.order + " for " + (sat / 60_000L)
                                            + " minutes - " + p.blocked
                                            + ". They are not stuck, the build is just not moving: check the "
                                            + "warehouse stock, whether a courier is free, and that the hut is not "
                                            + "paused.");
                                    ColonistErrands.LOGGER.info("[Build] {} sitting {} min on {} ({})",
                                            data.getName(), sat / 60_000L, p.order, p.blocked);
                                }
                            } else {
                                p.waitSinceMs = 0;
                            }
                            continue;
                        }
                        p.waitSinceMs = 0;

                        // Nothing is stopping them, and still nothing has moved: a real stall.
                        long stuckFor = nowMs - p.sinceMs;
                        if (stuckFor > STUCK_MS && nowMs - p.warnedMs > WARN_AGAIN_MS) {
                            p.warnedMs = nowMs;
                            String where = now.standing == null ? "" : " They are standing at "
                                    + now.standing.getX() + ", " + now.standing.getY() + ", " + now.standing.getZ() + ".";
                            broadcast(server, "[Build] " + data.getName() + " has made no progress on "
                                    + p.order + " for " + (stuckFor / 60_000L) + " minutes (stage: "
                                    + friendly(p.stage) + ") - awake, on site and not short of anything." + where
                                    + " Usually the next spot cannot be walked to. Note they cannot EAT in this "
                                    + "state either (BUILDING_STEP is flagged not-okay-to-eat), so do not leave "
                                    + "them in it. Clear the way, or PAUSE them for a moment - pausing is what "
                                    + "actually performs a restart.");
                            ColonistErrands.LOGGER.info("[Build] {} stuck on {} at stage {} for {} min",
                                    data.getName(), p.order, p.stage, stuckFor / 60_000L);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Null when this citizen is not a builder with an active work order.
     *
     * @param includeTalking false when the answer is for the citizen we are
     *                       speaking to right now - "busy talking" is never the
     *                       explanation THEY should give for their own build.
     */
    private static Progress read(ICitizenData data, boolean includeTalking) {
        try {
            if (!(data.getJob() instanceof AbstractJobStructure<?, ?>)) {
                return null;
            }
            IBuilding b = data.getWorkBuilding();
            if (!(b instanceof AbstractBuildingStructureBuilder hut)) {
                return null;
            }
            IWorkOrder wo = hut.getWorkOrder();
            if (wo == null) {
                return null;
            }
            Progress p = new Progress();
            try {
                p.order = wo.getDisplayName().getString();
            } catch (Throwable t) {
                p.order = "a building";
            }
            try {
                var progress = hut.getProgress();
                if (progress != null) {
                    BuildingProgressStage stage = progress.getB();
                    p.stage = stage == null ? "" : stage.name();
                    p.iterator = progress.getA() == null ? "" : progress.getA().toShortString();
                }
            } catch (Throwable ignored) {
            }
            try {
                p.standing = data.getEntity().map(AbstractEntityCitizen::blockPosition).orElse(null);
            } catch (Throwable ignored) {
            }
            p.blocked = blockedReason(data, hut, p, includeTalking);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Why this builder is legitimately not laying blocks right now, or null when
     * they really ought to be working. Everything here is a MineColonies fact, not
     * a guess: {@code CitizenAIState} is the colonist's own master state machine.
     */
    private static String blockedReason(ICitizenData data, AbstractBuildingStructureBuilder hut,
                                        Progress p, boolean includeTalking) {
        AbstractEntityCitizen entity = null;
        try {
            entity = data.getEntity().orElse(null);
        } catch (Throwable ignored) {
        }
        if (entity == null || !entity.isAlive()) {
            return "not loaded right now - no player is close enough for the game to run them";
        }
        try {
            if (data.isAsleep() || entity.isSleeping()) {
                return "asleep - builders do not work at night";
            }
        } catch (Throwable ignored) {
        }
        try {
            if (ErrandManager.hasErrand(entity)) {
                return "away from the site on an errand for you";
            }
        } catch (Throwable ignored) {
        }
        if (includeTalking) {
            try {
                if (ConversationManager.isCitizenBusy(entity)) {
                    return "standing in a conversation instead of working";
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (HospitalCheck.underCare(entity)) {
                return "in the hospital, not on the site";
            }
        } catch (Throwable ignored) {
        }
        try {
            if (data.shouldRestart()) {
                return "waiting to be restarted at their hut";
            }
        } catch (Throwable ignored) {
        }
        // The colonist's master state machine: anything but WORK/WORKING means the
        // job AI is not even being ticked.
        try {
            IState st = entity.getEntityStateController().getState();
            if (st == CitizenAIState.SLEEP) {
                boolean raided = false;
                try {
                    raided = data.getColony().getRaiderManager().isRaided();
                } catch (Throwable ignored) {
                }
                return raided ? "sheltering indoors while the colony is under attack" : "asleep";
            }
            if (st == CitizenAIState.EATING) {
                return "eating";
            }
            if (st == CitizenAIState.SICK) {
                return "sick and being looked after";
            }
            if (st == CitizenAIState.MOURN) {
                return "mourning a death in the colony";
            }
            if (st == CitizenAIState.FLEE) {
                return "running from danger";
            }
            if (st == CitizenAIState.INACTIVE) {
                return "not being ticked at all right now";
            }
            if (st == CitizenAIState.IDLE) {
                try {
                    if (entity.level().isRaining()) {
                        return "waiting out the rain";
                    }
                } catch (Throwable ignored) {
                }
                return "off the build site - the colony AI has them idle";
            }
        } catch (Throwable ignored) {
        }
        // The colonist is in WORK mode - now ask the JOB ai what it is actually doing.
        String aiState = aiStateName(data);
        if (aiState == null) {
            // Could not read the worker AI: fall back to the material queue.
            String materials = materialsWait(data, hut);
            if (materials != null) {
                p.waiting = true;
                return materials;
            }
        } else if (!LAYING_BLOCKS.contains(aiState)) {
            p.waiting = true;
            return describeAiState(aiState, data, hut);
        }
        try {
            if (data.isIdleAtJob()) {
                p.waiting = true;
                return "short of a tool - MineColonies has them flagged as stuck for equipment";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The builder's own job-AI state, or null when it cannot be read. */
    private static String aiStateName(ICitizenData data) {
        try {
            Object ai = data.getJob() == null ? null : data.getJob().getWorkerAI();
            if (!(ai instanceof AbstractAISkeleton<?> skeleton)) {
                return null;
            }
            IAIState st = skeleton.getState();
            return st == null ? null : st.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Put a job-AI state into words a colonist would actually say. */
    private static String describeAiState(String state, ICitizenData data, AbstractBuildingStructureBuilder hut) {
        switch (state) {
            case "NEEDS_ITEM": {
                String materials = materialsWait(data, hut);
                return materials != null ? materials : "waiting on a delivery before they can carry on";
            }
            case "GATHERING_REQUIRED_MATERIALS":
            case "GET_MATERIALS":
                return "fetching the next load of materials from their hut";
            case "INVENTORY_FULL":
            case "DUMPING":
                return "unloading a full inventory at their hut";
            case "PICK_UP":
            case "PICKUP":
                return "picking leftovers up off the site";
            case "PAUSED":
                return "paused - their hut is switched to pause";
            case "IDLE":
            case "INIT":
            case "PREPARING":
            case "START_WORKING":
            case "DECIDE":
                return "between steps, not on the wall yet";
            default:
                return "busy with " + state.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /** Open deliveries the builder is waiting on, phrased for speech. */
    private static String materialsWait(ICitizenData data, AbstractBuildingStructureBuilder hut) {
        try {
            Collection<IRequest<?>> open = hut.getOpenRequests(data.getId());
            if (open == null || open.isEmpty()) {
                return null;
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (IRequest<?> r : open) {
                try {
                    RequestState st = r.getState();
                    if (st == RequestState.COMPLETED || st == RequestState.RECEIVED
                            || st == RequestState.CANCELLED || st == RequestState.OVERRULED
                            || st == RequestState.FAILED) {
                        continue;
                    }
                    String name = r.getShortDisplayString().getString().trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (names.isEmpty()) {
                return null;
            }
            List<String> shown = new ArrayList<>(names);
            int extra = Math.max(0, shown.size() - 3);
            if (shown.size() > 3) {
                shown = shown.subList(0, 3);
            }
            return "waiting for materials - still on order: " + String.join(", ", shown)
                    + (extra > 0 ? " and " + extra + " more" : "");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String friendly(String stage) {
        if (stage == null) return "working";
        return switch (stage) {
            case "CLEAR", "REMOVE" -> "clearing the site";
            case "CLEAR_WATER", "REMOVE_WATER" -> "draining water";
            case "BUILD_SOLID", "WEAK_SOLID" -> "putting up the structure";
            case "CLEAR_NON_SOLIDS" -> "tidying loose blocks";
            case "DECORATE" -> "decorating - the fiddly last bits";
            case "SPAWN" -> "placing the last decorations and fittings";
            default -> "working";
        };
    }

    /** Spoken-ready answer for build_status. */
    public static String report(IColony colony) {
        List<String> lines = new ArrayList<>();
        boolean anyStuck = false;
        try {
            for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                Progress now = read(data, false);
                if (now == null) continue;
                Progress old = SEEN.get(data.getId());
                StringBuilder sb = new StringBuilder(data.getName() + " is building " + now.order
                        + " (" + friendly(now.stage) + ")");
                if (now.standing != null) {
                    sb.append(", standing at ").append(now.standing.getX()).append(", ")
                            .append(now.standing.getY()).append(", ").append(now.standing.getZ());
                }
                if (now.blocked != null) {
                    sb.append(" - not working on it at this moment: ").append(now.blocked);
                    long waiting = old == null || old.waitSinceMs == 0
                            ? 0 : System.currentTimeMillis() - old.waitSinceMs;
                    if (waiting > 60_000L) {
                        sb.append(" (").append(waiting / 60_000L).append(" minutes now)");
                    }
                    sb.append(". That is normal, not a fault");
                } else {
                    long stuckFor = old == null ? 0 : System.currentTimeMillis() - old.sinceMs;
                    if (stuckFor > STUCK_MS) {
                        anyStuck = true;
                        sb.append(" - and has NOT moved on for ").append(stuckFor / 60_000L)
                                .append(" minutes with nothing stopping them, which means the next spot cannot be "
                                        + "reached");
                    }
                }
                lines.add(sb.toString());
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Build] report failed", t);
        }
        if (lines.isEmpty()) {
            return "Nobody is building anything right now - no builder has an active work order.";
        }
        String tail = anyStuck
                ? " A build that sits still with the builder awake, on site and fully supplied is almost never short "
                + "of materials: the builder cannot WALK to the next spot. Suggest clearing the way, or pausing and "
                + "restarting them at their hut."
                : " Nothing is stalled - report the reasons as the ordinary rhythm of the day, not as a problem.";
        return String.join(". ", lines) + "." + tail;
    }

    /**
     * The builder's own truth: what they are building RIGHT NOW. Prudence told
     * Lovkar she was stuck on a residence while standing on a kitchen.
     */
    public static String promptLine(String citizenName) {
        try {
            ICitizenData data = find(citizenName);
            if (data == null) return "";
            Progress now = read(data, false);
            if (now == null) {
                if (data.getJob() instanceof AbstractJobStructure<?, ?>) {
                    return "\n\nYOUR BUILD: you have NO build order right now - you are between jobs. Never claim to "
                            + "be working on a building.";
                }
                return "";
            }
            Progress old = SEEN.get(data.getId());
            StringBuilder sb = new StringBuilder("\n\nYOUR BUILD: right now you are building ")
                    .append(now.order).append(", and you are ").append(friendly(now.stage))
                    .append(". This is THE building you are on - never name a different one, however familiar the "
                            + "other names feel.");
            if (now.blocked != null) {
                long waiting = old == null || old.waitSinceMs == 0
                        ? 0 : System.currentTimeMillis() - old.waitSinceMs;
                sb.append(" You are not laying blocks at this moment because you are ").append(now.blocked)
                        .append(waiting > 60_000L ? " (" + (waiting / 60_000L) + " minutes now)" : "")
                        .append(". You are NOT stuck and nothing is broken - if the player asks how the build is "
                                + "going, say plainly why you are not on it, and never call yourself stuck.");
            } else {
                long stuckFor = old == null ? 0 : System.currentTimeMillis() - old.sinceMs;
                if (stuckFor > STUCK_MS) {
                    sb.append(" You have been STUCK for ").append(stuckFor / 60_000L)
                            .append(" minutes: you are awake, on site and have everything you need, but you cannot "
                                    + "get to the next spot you must work on. Say so plainly if the player asks how "
                                    + "it is going - and that someone clearing the way, or your hut being paused and "
                                    + "restarted, would free you.");
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static ICitizenData find(String citizenName) {
        if (citizenName == null || citizenName.isBlank()) return null;
        try {
            Integer cached = COLONY_OF_NAME.get(citizenName);
            if (cached != null) {
                for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                    if (colony.getID() != cached) continue;
                    for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                        if (citizenName.equals(cd.getName())) return cd;
                    }
                }
            }
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                    if (citizenName.equals(cd.getName())) {
                        COLONY_OF_NAME.put(citizenName, colony.getID());
                        return cd;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void broadcast(MinecraftServer server, String msg) {
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal(msg));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        SEEN.clear();
        COLONY_OF_NAME.clear();
    }
}
