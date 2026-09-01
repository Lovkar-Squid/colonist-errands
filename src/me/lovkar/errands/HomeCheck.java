package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's report: colonists talk as if they had no home although he had given
 * them one. Two different MineColonies facts collide here, and neither is a bug:
 * <ul>
 *   <li>a citizen counts as homeless only while {@code getHomeBuilding() == null} -
 *       BUILDING a house is not enough, they have to be assigned to one (a
 *       residence only holds so many, by its level), and</li>
 *   <li>the housing happiness factor is literally {@code homeLevel / 3.0}, so a
 *       level 1 house scores 0.33 and Talking Colonists then renders lines like
 *       "the shack you're living in barely counts as a proper home" - which
 *       sounds exactly like "I have no house".</li>
 * </ul>
 * So we hand every colonist the plain truth about their own roof, the same way
 * FoodCheck hands them the truth about their dinner.
 */
public final class HomeCheck {

    private HomeCheck() {
    }

    private static final Map<String, Integer> COLONY_OF_NAME = new ConcurrentHashMap<>();

    public static String promptLine(String citizenName) {
        try {
            ICitizenData data = find(citizenName);
            if (data == null) return "";

            IBuilding home = null;
            try {
                home = data.getHomeBuilding();
            } catch (Throwable ignored) {
            }
            boolean guard = false;
            try {
                guard = data.getJob() != null && data.getJob().isGuard();
            } catch (Throwable ignored) {
            }

            if (home != null) {
                int level = 0;
                try {
                    level = home.getBuildingLevel();
                } catch (Throwable ignored) {
                }
                String name = ErrandBuildings.bestName(home);
                StringBuilder sb = new StringBuilder("\n\nHOME TRUTH: you DO have a home - the ")
                        .append(name).append(", level ").append(level)
                        .append(". Never say you are homeless, that you have nowhere to sleep or that you have no "
                                + "roof: it is not true and the player has already housed you. ");
                if (level <= 2) {
                    sb.append("It IS small though (housing comfort in this colony is the house's level divided by "
                            + "three, so level ").append(level).append(" feels cramped) - if housing comes up, wish "
                            + "for it to be UPGRADED and bigger, which is an honest complaint, rather than claiming "
                            + "you have none.");
                } else {
                    sb.append("It is a decent place by the colony's standards - be content with it.");
                }
                return sb.toString();
            }

            if (guard) {
                return "\n\nHOME TRUTH: you have no separate house, but you are a guard - your tower is your "
                        + "quarters and that is normal for a soldier. Do not complain about being homeless.";
            }
            return "\n\nHOME TRUTH: you genuinely have NO home assigned yet. Say so plainly if it comes up, and "
                    + "know why: in this colony a house has to have room for you and you have to be assigned to it "
                    + "- an empty new house does not house anyone by itself. Asking the player for a house (or for "
                    + "an existing one to be upgraded so it holds more people) is fair and useful.";
        } catch (Throwable t) {
            return "";
        }
    }

    private static ICitizenData find(String citizenName) {
        if (citizenName == null || citizenName.isBlank()) return null;
        try {
            Integer cached = COLONY_OF_NAME.get(citizenName);
            if (cached != null) {
                IColony colony = byId(cached);
                if (colony != null) {
                    ICitizenData d = inColony(colony, citizenName);
                    if (d != null) return d;
                }
            }
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                ICitizenData d = inColony(colony, citizenName);
                if (d != null) {
                    COLONY_OF_NAME.put(citizenName, colony.getID());
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ICitizenData inColony(IColony colony, String name) {
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (name.equals(cd.getName())) return cd;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static IColony byId(int id) {
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getID() == id) return colony;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void clearAll() {
        COLONY_OF_NAME.clear();
    }
}
