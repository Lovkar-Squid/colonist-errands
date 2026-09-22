package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bug report from nikochilv0 (13 Sep 2026): he told colonists to chop wood and to go mining, and
 * they stood exactly where they were - "the AI reacts as if they've tried to do it and are
 * currently doing it, nothing actually happens".
 *
 * <p>His log says the mod did nothing wrong: in forty minutes it ran sixty-eight tool calls and
 * every one of them succeeded. The model simply never reached for a work tool, because there is no
 * such thing. A MineColonies colonist does not chop wood on request - a <b>Forester</b> chops wood,
 * from a Forester's Hut, and nothing else does. What the model had was no instruction to say so, so
 * it did the most agreeable thing available and played along.</p>
 *
 * <p>That is the bug, and it is a bug in the <i>prompt</i>. A colonist who cannot do a thing must
 * say he cannot do it. So this block tells every citizen, in their own guidance, that work is a job
 * and not an errand, what their own job actually is, and what the honest alternative is: take the
 * job, if the colony has the hut - and if it has not, that somebody has to build it first. Checking
 * the colony for the hut is the difference between "I can't" and "I can't, but build a Forester's
 * Hut and I will".</p>
 */
public final class WorkTruth {

    private WorkTruth() {
    }

    /**
     * The professions people ask for by the WORK rather than by the hut, and the hut that does it -
     * by its <b>registry path</b>, because that is what {@link #standing} compares against.
     *
     * <p>This is the line that has to be right and was not. Chopping wood is done at the Forester's
     * Hut, which MineColonies registers as {@code lumberjack}; written here as "forester" it
     * matched no building in any colony, so every citizen in the game was told the colony had no
     * workplace for chopping wood and that one would have to be built - with a Forester's Hut
     * standing in front of him. That is the same false confidence the bug report was about, only
     * pointing the other way, and it shipped inside the fix for it. {@code
     * tools/check_buildings.py} now holds every value here against the installed MineColonies jar
     * so it cannot come back.</p>
     */
    private static final Map<String, String> BY_THE_WORK = new LinkedHashMap<>();

    static {
        BY_THE_WORK.put("chopping wood", "lumberjack");
        BY_THE_WORK.put("mining", "miner");
        BY_THE_WORK.put("farming", "farmer");
        BY_THE_WORK.put("fishing", "fisherman");
        BY_THE_WORK.put("herding animals", "cowboy");
        BY_THE_WORK.put("building", "builder");
    }

    private static final Map<String, Integer> COLONY_OF_NAME = new ConcurrentHashMap<>();

    public static String promptLine(final String citizenName) {
        try {
            final ICitizenData data = find(citizenName);
            if (data == null) {
                return "";
            }
            final IColony colony = data.getColony();
            if (colony == null) {
                return "";
            }

            final StringBuilder sb = new StringBuilder("\n\nWORK TRUTH: work in this colony is a JOB, never an "
                    + "errand. You do one kind of work - the work of the hut you are employed at - and you cannot "
                    + "start another kind because somebody asks. A Forester chops wood and only a Forester; a Miner "
                    + "mines; a Farmer works their own fields. ");

            final String job = jobName(data);
            if (job == null) {
                sb.append("You have NO job at all right now, so you do no work whatsoever. ");
            } else {
                sb.append("Your job is: ").append(job).append(". Anything that is not that job is not yours to do. ");
            }

            sb.append("So if the player tells you to chop wood, go mining, plough a field, fish, hunt or build, and "
                    + "it is not your job, NEVER answer that you are on your way and NEVER pretend to have started - "
                    + "that is the one thing you must not do. Say plainly that you cannot, say why in one sentence, "
                    + "and offer the thing that would actually work: that you TAKE that job, with the ")
                    .append(me.lovkar.errands.tc.ToolNames.providerName("take_job"))
                    .append(" tool. ");

            final String have = huts(colony);
            if (!have.isEmpty()) {
                sb.append("The colony HAS these workplaces standing, so for these the offer is real and you should "
                        + "make it: ").append(have).append(". ");
            }
            final String missing = missingHuts(colony);
            if (!missing.isEmpty()) {
                sb.append("The colony has NO ").append(missing).append(" - so that work cannot be done by anyone "
                        + "here yet, whoever asks. Say so: it has to be built first, and the Builder builds it.");
            }
            return sb.toString();
        } catch (final Throwable t) {
            return "";
        }
    }

    /** The job a citizen actually holds, or null when they are unemployed. */
    private static String jobName(final ICitizenData data) {
        try {
            if (data.getJob() == null) {
                return null;
            }
            final IBuilding work = data.getWorkBuilding();
            final String where = work == null ? null : ErrandBuildings.bestName(work);
            final String what = data.getJob().getJobRegistryEntry() == null
                    ? null : data.getJob().getJobRegistryEntry().getKey().getPath();
            if (what == null && where == null) {
                return null;
            }
            if (where == null) {
                return what;
            }
            return what == null ? where : what + " at the " + where;
        } catch (final Throwable t) {
            return null;
        }
    }

    /** "chopping wood (the Forester's Hut)" for every kind of work this colony can actually do. */
    private static String huts(final IColony colony) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> e : BY_THE_WORK.entrySet()) {
            final IBuilding b = standing(colony, e.getValue());
            if (b == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append(" (the ").append(ErrandBuildings.bestName(b)).append(')');
        }
        return sb.toString();
    }

    /** ...and the ones it cannot, named by the work rather than by the hut. */
    private static String missingHuts(final IColony colony) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> e : BY_THE_WORK.entrySet()) {
            if (standing(colony, e.getValue()) != null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", no ");
            }
            sb.append("workplace for ").append(e.getKey());
        }
        return sb.toString();
    }

    /** A built workplace of that type, or null. A hut still on the drawing board is not a workplace. */
    private static IBuilding standing(final IColony colony, final String type) {
        try {
            final String want = ErrandBuildings.normalizeType(type);
            for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (b.getBuildingLevel() <= 0) {
                    continue;
                }
                final var id = b.getBuildingType() == null ? null : b.getBuildingType().getRegistryName();
                if (id != null && (id.getPath().equals(type) || id.getPath().equals(want))) {
                    return b;
                }
            }
        } catch (final Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ the same finder HomeCheck uses

    private static ICitizenData find(final String citizenName) {
        if (citizenName == null || citizenName.isBlank()) {
            return null;
        }
        try {
            final Integer cached = COLONY_OF_NAME.get(citizenName);
            if (cached != null) {
                final IColony colony = byId(cached);
                if (colony != null) {
                    final ICitizenData d = inColony(colony, citizenName);
                    if (d != null) {
                        return d;
                    }
                }
            }
            for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
                final ICitizenData d = inColony(colony, citizenName);
                if (d != null) {
                    COLONY_OF_NAME.put(citizenName, colony.getID());
                    return d;
                }
            }
        } catch (final Throwable ignored) {
        }
        return null;
    }

    private static ICitizenData inColony(final IColony colony, final String name) {
        try {
            for (final ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (name.equals(cd.getName())) {
                    return cd;
                }
            }
        } catch (final Throwable ignored) {
        }
        return null;
    }

    private static IColony byId(final int id) {
        try {
            for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getID() == id) {
                    return colony;
                }
            }
        } catch (final Throwable ignored) {
        }
        return null;
    }

    public static void clearAll() {
        COLONY_OF_NAME.clear();
    }
}
