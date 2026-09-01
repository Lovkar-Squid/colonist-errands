package me.lovkar.errands;

import com.ldtteam.domumornamentum.block.decorative.PanelBlock;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.AbstractAssignedCitizenModule;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.modules.BedHandlingModule;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar: "every night one colonist in my level 3 tavern gets stuck and cannot
 * get into bed until I unassign their home and assign it back."
 * <p>
 * That is not random. In {@code EntityAISleep.findBedAndTryToSleep} MineColonies
 * does NOT look for a free bed - it hands each resident the bed at THEIR OWN
 * INDEX in the assigned-citizen list:
 * <pre>
 *   index   = assignedCitizens.indexOf(me)
 *   bedList = building.getModule(BED).getRegisteredBlocks()
 *   if (index &lt; bedList.size()) { ...check that one bed... }
 *   usedBed = homePos;            // otherwise: the hut block, which is not a bed
 * </pre>
 * So if there are more residents than registered beds, or if the bed sitting at
 * that one index is unusable - registered on the foot half, or with a solid block
 * right above the pillow - that resident walks to the hut block and stands there
 * all night. Always the same colonist, every night. Un-assigning and re-assigning
 * the home works because it changes their INDEX in the list, which hands them a
 * different bed.
 * <p>
 * We cannot fix MineColonies' choice of bed from here, but we can name the exact
 * block to break or the bed to add, and let the colonist say what is wrong.
 */
public final class BedCheck {

    private BedCheck() {
    }

    /** One resident who will not get into a bed tonight, and why. */
    public static final class Problem {
        public final String citizen;
        public final String building;
        public final BlockPos bed;
        public final String reason;
        public final String fix;

        Problem(String citizen, String building, BlockPos bed, String reason, String fix) {
            this.citizen = citizen;
            this.building = building;
            this.bed = bed;
            this.reason = reason;
            this.fix = fix;
        }
    }

    /** citizen name -> their current problem, refreshed by the tick. */
    private static final Map<String, Problem> BY_CITIZEN = new ConcurrentHashMap<>();
    /** citizen name -> the Minecraft day we last complained about them. */
    private static final Map<String, Long> WARNED_DAY = new ConcurrentHashMap<>();
    private static long lastScanDay = -1;

