package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.jobs.AbstractJobStructure;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar: "make them react to and talk about what you build, and what gets
 * upgraded."
 * <p>
 * Same shape as [[ResearchWatcher]], and for the same reason: MineColonies fires
 * nothing we can subscribe to for "a building finished", so we watch the state
 * ourselves. Every building's level is snapshotted; a level that RISES is the
 * event. Level 0 to 1 is a new building, anything above that is an upgrade.
 * <p>
 * The first pass on a world only seeds the snapshot - a colony that already has
 * forty buildings must not announce forty of them at login.
 */
public final class ConstructionWatcher {

    private ConstructionWatcher() {
    }

    /** colonyId -> (hut position -> level we last saw). */
    private static final Map<Integer, Map<BlockPos, Integer>> SEEN = new ConcurrentHashMap<>();
    /** colonyId -> the last few things finished, for the prompt. */
    private static final Map<Integer, LinkedList<String>> RECENT = new ConcurrentHashMap<>();
    private static final int RECENT_CAP = 4;

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                try {
                    Map<BlockPos, Integer> levels = SEEN.get(colony.getID());
                    boolean seeding = levels == null;
                    if (seeding) {
                        levels = new ConcurrentHashMap<>();
                        SEEN.put(colony.getID(), levels);
                    }
                    for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                        try {
                            int level = b.getBuildingLevel();
                            Integer was = levels.put(b.getPosition(), level);
                            if (seeding || was == null || level <= was) {
                                continue;
                            }
                            announce(server, colony, b, was, level);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void announce(MinecraftServer server, IColony colony, IBuilding b, int from, int to) {
        String name = buildingName(b);
        boolean brandNew = from <= 0;
        String headline = brandNew
                ? "The " + name + " is finished"
                : "The " + name + " has been upgraded to level " + to;
        ColonistErrands.LOGGER.info("[Built] Colony {}: {} (level {} -> {})", colony.getID(), name, from, to);

        LinkedList<String> recent = RECENT.computeIfAbsent(colony.getID(), k -> new LinkedList<>());
        String at = "";
        try {
            at = " - " + ColonyMap.where(colony, b);
        } catch (Throwable ignored) {
        }
        recent.addFirst((brandNew ? name + " (new)" : name + " (now level " + to + ")") + at);
        while (recent.size() > RECENT_CAP) {
            recent.removeLast();
        }

        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal("[Built] " + headline + " ("
                        + ColonyMap.where(colony, b) + ") - the colony is talking about it."));
            }
        } catch (Throwable ignored) {
        }

        // The builder who put it up is proud of it; everyone else heard the news.
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                try {
                    boolean builder = cd.getJob() instanceof AbstractJobStructure<?, ?>;
                    boolean livesThere = cd.getHomeBuilding() == b;
                    boolean worksThere = cd.getWorkBuilding() == b;
                    String event;
                    if (worksThere) {
                        event = brandNew
                                ? "MY OWN workplace, the " + name + ", is finally built. This is where I work now, "
                                + "and I am glad of it."
                                : "MY OWN workplace, the " + name + ", has just been upgraded to level " + to
                                + ". Better tools, more room - I noticed straight away.";
                    } else if (livesThere) {
                        event = brandNew
                                ? "My home, the " + name + ", has just been finished. A proper roof at last."
                                : "My home, the " + name + ", has been upgraded to level " + to
                                + " - it is a better place to live now.";
                    } else if (builder) {
                        event = brandNew
                                ? "We finished building the " + name + ". I am a builder in this colony and I am "
                                + "proud of the work - I will happily talk about how it went."
                                : "We finished upgrading the " + name + " to level " + to
                                + ". Builder's work, and I am proud of it.";
                    } else {
                        event = brandNew
                                ? "Word went round: the " + name + " is finished. Everybody walked past to look at "
                                + "it - I have an opinion about whether we needed it."
                                : "Word went round: the " + name + " has been upgraded to level " + to
                                + ". People are talking about what it will change.";
                    }
                    ((CitizenDataMemoryExtended) cd).mc_talking$getOrInitializeMemory().addEvent(event);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Appended to every colonist's prompt, so they can bring it up unprompted. */
    public static String promptBlock(String citizenName) {
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                    if (!citizenName.equals(cd.getName())) {
                        continue;
                    }
                    LinkedList<String> recent = RECENT.get(colony.getID());
                    if (recent == null || recent.isEmpty()) {
                        return "";
                    }
                    return "\n\nRECENTLY BUILT: " + String.join(", ", recent)
                            + ". These went up in your colony just now - you saw them go up, you have opinions "
                            + "about them, and you can bring them up in conversation.";
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String buildingName(IBuilding b) {
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

    public static void clearAll() {
        SEEN.clear();
        RECENT.clear();
    }
}
