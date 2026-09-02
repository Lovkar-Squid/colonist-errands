package me.lovkar.errands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Where a defensive line facing a direction should stand. Shared by the automatic
 * raid line (RaidWatcher) and the defend_here voice command (border and raid modes).
 *
 * <p>Lovkar's raid test, beta.41: the line was anchored at the colony's BOUNDING-BOX
 * corner (maxX + 8). His colony sprawls far to the east, the raid spawned at x=592 and
 * the anchor landed at x=704 - a hundred blocks BEHIND the attackers, in the sea, and
 * every one of 13 towers was skipped ("No dry ground near 704,3505"). Two rules now:
 * <ul>
 * <li>The anchor lies on the axis of the attack, just past the OUTERMOST BUILDING in
 *     that direction (projected on the axis), never at a corner the colony does not
 *     reach in that direction - and never beyond three quarters of the way to the raid
 *     spawn when it is known, so the line always stands BETWEEN the raiders and the
 *     colony.</li>
 * <li>When that stretch has no dry ground, the line steps back toward the town hall
 *     eight blocks at a time until half of its posts are dry - guards drown out at
 *     sea, and a line that never forms defends nothing.</li>
 * </ul>
 */
public final class DefenseLine {

    /** Blocks past the outermost building the line is drawn. */
    public static final int MARGIN = 8;
    /** Distance between two neighbouring posts along the line. */
    public static final int SPACING = 4;
    /** How far the line retreats per attempt when the ground is wet. */
    static final int STEP = 8;
    /** Closest to the town hall a line is ever drawn. */
    static final int MIN_DIST = 12;
    /** Never draw the line past this fraction of the way to the raiders' spawn. */
    static final double SPAWN_FRACTION = 0.75;

    private DefenseLine() {
    }

    /**
     * Anchor {x, z} of the line facing {@code out} (unit vector pointing AWAY from the
     * colony, toward the enemy), or null when nothing dry exists between the border and
     * the town hall.
     *
     * @param spawn where the raiders appear, when known (bounds the anchor), else null
     * @param posts how many towers will man the line - decides how many posts are checked
     */
    public static int[] anchor(IColony colony, double[] out, BlockPos spawn, int posts) {
        try {
            BlockPos center = colony.getCenter();
            Level level = colony.getWorld();
            if (center == null || level == null || out == null) return null;
            int cx = center.getX();
            int cz = center.getZ();
            double maxProj = 0;
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                BlockPos p = b.getPosition();
                if (p == null) continue;
                double proj = (p.getX() - cx) * out[0] + (p.getZ() - cz) * out[1];
                if (proj > maxProj) maxProj = proj;
            }
            double dist = maxProj + MARGIN;
            if (spawn != null) {
                double toSpawn = (spawn.getX() - cx) * out[0] + (spawn.getZ() - cz) * out[1];
                if (toSpawn > MIN_DIST) {
                    dist = Math.min(dist, toSpawn * SPAWN_FRACTION);
                }
            }
            dist = Math.max(dist, MIN_DIST);

            double px = -out[1];
            double pz = out[0];
            int want = Math.max(1, Math.min(posts, 5)); // the middle of the line is what matters
            double d = dist;
            while (true) {
                int ax = cx + (int) Math.round(out[0] * d);
                int az = cz + (int) Math.round(out[1] * d);
                int dry = 0;
                for (int i = 0; i < want; i++) {
                    int k = (i + 1) / 2 * ((i % 2 == 0) ? 1 : -1);
                    int x = ax + (int) Math.round(px * SPACING * k);
                    int z = az + (int) Math.round(pz * SPACING * k);
                    if (ErrandManager.safePost(level, x, z) != null) dry++;
                }
                if (dry * 2 >= want) {
                    ColonistErrands.LOGGER.info("[Defense] Line anchored {} blocks from the town hall at {},{} ({}/{} posts dry"
                                    + "{})", (int) Math.round(d), ax, az, dry, want,
                            d < dist - 0.5 ? ", pulled back from " + (int) Math.round(dist) + " - water" : "");
                    return new int[]{ax, az};
                }
                if (d <= MIN_DIST) break;
                d = Math.max(MIN_DIST, d - STEP);
            }
            ColonistErrands.LOGGER.info("[Defense] No dry ground anywhere between the border and the town hall along that axis "
                    + "(tried from {} blocks out)", (int) Math.round(dist));
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("defense line anchor failed", t);
        }
        return null;
    }
}