    /**
     * Actually REPAIR the bed list instead of only naming the broken entry.
     * <p>
     * Lovkar fixed the bed at 549, 79, 3612 by hand and the same coordinate came
     * back the next night, because MineColonies never cleans this up. In
     * {@code findBedAndTryToSleep} the only entry the game ever removes is one
     * whose block is no longer a bed at all:
     * <pre>
     *   if (!state.is(BlockTags.BEDS)) { hut.getModule(BED).removeBed(pos); return; }
     *   if (state.getValue(PART) == HEAD &amp;&amp; aboveIsClear) { usedBed = pos; return; }
     *   ...falls through to usedBed = homePos      // the hut block, not a bed
     * </pre>
     * A FOOT-half entry passes the first test (the foot IS a bed block) and fails
     * the second, so it is never removed and never usable - a permanent dead slot.
     * Beds that come from a schematic land that way; a hand-placed bed does not,
     * because {@code BedHandlingModule.onBlockPlacedInBuilding} normalises FOOT to
     * HEAD. So we run that same normalisation over the stored list ourselves.
     */
    private static int repairBeds(IColony colony) {
        int repaired = 0;
        try {
            Level level = colony.getWorld();
            if (level == null) {
                return 0;
            }
            java.util.Set<BlockPos> building = underConstruction(colony);
            for (IBuilding hut : colony.getServerBuildingManager().getBuildings().values()) {
                try {
                    if (!hut.hasModule(BuildingModules.BED) || building.contains(hut.getPosition())) {
                        continue;
                    }
                    BedHandlingModule beds = hut.getModule(BuildingModules.BED);
                    List<BlockPos> registered = new ArrayList<>(beds.getRegisteredBlocks());
                    java.util.Set<BlockPos> seen = new java.util.HashSet<>();
                    for (BlockPos pos : registered) {
                        BlockState state = level.getBlockState(pos);
                        if (!state.is(BlockTags.BEDS)) {
                            beds.removeBed(pos);
                            repaired++;
                            ColonistErrands.LOGGER.info("[Beds] Dropped a registered bed that is no longer there: {}", pos);
                            continue;
                        }
                        if (!seen.add(pos)) {
                            beds.removeBed(pos); // duplicate entry: one copy is enough
                            repaired++;
                            ColonistErrands.LOGGER.info("[Beds] Dropped a duplicate bed entry: {}", pos);
                            continue;
                        }
                        if (state.getValue(BedBlock.PART).equals(BedPart.HEAD)) {
                            continue;
                        }
                        // Registered on the foot half - re-register it the way a
                        // hand-placed bed would be, then drop the foot entry.
                        beds.removeBed(pos);
                        BlockPos head = pos.relative(state.getValue(BedBlock.FACING));
                        if (!beds.getRegisteredBlocks().contains(head)) {
                            beds.onBlockPlacedInBuilding(state, pos, level);
                        }
                        repaired++;
                        ColonistErrands.LOGGER.info("[Beds] Re-registered a bed from its foot {} onto its pillow {} in {}",
                                pos, head, buildingName(hut));
                    }
                    repaired += registerMissingBeds(hut, beds, level);
                    if (repaired > 0) {
                        hut.markDirty();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Beds] repair failed", t);
        }
        return repaired;
    }

    /**
     * Lovkar: "but somebody DOES lie down in the bed at those coordinates."
     * <p>
     * Quite right - the bed is real and in use. What is broken is the ENTRY that
     * points at its foot end, which is a slot nobody can ever sleep in. Dropping
     * that entry makes the building one slot shorter, which is honest but leaves
     * whoever was on it with nothing. So after the cleanup we look for beds that
     * are physically inside the building but were never registered at all - which
     * is the other half of the same schematic bug - and file them properly.
     */
    private static int registerMissingBeds(IBuilding hut, BedHandlingModule beds, Level level) {
        int added = 0;
        try {
            AbstractAssignedCitizenModule living = hut.getFirstModuleOccurance(AbstractAssignedCitizenModule.class);
            if (living == null) {
                return 0;
            }
            int sleepers = 0;
            for (ICitizenData cd : living.getAssignedCitizen()) {
                if (cd != null && !neverSleeps(cd)) {
                    sleepers++;
                }
            }
            List<BlockPos> registered = beds.getRegisteredBlocks();
            if (sleepers <= registered.size()) {
                return 0; // no shortage - do not go rummaging through the building
            }
            net.minecraft.util.Tuple<BlockPos, BlockPos> corners = hut.getCorners();
            if (corners == null) {
                return 0;
            }
            BlockPos a = corners.getA();
            BlockPos b = corners.getB();
            int x0 = Math.min(a.getX(), b.getX());
            int x1 = Math.max(a.getX(), b.getX());
            int y0 = Math.min(a.getY(), b.getY());
            int y1 = Math.max(a.getY(), b.getY());
            int z0 = Math.min(a.getZ(), b.getZ());
            int z1 = Math.max(a.getZ(), b.getZ());
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 200_000L) {
                return 0; // absurd bounds - not worth the scan
            }
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = x0; x <= x1 && added < sleepers - registered.size(); x++) {
                for (int z = z0; z <= z1 && added < sleepers - registered.size(); z++) {
                    for (int y = y0; y <= y1 && added < sleepers - registered.size(); y++) {
                        cursor.set(x, y, z);
                        BlockState st = level.getBlockState(cursor);
                        if (!st.is(BlockTags.BEDS) || !st.getValue(BedBlock.PART).equals(BedPart.HEAD)) {
                            continue;
                        }
                        BlockPos head = cursor.immutable();
                        if (beds.getRegisteredBlocks().contains(head) || bedTrouble(level, head) != null) {
                            continue;
                        }
                        beds.onBlockPlacedInBuilding(st, head, level);
                        added++;
                        ColonistErrands.LOGGER.info("[Beds] Registered a bed the schematic never filed: {} in {}",
                                head, buildingName(hut));
                    }
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Beds] could not look for unregistered beds", t);
        }
        return added;
    }

    /**
     * Lovkar: "that Residence is being built right now, and while they build, the
     * builder moves the beds."
     * <p>
     * Quite so - and it makes every bed check meaningless for that building. During
     * construction the builder tears blocks out and puts them back, so beds vanish
     * from the world for minutes at a time, entries get dropped as "no longer
     * there", and the residents look bedless when in fact the house is simply half
     * built. So any building with an open work order is left entirely alone: not
     * scanned, not repaired, not reported.
     */
    private static java.util.Set<BlockPos> underConstruction(IColony colony) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        try {
            for (com.minecolonies.api.colony.workorders.IWorkOrder wo
                    : colony.getWorkManager().getWorkOrders().values()) {
                try {
                    BlockPos at = wo.getLocation();
                    if (at != null) {
                        out.add(at);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Hospital beds are a SEPARATE list with a STRICTER rule, and Lovkar's guards
     * were the ones paying for it: "in the hospital he does not stay to be cured,
     * he does not lie down."
     * <p>
     * {@code EntityAISickTask.findEmptyBed} walks {@code BuildingHospital.getBedList()}
     * and skips any bed that is not a bed block, is occupied, is not the HEAD half,
     * or does not have {@code world.isEmptyBlock(pos.above())} - literally AIR, where
     * the ordinary sleep code is happy with a trapdoor or a panel. If nothing passes
     * it returns WAIT_FOR_CURE: the patient stands in the hospital and never lies
     * down. And guards reach this code far more often than anyone else, because
     * {@code CitizenAI} sends a guard to SICK as soon as they are ill, while everyone
     * else only goes once {@code sleepsAtHospital()} is already true.
     * <p>
     * The hospital keeps its own {@code bedMap} with no public removal, but
     * {@code registerBlockPosition} normalises FOOT to HEAD and skips duplicates, so
     * we can add the missing pillow entry. A stale foot entry left behind is
     * harmless - the search simply skips it and takes the good one.
     */
    private static int repairHospitalBeds(IColony colony) {
        int fixed = 0;
        try {
            Level level = colony.getWorld();
            if (level == null) {
                return 0;
            }
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (!(b instanceof com.minecolonies.core.colony.buildings.workerbuildings.BuildingHospital hosp)) {
                    continue;
                }
                if (underConstruction(colony).contains(b.getPosition())) {
                    continue; // the builder is in there moving blocks about
                }
                try {
                    List<BlockPos> beds = new ArrayList<>(hosp.getBedList());
                    int usable = 0;
                    List<String> blocked = new ArrayList<>();
                    for (BlockPos pos : beds) {
                        BlockState st = level.getBlockState(pos);
                        if (!st.is(BlockTags.BEDS)) {
                            continue;
                        }
                        if (!st.getValue(BedBlock.PART).equals(BedPart.HEAD)) {
                            hosp.registerBlockPosition(st, pos, level);
                            fixed++;
                            ColonistErrands.LOGGER.info("[Beds] Hospital bed at {} was filed on its foot - "
                                    + "registered the pillow too", pos);
                            continue;
                        }
                        if (!level.isEmptyBlock(pos.above())) {
                            blocked.add(pos.getX() + ", " + (pos.getY() + 1) + ", " + pos.getZ() + " ("
                                    + level.getBlockState(pos.above()).getBlock().getName().getString() + ")");
                            continue;
                        }
                        usable++;
                    }
                    if (usable == 0 && !blocked.isEmpty()) {
                        ColonistErrands.LOGGER.info("[Beds] Hospital has NO usable bed - blocked above: {}", blocked);
                        HOSPITAL_TROUBLE.put(b.getPosition(), "the hospital has no bed anyone can lie in. A hospital "
                                + "bed needs BARE AIR directly above the pillow - stricter than an ordinary bed, "
                                + "where a trapdoor is fine. Clear the block above: " + String.join("; ", blocked));
                    } else {
                        HOSPITAL_TROUBLE.remove(b.getPosition());
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Beds] hospital bed check failed", t);
        }
        return fixed;
    }

    /** Hospital position -> what is wrong with its beds, for the chat warning. */
    private static final Map<BlockPos, String> HOSPITAL_TROUBLE = new ConcurrentHashMap<>();

    /**
     * Lovkar's second case: builder Prudence stood in the tavern all night, and
     * she HAS a home elsewhere - so neither the bed index nor homelessness explains
     * her. The answer is one state earlier, in {@code EntityAISleep.walkHome}:
     * <pre>
     *   homeBuilding == null
     *       ? homePos.distSqr(myPos) &lt;= 16          // homeless: near the tavern is enough
     *       : homeBuilding.isInBuilding(myPos)      // with a home: you must be INSIDE it
     *     -&gt; FIND_BED
     *   else { goHome(); stay in WALKING_HOME; }
     * </pre>
     * The bed code is only ever reached from FIND_BED. A colonist who cannot
     * actually walk INTO their home building never leaves WALKING_HOME - so
     * {@code goHome()} is retried forever, silently, and they stand wherever the
     * failed path left them. That is the same silent-navigation-failure shape as
     * the builder stall, and it is invisible in game: no message, no icon.
     */
    private static final Map<String, Long> AWAY_SINCE = new ConcurrentHashMap<>();
    /** Standing outside your own home this long, while trying to sleep, is a fault. */
    private static final long AWAY_MS = 4 * 60_000L;

    /** Colonists who are trying to go to bed but cannot get into their own home. */
    private static List<Problem> scanCannotGetHome(IColony colony) {
        List<Problem> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                try {
                    if (data == null || neverSleeps(data)) {
                        continue;
                    }
                    AbstractEntityCitizen entity = data.getEntity().orElse(null);
                    IBuilding home = data.getHomeBuilding();
                    if (entity == null || !entity.isAlive() || home == null) {
                        AWAY_SINCE.remove(data.getName());
                        continue;
                    }
                    // Only while the colonist is actually TRYING to sleep.
                    IState st = entity.getEntityStateController().getState();
                    if (st != CitizenAIState.SLEEP || data.isAsleep() || entity.isSleeping()) {
                        AWAY_SINCE.remove(data.getName());
                        continue;
                    }
                    if (home.isInBuilding(entity.blockPosition())
                            || underConstruction(colony).contains(home.getPosition())) {
                        AWAY_SINCE.remove(data.getName());
                        continue;
                    }
                    Long since = AWAY_SINCE.putIfAbsent(data.getName(), now);
                    if (since == null || now - since < AWAY_MS) {
                        continue;
                    }
                    BlockPos at = entity.blockPosition();
                    BlockPos hp = home.getPosition();
                    int dist = (int) Math.sqrt(at.distSqr(hp));
                    out.add(new Problem(data.getName(), buildingName(home), at,
                            "they are trying to go to bed but cannot get INTO their own home. They have been "
                                    + "standing at " + at.getX() + ", " + at.getY() + ", " + at.getZ() + " for "
                                    + ((now - since) / 60_000L) + " minutes, " + dist + " blocks from the home hut at "
                                    + hp.getX() + ", " + hp.getY() + ", " + hp.getZ()
                                    + " - the sleep AI only looks for a bed once they are inside the building, so "
                                    + "they never even get that far",
                            "Check the way in: a blocked door, a missing path, a fence or a drop they cannot climb. "
                                    + "Unassigning and re-assigning the home resets the sleep state and often frees "
                                    + "them for that night."));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        try {
            ServerLevel overworld = server.overworld();
            long day = overworld.getDayTime() / 24000L;
            long timeOfDay = overworld.getDayTime() % 24000L;
            boolean night = timeOfDay >= 11000L;
            List<Problem> found = new ArrayList<>();
            // The bed-and-index scan bites once, in the evening.
            if (night && timeOfDay <= 13500L && lastScanDay != day) {
                lastScanDay = day;
                BY_CITIZEN.clear();
                for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                    int fixed = repairBeds(colony) + repairHospitalBeds(colony);
                    for (String trouble : HOSPITAL_TROUBLE.values()) {
                        broadcast(server, "[Beds] " + trouble + " Until then the sick stand about in there instead "
                                + "of being treated - guards most of all, because the game sends them to the "
                                + "hospital the moment they fall ill.");
                    }
                    if (fixed > 0) {
                        broadcast(server, "[Beds] Fixed " + fixed + " bed registration"
                                + (fixed == 1 ? "" : "s") + " - beds filed on the foot end (a slot nobody can "
                                + "ever sleep in, which the game never cleans up), entries pointing at nothing, "
                                + "and real beds the schematic never filed at all. Whoever was stuck should get "
                                + "into bed tonight.");
                    }
                    found.addAll(scan(colony));
                }
            }
            // "Cannot get home" can only be seen by watching them fail for minutes,
            // so it runs all night, not on one pass.
            if (night) {
                for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                    found.addAll(scanCannotGetHome(colony));
                }
            } else {
                AWAY_SINCE.clear();
        HOSPITAL_TROUBLE.clear();
            }
            if (found.isEmpty()) {
                return;
            }
            for (Problem p : found) {
                BY_CITIZEN.put(p.citizen, p);
            }
            for (Problem p : found) {
                Long warned = WARNED_DAY.get(p.citizen);
                if (warned != null && warned == day) {
                    continue;
                }
                WARNED_DAY.put(p.citizen, day);
                broadcast(server, "[Beds] " + p.citizen + " will not get into a bed tonight in the "
                        + p.building + " - " + p.reason + ". " + p.fix
                        + " MineColonies gives each resident the bed at their own place in the resident list, so "
                        + "it is always the same person until the bed itself is fixed.");
                ColonistErrands.LOGGER.info("[Beds] {} has no usable bed in {} - {}",
                        p.citizen, p.building, p.reason);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Every resident of this colony who cannot reach a usable bed.
     * <p>
     * We walk CITIZENS, not buildings, and ask each one for
     * {@code getHomeBuilding()} - exactly what {@code EntityAISleep} does. Walking
     * buildings was wrong twice over: a barracks or a guard tower also carries an
     * assigned-citizen module, so {@code getFirstModuleOccurance} handed back the
     * GUARD WORK roster and we reported guards as bedless; and guards never sleep
     * at all - {@code CitizenAI.calculateNextState} returns WORK for every
     * {@code AbstractJobGuard} before the sleep branch is ever reached.
     */
    public static List<Problem> scan(IColony colony) {
        List<Problem> out = new ArrayList<>();
        try {
            Level level = colony.getWorld();
            if (level == null) {
                return out;
            }
            java.util.Set<BlockPos> building = underConstruction(colony);
            for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                try {
                    if (data == null || neverSleeps(data)) {
                        continue;
                    }
                    IBuilding home = data.getHomeBuilding();
                    if (home != null && building.contains(home.getPosition())) {
                        continue; // half built - the beds are in the builder's hands
                    }
                    if (home == null) {
                        // MineColonies sends the homeless to the TAVERN: getHomePosition()
                        // falls back to the tavern, then the town hall. So they walk there
                        // every evening and just stand - findBedAndTryToSleep does nothing
                        // at all without a home building, so they never get into a bed.
                        out.add(new Problem(data.getName(), homelessDestination(colony), data.getHomePosition(),
                                "they have NO home at all - the game sends the homeless to stand there at night, "
                                        + "and without a home building the sleep code never even looks for a bed",
                                "Assign them a house (or a tavern room) in its hut window; they will keep standing "
                                        + "about every night until you do."));
                        continue;
                    }
                    if (!home.hasModule(BuildingModules.BED)) {
                        continue;
                    }
                    AbstractAssignedCitizenModule living =
                            home.getFirstModuleOccurance(AbstractAssignedCitizenModule.class);
                    if (living == null) {
                        continue;
                    }
                    int index = living.getAssignedCitizen().indexOf(data);
                    if (index < 0) {
                        continue;
                    }
                    List<BlockPos> beds = home.getModule(BuildingModules.BED).getRegisteredBlocks();
                    String name = buildingName(home);
                    int sleepers = 0;
                    for (ICitizenData other : living.getAssignedCitizen()) {
                        if (other != null && !neverSleeps(other)) {
                            sleepers++;
                        }
                    }
                    if (index >= beds.size()) {
                        int missing = Math.max(1, sleepers - beds.size());
                        out.add(new Problem(data.getName(), name, home.getPosition(),
                                "there is no bed for them at all (" + sleepers + " who need one, only "
                                        + beds.size() + " bed" + (beds.size() == 1 ? "" : "s") + " registered)",
                                "Place " + missing + " more bed" + (missing == 1 ? "" : "s")
                                        + " inside the building, or move a resident out."));
                        continue;
                    }
                    String trouble = bedTrouble(level, beds.get(index));
                    if (trouble != null) {
                        BlockPos bed = beds.get(index);
                        out.add(new Problem(data.getName(), name, bed,
                                "the one bed the game assigns them, at " + bed.getX() + ", " + bed.getY()
                                        + ", " + bed.getZ() + ", " + trouble,
                                fixFor(trouble, bed)));
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Beds] scan failed", t);
        }
        return out;
    }

    /**
     * Guards never sleep. In {@code CitizenAI.calculateNextState} an
     * {@code AbstractJobGuard} short-circuits to EATING, SICK or WORK and never
     * reaches the sleep branch at all, so a barracks or a guard tower having fewer
     * beds than guards is not a problem - it is simply how the game works.
     */
    private static boolean neverSleeps(ICitizenData data) {
        try {
            return data.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The same three tests MineColonies itself runs before it accepts a bed. Any
     * one of them failing sends the resident to the hut block instead.
     */
    private static String bedTrouble(Level level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.BEDS)) {
                return "is not a bed any more - the block is gone";
            }
            if (!state.getValue(BedBlock.PART).equals(BedPart.HEAD)) {
                return "is registered on the FOOT half instead of the pillow";
            }
            BlockState above = level.getBlockState(pos.above());
            boolean ok = above.is(BlockTags.BEDS)
                    || above.getBlock() instanceof PanelBlock
                    || above.getBlock() instanceof TrapDoorBlock
                    || !above.isSolid();
            if (!ok) {
                return "has a solid block sitting right above the pillow ("
                        + above.getBlock().getName().getString() + ")";
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fixFor(String trouble, BlockPos bed) {
        if (trouble.contains("above the pillow")) {
            return "Break the block at " + bed.getX() + ", " + (bed.getY() + 1) + ", " + bed.getZ()
                    + " (a trapdoor or a panel there is fine, a solid block is not).";
        }
        if (trouble.contains("FOOT")) {
            return "Break that bed and place it again by hand - placing it registers the pillow end properly, which "
                    + "a bed that came with the schematic never did. Then ALSO unassign and re-assign that resident, "
                    + "because the re-placed bed goes to the END of the building's bed list and the old order no "
                    + "longer lines up.";
        }
        return "Place a bed there again, or somewhere else inside the building.";
    }

    /**
     * {@code getBuildingDisplayName()} hands back a translation KEY
     * ("com.minecolonies.building.tavern"), not a name - resolve it, and fall back
     * to the last word of the key rather than printing the key at the player.
     */
    private static String buildingName(IBuilding hut) {
        String key = null;
        try {
            key = hut.getBuildingDisplayName();
            String resolved = Component.translatable(key).getString();
            if (resolved != null && !resolved.isBlank() && !resolved.equals(key)) {
                return resolved;
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = key != null ? key : hut.getSchematicName();
            int dot = name.lastIndexOf('.');
            name = dot >= 0 ? name.substring(dot + 1) : name;
            return name.isBlank() ? "building" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        } catch (Throwable ignored) {
            return "building";
        }
    }

    /** Where the game parks the homeless: the tavern, else the town hall. */
    private static String homelessDestination(IColony colony) {
        try {
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (b.getBuildingType() == com.minecolonies.api.colony.buildings.ModBuildings.tavern.get()
                        && b.getBuildingLevel() > 0) {
                    return buildingName(b);
                }
            }
        } catch (Throwable ignored) {
        }
        return "town hall";
    }

    /** So the colonist can say it themselves when asked why they are not asleep. */
    public static String promptLine(String citizenName) {
        try {
            Problem p = BY_CITIZEN.get(citizenName);
            if (p == null) {
                return "";
            }
            if (p.reason.startsWith("they are trying to go to bed")) {
                return "\n\nYOUR BED: you are trying to get home to sleep and you simply CANNOT GET IN. "
                        + p.reason.replace("they are", "You are").replace("their own home", "your own home")
                        .replace("They have", "You have").replace("they never", "you never")
                        + ". You are exhausted and cross about it. If the player asks why you are not asleep, say "
                        + "plainly that you cannot get into your house and where you are stuck.";
            }
            boolean homeless = p.reason.startsWith("they have NO home");
            return "\n\nYOUR BED: " + (homeless
                    ? "you have NO HOME at all. Every evening you end up standing around the " + p.building
                      + " because that is simply where the colony sends people with nowhere to live - you do not "
                      + "live there and you never get to lie down."
                    : "you have no bed you can actually use in the " + p.building + " - " + p.reason + ".")
                    + " You end up standing about at night instead of sleeping, and you are tired and fed up about "
                    + "it. If the player asks why you are not in bed, say exactly this, plainly, and that what would "
                    + "fix it is: " + p.fix;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Spoken-ready summary for the whole colony. */
    public static String report(IColony colony) {
        List<Problem> problems = new ArrayList<>(scan(colony));
        problems.addAll(scanCannotGetHome(colony));
        if (problems.isEmpty()) {
            return "Every resident in this colony has a bed they can actually use - nobody is stuck standing at night.";
        }
        StringBuilder sb = new StringBuilder(problems.size() == 1
                ? "One resident cannot get into a bed: "
                : problems.size() + " residents cannot get into a bed: ");
        for (Problem p : problems) {
            sb.append(p.citizen).append(" in the ").append(p.building).append(" - ").append(p.reason)
                    .append(". ").append(p.fix).append(" ");
        }
        sb.append("This is a MineColonies quirk: each resident is handed the bed at their own position in the "
                + "resident list, never simply a free one, so the same person is stuck every night until the bed "
                + "itself is fixed.");
        return sb.toString();
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
        BY_CITIZEN.clear();
        WARNED_DAY.clear();
        AWAY_SINCE.clear();
        HOSPITAL_TROUBLE.clear();
        lastScanDay = -1;
    }
}
