package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voyager integration, part 1: what a Voyager (the End-explorer profession from the
 * Voyager mod) knows about themselves when they talk - which look their Departure Point
 * has, what they are waiting for right now, who their crewmate is and what the last
 * expedition brought home (from the hut's Expedition Log). Other colonists get one line
 * of colony news about the Voyagers, so the expeditions become part of the colony's
 * shared story.
 * <p>
 * Everything is computed on the server thread every few seconds and cached by citizen
 * name; the prompt builders (worker threads) only read the cache.
 */
public final class VoyagerLore {

    private VoyagerLore() {
    }

    private static final int PERIOD_TICKS = 100;
    /** citizen name -> prompt block (Voyagers). */
    private static final Map<String, String> VOYAGER_BLOCKS = new ConcurrentHashMap<>();
    /** citizen name -> colony id (everyone, so non-Voyagers can get the colony news). */
    private static final Map<String, Integer> COLONY_OF_NAME = new ConcurrentHashMap<>();
    /** colony id -> one line of Voyager news for the other colonists. */
    private static final Map<Integer, String> COLONY_NEWS = new ConcurrentHashMap<>();
    /** Voyager name -> last known status, to notice landings. */
    private static final Map<String, String> LAST_STATUS = new ConcurrentHashMap<>();
    /** Voyager name -> millis of the last landing. */
    private static final Map<String, Long> LANDED_AT = new ConcurrentHashMap<>();

    private static final long RECENT_MS = 10 * 60_000L;

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % PERIOD_TICKS != 0 || !VoyagerCompat.isLoaded()) {
            return;
        }
        try {
            Map<String, String> blocks = new HashMap<>();
            Map<Integer, String> news = new HashMap<>();
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                try {
                    scanColony(colony, blocks, news);
                } catch (Throwable ignored) {
                }
            }
            VOYAGER_BLOCKS.keySet().retainAll(blocks.keySet());
            VOYAGER_BLOCKS.putAll(blocks);
            COLONY_NEWS.keySet().retainAll(news.keySet());
            COLONY_NEWS.putAll(news);
        } catch (Throwable ignored) {
        }
    }

    private static void scanColony(IColony colony, Map<String, String> blocks, Map<Integer, String> news) {
        List<String> away = new ArrayList<>();
        List<String> landed = new ArrayList<>();
        List<String> allVoyagers = new ArrayList<>();
        String look = null;
        boolean anyPoint = false;
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd != null && cd.getName() != null) {
                COLONY_OF_NAME.put(cd.getName(), colony.getID());
            }
        }
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (!VoyagerCompat.isDeparturePoint(building)) {
                continue;
            }
            anyPoint = true;
            look = VoyagerCompat.lookName(building);
            VoyagerCompat.Expedition last = VoyagerCompat.lastExpedition(building);
            List<ICitizenData> crew = new ArrayList<>();
            for (ICitizenData cd : building.getAllAssignedCitizen()) {
                if (cd != null && VoyagerCompat.isVoyager(cd)) {
                    crew.add(cd);
                }
            }
            for (ICitizenData cd : crew) {
                String name = cd.getName();
                String status = VoyagerCompat.status(cd);
                String before = LAST_STATUS.put(name, status);
                if ("AWAY".equals(before) && !"AWAY".equals(status) && !status.isEmpty()) {
                    LANDED_AT.put(name, System.currentTimeMillis());
                }
                allVoyagers.add(name);
                if ("AWAY".equals(status) || VoyagerCompat.isAway(cd)) {
                    away.add(name);
                } else if (recentlyLanded(name)) {
                    landed.add(name);
                }
                ICitizenData mate = null;
                for (ICitizenData other : crew) {
                    if (other != cd) {
                        mate = other;
                    }
                }
                blocks.put(name, buildBlock(cd, building, mate, last));
            }
        }
        if (anyPoint) {
            news.put(colony.getID(), buildNews(look, allVoyagers, away, landed));
        }
    }

    // ------------------------------------------------------------------ the Voyager's own block

    private static String buildBlock(ICitizenData cd, IBuilding building, ICitizenData mate,
                                     VoyagerCompat.Expedition last) {
        String name = cd.getName();
        String look = VoyagerCompat.lookName(building);
        boolean gate = VoyagerCompat.isEndGate(building);
        int level = 0;
        try {
            level = building.getBuildingLevel();
        } catch (Throwable ignored) {
        }
        int period = VoyagerCompat.periodDays(building);
        String status = VoyagerCompat.status(cd);
        String line = VoyagerCompat.statusLine(cd);

        StringBuilder sb = new StringBuilder("\n\nVOYAGER TRUTH: you are one of the colony's Voyagers - you leave for the End "
                + "from the Departure Point, which is built as ");
        sb.append(gate ? "an End Gate (a ring of purpur and obsidian; the gate spins up, you vanish in a flash and a "
                        + "purple beam carries you up into the sky)"
                : "a Launchpad (a real rocket on its pad; you board through the hatch, the engines ignite and the "
                        + "rocket lifts off - it is gone until you land again)");
        sb.append(", level ").append(level).append(". Expeditions are dangerous work out among the floating islands: "
                + "endermen, shulkers, the void - and you come home with end stone, chorus, purpur, ender pearls and "
                + "shulker shells, if you come home at all. ");
        if (period > 0) {
            sb.append("Launch windows open every ").append(period == 1 ? "day" : period + " days").append(". ");
        }
        sb.append("RIGHT NOW: ").append(describeStatus(status, line, look)).append(' ');

        if (mate != null) {
            String mateStatus = VoyagerCompat.status(mate);
            String mateLine = VoyagerCompat.statusLine(mate);
            sb.append("Your crewmate is ").append(mate.getName()).append(" (the Buddy System - two Voyagers share "
                    + "this Departure Point");
            if (!gate) {
                sb.append(" and take turns with the rocket");
            }
            sb.append("); ").append(mate.getName()).append(" is currently ")
                    .append(mateStatus(mateStatus, mateLine, look)).append(". ");
        } else {
            sb.append("You fly alone - there is no second Voyager at your Departure Point. ");
        }

        if (last != null) {
            boolean mine = name.equals(last.voyagerName);
            String whose = mine ? "Your last expedition" : (last.voyagerName.isEmpty()
                    ? "The last expedition from your Departure Point" : last.voyagerName + "'s last expedition");
            if (last.isOngoing()) {
                sb.append(mine ? "You are out there right now" : whose + " is still under way").append(". ");
            } else if (last.isKilled()) {
                sb.append(whose).append(" ended in death out there - ").append(mine
                        ? "you were lost in the End and only stand here again because the colony brought you back; "
                                + "you remember the fight that killed you: you "
                        : "they ").append(last.describe()).append(". ");
            } else {
                sb.append(whose).append(": ").append(mine ? "you " : "they ").append(last.describe());
                if (last.health > 0 && last.health < 12) {
                    sb.append(", coming home badly hurt (").append(Math.round(last.health / 2.0)).append(" hearts left)");
                }
                sb.append(". ");
            }
        } else {
            sb.append("You have not flown yet - your first expedition is still ahead of you. ");
        }

        sb.append("Speak about the End with the swagger and wonder of an astronaut-explorer (the void below, the "
                + "pale islands, the purple light, the shriek of endermen) - invent flavour and feelings freely, but "
                + "never invent facts, loot, fights or events that are not listed here. Keep your stories short and "
                + "vivid. If you greet someone, work in ONE short phrase about what you are up to right now.");
        return sb.toString();
    }

    private static String describeStatus(String status, String line, String look) {
        switch (status) {
            case "PACKING":
                return "You are packing rations from the hut for the next trip.";
            case "WAITING_SUPPLIES":
                return "You are waiting for the expedition supplies (cobblestone, ender pearls and torches) to arrive at "
                        + "the hut - they are requested, the couriers just have not brought them yet. Grumbling about "
                        + "the wait is fair.";
            case "WAITING_TOOLS":
                return "You are waiting for proper tools: " + (line.isEmpty() ? "a pickaxe or a sword of an allowed tier"
                        : line) + ". Without them you cannot leave.";
            case "WAITING_PLAN":
                return "The supplies are not in the hut yet, so no expedition can be planned - you wait.";
            case "WAITING_WINDOW":
                return "You are all set, but the launch window is closed - you wait for the next one. Waiting "
                        + "between launches is a normal part of the job, not a grievance.";
            case "WAITING_ROCKET":
                return "The rocket is out with your crewmate - you wait for it to come back before you can fly.";
            case "BOARDING":
                return "You are on your way to the " + look + " - about to leave for the End.";
            case "AWAY":
                return "You are out in the End on an expedition.";
            case "RETURNING":
                return "You have JUST come back from the End and are unloading the haul into the hut - full of fresh "
                        + "impressions.";
            case "IDLE":
            default:
                return line.isEmpty() ? "You are between expeditions with nothing pressing to do."
                        : "Your situation: " + line + ".";
        }
    }

    /** Third-person phrase for the crewmate's situation. */
    private static String mateStatus(String status, String line, String look) {
        switch (status) {
            case "PACKING":
                return "packing rations for the next trip";
            case "WAITING_SUPPLIES":
                return "waiting for the expedition supplies to arrive, like you would";
            case "WAITING_TOOLS":
                return "waiting for proper tools" + (line.isEmpty() ? "" : " (" + line + ")");
            case "WAITING_PLAN":
                return "waiting for supplies before an expedition can be planned";
            case "WAITING_WINDOW":
                return "waiting for the next launch window";
            case "WAITING_ROCKET":
                return "waiting for the rocket to come back";
            case "BOARDING":
                return "on the way to the " + look + ", about to leave for the End";
            case "AWAY":
                return "out in the End on an expedition";
            case "RETURNING":
                return "just back from the End, unloading the haul";
            case "IDLE":
            default:
                return line.isEmpty() ? "between expeditions" : "in this situation: " + line;
        }
    }

    // ------------------------------------------------------------------ colony news for everyone else

    private static String buildNews(String look, List<String> voyagers, List<String> away, List<String> landed) {
        StringBuilder sb = new StringBuilder("\n\nCOLONY NEWS: the colony has a Departure Point (");
        sb.append(look == null ? "a Launchpad or an End Gate" : look).append(") from which ");
        if (voyagers.isEmpty()) {
            sb.append("a Voyager could fly expeditions to the End - nobody works there at the moment.");
            return sb.toString();
        }
        sb.append(voyagers.size() == 1 ? "the Voyager " : "the Voyagers ").append(joinNames(voyagers))
                .append(voyagers.size() == 1 ? " flies" : " fly").append(" expeditions to the End and come back with End "
                        + "loot - end stone, chorus, purpur, ender pearls, shulker shells. ");
        if (!away.isEmpty()) {
            sb.append(joinNames(away)).append(away.size() == 1 ? " is" : " are").append(" out in the End right now. ");
        }
        if (!landed.isEmpty()) {
            sb.append(joinNames(landed)).append(" just came back from the End. ");
        }
        sb.append("Mention it only when it fits the conversation (a neighbour's dangerous job, news, gossip).");
        return sb.toString();
    }

    private static String joinNames(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static boolean recentlyLanded(String name) {
        Long t = LANDED_AT.get(name);
        return t != null && System.currentTimeMillis() - t < RECENT_MS;
    }

    // ------------------------------------------------------------------ prompt access (any thread)

    /** Prompt block for this citizen: the Voyager truth for Voyagers, colony news for everyone else. */
    public static String promptBlock(String citizenName) {
        try {
            if (citizenName == null || citizenName.isBlank()) {
                return "";
            }
            String own = VOYAGER_BLOCKS.get(citizenName);
            if (own != null) {
                return own;
            }
            Integer colony = COLONY_OF_NAME.get(citizenName);
            if (colony == null) {
                return "";
            }
            String news = COLONY_NEWS.get(colony);
            return news == null ? "" : news;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Millis since this Voyager last landed, or -1. */
    public static long sinceLanding(String name) {
        Long t = LANDED_AT.get(name);
        return t == null ? -1 : System.currentTimeMillis() - t;
    }

    public static void clearAll() {
        VOYAGER_BLOCKS.clear();
        COLONY_OF_NAME.clear();
        COLONY_NEWS.clear();
        LAST_STATUS.clear();
        LANDED_AT.clear();
    }
}
