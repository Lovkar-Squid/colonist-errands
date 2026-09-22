package me.lovkar.errands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.managers.interfaces.IRegisteredStructureManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Shared list of colony building types + nearest-building lookup. */
public final class ErrandBuildings {

    public static final List<String> BUILDING_TYPES = List.of(
            "cook", "miner", "farmer", "townhall", "barracks", "warehouse",
            "library", "school", "builder", "residence", "deliveryman", "tavern", "hospital",
            "enchanter", "smeltery", "composter", "baker", "fisherman", "lumberjack", "forester", "shepherd",
            "cowboy", "graveyard", "plantation", "beekeeper", "mechanic", "sifter", "crusher",
            "netherworker", "florist", "archery", "combatacademy", "rabbithutch",
            "guardtower", "barrackstower", "gatehouse",
            // profession words the model may use - normalized via JOB_ALIASES:
            "sawmill", "carpenter", "courier", "chef", "kitchen", "smelter", "researcher",
            "university", "student", "teacher", "healer", "undertaker", "planter", "quarry",
            "stablemaster", "stable", "blacksmith", "stonemason", "stonesmeltery", "glassblower",
            "dyer", "fletcher", "concretemixer", "alchemist", "swineherder", "chickenherder");

    /**
     * MineColonies professions are NOT always named like their building
     * (Lovkar: "carpenter dela pri sawmill"). Job words and common synonyms map
     * onto the real building-type registry path here.
     */
    private static final java.util.Map<String, String> JOB_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("carpenter", "sawmill"),
            java.util.Map.entry("courier", "deliveryman"),
            java.util.Map.entry("chef", "kitchen"),
            java.util.Map.entry("smelter", "smeltery"),
            java.util.Map.entry("stonesmelter", "stonesmeltery"),
            java.util.Map.entry("stone smelter", "stonesmeltery"),
            java.util.Map.entry("researcher", "university"),
            java.util.Map.entry("student", "library"),
            java.util.Map.entry("teacher", "school"),
            java.util.Map.entry("pupil", "school"),
            java.util.Map.entry("healer", "hospital"),
            java.util.Map.entry("doctor", "hospital"),
            java.util.Map.entry("medic", "hospital"),
            java.util.Map.entry("undertaker", "graveyard"),
            java.util.Map.entry("planter", "plantation"),
            java.util.Map.entry("stablemaster", "stable"),
            java.util.Map.entry("stable master", "stable"),
            java.util.Map.entry("rabbitherder", "rabbithutch"),
            java.util.Map.entry("rabbit herder", "rabbithutch"),
            java.util.Map.entry("chicken farmer", "chickenherder"),
            java.util.Map.entry("swineherd", "swineherder"),
            java.util.Map.entry("pig farmer", "swineherder"),
            java.util.Map.entry("knight", "guardtower"),
            java.util.Map.entry("ranger", "guardtower"),
            java.util.Map.entry("druid", "guardtower"),
            java.util.Map.entry("guard", "guardtower"),
            java.util.Map.entry("archer", "guardtower"),
            java.util.Map.entry("restaurant", "cook"),
            java.util.Map.entry("storage", "warehouse"),
            java.util.Map.entry("mason", "stonemason"),
            java.util.Map.entry("mine", "miner"),
            java.util.Map.entry("bakery", "baker"),
            java.util.Map.entry("quarrier", "quarry"),
            // The name on the hut is not the name in the registry, and 21 of MineColonies' 52 huts
            // differ. The fuzzy display-name fallback in byCustomName rescued most of them, but it
            // is a contains() on whatever three names a building happens to carry - not something
            // to lean on - and WorkTruth reads the registry path straight, so for it these WERE
            // broken. "Forester's Hut" is the one that matters most: it is registered lumberjack,
            // and it is the hut the whole chop-wood bug report is about.
            // tools/check_buildings.py holds this list to the installed MineColonies jar.
            java.util.Map.entry("forester", "lumberjack"),
            java.util.Map.entry("woodcutter", "lumberjack"),
            java.util.Map.entry("cowhand", "cowboy"),
            java.util.Map.entry("fisher", "fisherman"),
            java.util.Map.entry("farm", "farmer"),
            java.util.Map.entry("town hall", "townhall"),
            java.util.Map.entry("dining hall", "cook"),
            java.util.Map.entry("apiary", "beekeeper"),
            java.util.Map.entry("nether mine", "netherworker"),
            java.util.Map.entry("brick yard", "stonesmeltery"),
            java.util.Map.entry("brickyard", "stonesmeltery"),
            java.util.Map.entry("flower shop", "florist"),
            java.util.Map.entry("mystical site", "mysticalsite"),
            java.util.Map.entry("combat academy", "combatacademy"),
            java.util.Map.entry("guard tower", "guardtower"),
            java.util.Map.entry("barracks tower", "barrackstower"),
            java.util.Map.entry("alchemist tower", "alchemist"),
            java.util.Map.entry("enchanter's tower", "enchanter"),
            java.util.Map.entry("enchanter tower", "enchanter"),
            java.util.Map.entry("concrete mixer", "concretemixer"),
            java.util.Map.entry("chef's kitchen", "kitchen"),
            java.util.Map.entry("plantation field", "plantationfield"),
            java.util.Map.entry("residence", "citizen"),
            java.util.Map.entry("rabbit hutch", "rabbithutch"));

    /**
     * Lowercase, take a trailing "'s hut" / " hut" / " building" off, apply JOB_ALIASES.
     *
     * <p>The suffix is stripped at the END and nowhere else. Stripping " hut" wherever it appeared
     * turned MineColonies' own <b>Rabbit Hutch</b> into "rabbitch", which matches nothing and never
     * could - and a building the player can point at by name must be findable by that name.</p>
     */
    public static String normalizeType(String raw) {
        if (raw == null) return null;
        String q = raw.trim().toLowerCase(java.util.Locale.ROOT);
        q = q.replaceAll("(?:'s)?\\s+hut$", "").replaceAll("\\s+building$", "").trim();
        return JOB_ALIASES.getOrDefault(q, q);
    }

    private ErrandBuildings() {
    }

    /**
     * Nearest building whose name matches (contains, case-insensitive), or null.
     * Checks the player-given custom name, the displayed name and the blueprint/schematic
     * name, so "Gatehouse" matches a building shown as "Gatehouse 2" (level suffix) even
     * if the name comes from a style pack instead of a manual rename.
     */
    public static IBuilding byCustomName(IColony colony, AbstractEntityCitizen near, String name) {
        if (name == null || name.isBlank()) return null;
        IRegisteredStructureManager bm = colony.getServerBuildingManager();
        if (bm == null) return null;
        String q = name.trim().toLowerCase();
        BlockPos from = near.blockPosition();
        IBuilding best = null;
        double bestD = Double.MAX_VALUE;
        for (IBuilding b : bm.getBuildings().values()) {
            if (!nameMatches(b, q)) continue;
            double d = from.distSqr(b.getPosition());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /**
     * Full resolution chain: player's building name first, then the type, then the
     * type string itself as a name (covers the AI putting the player's word into target,
     * e.g. target="gatehouse" which is not a real MineColonies type).
     */
    public static IBuilding resolve(IColony colony, AbstractEntityCitizen near, String type, String name) {
        IBuilding b = byCustomName(colony, near, name);
        if (b == null && type != null && !type.isBlank()) {
            b = nearestOfType(colony, near, type);
            if (b == null) {
                b = byCustomName(colony, near, type);
            }
        }
        return b;
    }

    /** Nicest available name for answers: custom name, else display name, else schematic, else type. */
    public static String bestName(IBuilding b) {
        String[] candidates = new String[3];
        try {
            candidates[0] = b.getCustomName();
        } catch (Throwable ignored) {
        }
        try {
            candidates[1] = b.getBuildingDisplayName();
        } catch (Throwable ignored) {
        }
        try {
            candidates[2] = b.getSchematicName();
        } catch (Throwable ignored) {
        }
        for (String cn : candidates) {
            if (cn != null && !cn.isBlank() && !cn.contains(".")) {
                return cn.trim();
            }
        }
        try {
            return b.getBuildingType().getRegistryName().getPath();
        } catch (Throwable t) {
            return "building";
        }
    }

    private static boolean nameMatches(IBuilding b, String q) {
        String[] candidates = new String[3];
        try {
            candidates[0] = b.getCustomName();
        } catch (Throwable ignored) {
        }
        try {
            candidates[1] = b.getBuildingDisplayName();
        } catch (Throwable ignored) {
        }
        try {
            candidates[2] = b.getSchematicName();
        } catch (Throwable ignored) {
        }
        for (String cn : candidates) {
            if (cn == null || cn.isBlank()) continue;
            String c = cn.trim().toLowerCase();
            if (c.contains(q) || q.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** true when the registry path of b matches the (normalized) wanted type. */
    private static boolean typeMatches(IBuilding b, String wanted) {
        try {
            String path = b.getBuildingType().getRegistryName().getPath();
            return path.equals(wanted) || (wanted != null && wanted.equals("quarry") && path.endsWith("quarry"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** First worker module of the building with a free position, or null. NEVER throws (getModuleMatching does). */
    public static WorkerBuildingModule firstFreeWorkerModule(IBuilding b) {
        try {
            for (WorkerBuildingModule m : b.getModules(WorkerBuildingModule.class)) {
                if (!m.isFull()) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Does this building hire workers at all? (warehouse/residence etc. do not) */
    public static boolean hiresWorkers(IBuilding b) {
        try {
            return !b.getModules(WorkerBuildingModule.class).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * take_job fallback (Lovkar's guard-tower crash): the NEAREST building of a
     * type may be full while another one of the same type still has an open
     * position (several guard towers!). Returns the nearest building of the
     * type with a FREE worker slot, skipping 'except'; also counts how many
     * buildings of the type exist via the out array (for honest replies).
     */
    public static IBuilding nearestOfTypeWithFreeSlot(IColony colony, AbstractEntityCitizen near, String type,
                                                      IBuilding except, int[] outTypeCount) {
        IRegisteredStructureManager bm = colony.getServerBuildingManager();
        if (bm == null) return null;
        String wanted = normalizeType(type);
        if (wanted == null || wanted.isBlank()) return null;
        BlockPos from = near.blockPosition();
        IBuilding best = null;
        double bestD = Double.MAX_VALUE;
        int count = 0;
        for (IBuilding b : bm.getBuildings().values()) {
            if (!typeMatches(b, wanted)) continue;
            count++;
            if (except != null && b.getPosition().equals(except.getPosition())) continue;
            if (firstFreeWorkerModule(b) == null) continue;
            double d = from.distSqr(b.getPosition());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (outTypeCount != null && outTypeCount.length > 0) {
            outTypeCount[0] = count;
        }
        return best;
    }

    /** Nearest building of the given registry-path type, or null. */
    public static IBuilding nearestOfType(IColony colony, AbstractEntityCitizen near, String type) {
        IRegisteredStructureManager bm = colony.getServerBuildingManager();
        if (bm == null) return null;
        String wanted = normalizeType(type);
        BlockPos from = near.blockPosition();
        IBuilding bestBuilding = null;
        double best = Double.MAX_VALUE;
        for (IBuilding b : bm.getBuildings().values()) {
            String path = b.getBuildingType().getRegistryName().getPath();
            // "quarry" matches simplequarry/mediumquarry/largequarry too.
            boolean match = path.equals(wanted)
                    || (wanted != null && wanted.equals("quarry") && path.endsWith("quarry"));
            if (!match) continue;
            double d = from.distSqr(b.getPosition());
            if (d < best) {
                best = d;
                bestBuilding = b;
            }
        }
        return bestBuilding;
    }
}
