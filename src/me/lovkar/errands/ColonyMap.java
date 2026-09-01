package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lovkar: "most of them tell me their leg hurts, or that there is no defence on
 * the western wall."
 * <p>
 * Neither is true, and neither is a bug in the game - it is the model filling a
 * gap. The prompt told a colonist nothing about their own body and nothing about
 * where anything in the colony stands, so when a conversation wanted a concrete
 * detail it invented a plausible one: an ache, a wall, an undefended side. A
 * language model with no grounding will always do that.
 * <p>
 * So we give them the two things they were missing and forbid the invention:
 * their real health, and a real map in compass terms, measured from the town
 * hall. Now "the western side has no guard tower" is either true and checkable,
 * or it is a thing they are told plainly not to say.
 */
public final class ColonyMap {

    private ColonyMap() {
    }

    /** Compass bearing from the colony centre, the way a person would say it. */
    public static String direction(BlockPos centre, BlockPos at) {
        try {
            int dx = at.getX() - centre.getX();
            int dz = at.getZ() - centre.getZ();
            if (Math.abs(dx) < 12 && Math.abs(dz) < 12) {
                return "right in the middle";
            }
            // Minecraft: -Z is north, +X is east.
            double angle = Math.toDegrees(Math.atan2(dx, -dz));
            if (angle < 0) {
                angle += 360;
            }
            String[] names = {"north", "north-east", "east", "south-east",
                    "south", "south-west", "west", "north-west"};
            return names[(int) Math.round(angle / 45.0) % 8];
        } catch (Throwable t) {
            return "somewhere in the colony";
        }
    }

    private static int distance(BlockPos centre, BlockPos at) {
        return (int) Math.sqrt(centre.distSqr(at));
    }

    /** Where this building is, in words: "north-east, about 40 blocks out". */
    public static String where(IColony colony, IBuilding b) {
        BlockPos centre = colony.getCenter();
        String dir = direction(centre, b.getPosition());
        if ("right in the middle".equals(dir)) {
            return dir;
        }
        return dir + ", about " + distance(centre, b.getPosition()) + " blocks out";
    }

    /**
     * The prompt block: their own health, their own bearings, and which sides of
     * the colony actually have a guard post.
     */
    public static String promptBlock(String citizenName) {
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                    if (!citizenName.equals(data.getName())) {
                        continue;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(health(data));
                    sb.append(bearings(colony, data));
                    sb.append(defences(colony));
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String health(ICitizenData data) {
        try {
            var dh = data.getCitizenDiseaseHandler();
            boolean sick = dh != null && dh.isSick();
            boolean hurt = dh != null && dh.isHurt();
            float hp = -1;
            try {
                AbstractEntityCitizen e = data.getEntity().orElse(null);
                if (e != null) {
                    hp = e.getHealth();
                }
            } catch (Throwable ignored) {
            }
            if (sick) {
                String disease = "an illness";
                try {
                    var d = dh.getDisease();
                    if (d != null) {
                        disease = String.valueOf(d.name());
                    }
                } catch (Throwable ignored) {
                }
                return "\n\nYOUR HEALTH: you are genuinely ILL (" + disease + ") and need the hospital. "
                        + "Say so if it comes up.";
            }
            if (hurt || (hp >= 0 && hp < 12)) {
                return "\n\nYOUR HEALTH: you are WOUNDED and still healing. That much is real - but name no other "
                        + "ailment.";
            }
            return "\n\nYOUR HEALTH: you are perfectly well - no illness, no injury, nothing aching. NEVER invent "
                    + "an ache, a bad leg, a cough or any other complaint about your body; if you are asked how you "
                    + "are, you are fine.";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String bearings(IColony colony, ICitizenData data) {
        try {
            StringBuilder sb = new StringBuilder("\n\nYOUR BEARINGS: the town hall is the middle of "
                    + colony.getName() + ".");
            IBuilding work = data.getWorkBuilding();
            if (work != null) {
                sb.append(" You work at the ").append(name(work)).append(", which is ")
                        .append(where(colony, work)).append(" from the middle.");
            }
            IBuilding home = data.getHomeBuilding();
            if (home != null && home != work) {
                sb.append(" You live at the ").append(name(home)).append(", ")
                        .append(where(colony, home)).append(".");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String defences(IColony colony) {
        try {
            Set<String> guarded = new LinkedHashSet<>();
            List<String> posts = new ArrayList<>();
            BlockPos centre = colony.getCenter();
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                try {
                    if (!(b instanceof AbstractBuildingGuards) || b.getBuildingLevel() <= 0) {
                        continue;
                    }
                    String dir = direction(centre, b.getPosition());
                    guarded.add(dir);
                    if (posts.size() < 6) {
                        posts.add(name(b) + " to the " + dir);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (posts.isEmpty()) {
                return "\n\nTHE DEFENCES: this colony has NO guard tower or barracks at all yet - that is the plain "
                        + "truth and you may worry about it. Do not describe walls or defences that do not exist.";
            }
            StringBuilder sb = new StringBuilder("\n\nTHE DEFENCES: the colony's guard posts are ")
                    .append(String.join(", ", posts)).append(".");
            List<String> bare = new ArrayList<>();
            for (String side : new String[] {"north", "east", "south", "west"}) {
                boolean covered = false;
                for (String g : guarded) {
                    if (g.contains(side)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    bare.add(side);
                }
            }
            if (bare.isEmpty()) {
                sb.append(" Every side has a post - do NOT claim any side is undefended.");
            } else {
                sb.append(" Nothing stands on the ").append(String.join(" or ", bare))
                        .append(" side, and you may say so.");
            }
            sb.append(" This colony has NO walls unless you have seen them built. Never invent a wall, a gate or a "
                    + "watchpost that is not in this list.");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String name(IBuilding b) {
        String key = null;
        try {
            key = b.getBuildingDisplayName();
            String resolved = Component.translatable(key).getString();
            if (resolved != null && !resolved.isBlank() && !resolved.equals(key)) {
                return resolved;
            }
        } catch (Throwable ignored) {
        }
        try {
            String n = key != null ? key : b.getSchematicName();
            int dot = n.lastIndexOf('.');
            n = dot >= 0 ? n.substring(dot + 1) : n;
            return n.isBlank() ? "building" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
        } catch (Throwable ignored) {
            return "building";
        }
    }
}
