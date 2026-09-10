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
 * expedition brought home (from the hut's Expedition Log). Since 2.2.0 the same for
 * Voyager 0.3's two other professions: the Observatory's astronomer (nights at the
 * telescope, the sky book, the darkroom) and the Photo Booth's photographer (portraits,
 * paying sitters, the colony chronicle) - their truth blocks are built from what Voyager
 * itself says about them (status lines and {@code describeForChat()}, read by reflection).
 * Other colonists get one line of colony news about all of them, so the expeditions, the
 * discoveries and the portraits become part of the colony's shared story.
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
        StringBuilder skyNews = new StringBuilder();
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd != null && cd.getName() != null) {
                COLONY_OF_NAME.put(cd.getName(), colony.getID());
            }
        }
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            if (VoyagerCompat.isObservatory(building)) {
                skyNews.append(scanObservatory(building, blocks));
                continue;
            }
            if (VoyagerCompat.isPhotoBooth(building)) {
                skyNews.append(scanPhotoBooth(building, blocks));
                continue;
            }
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
        if (anyPoint || skyNews.length() > 0) {
            String line = anyPoint ? buildNews(look, allVoyagers, away, landed) : "\n\nCOLONY NEWS:";
            news.put(colony.getID(), line + skyNews);
        }
    }

    // ------------------------------------------------------------------ the Observatory and the Photo Booth (Voyager 0.3)

    /** Blocks for the astronomers at this Observatory; returns the colony-news sentence about it. */
    private static String scanObservatory(IBuilding building, Map<String, String> blocks) {
        String about = VoyagerCompat.describe(building);
        List<String> names = new ArrayList<>();
        for (ICitizenData cd : building.getAllAssignedCitizen()) {
            if (cd == null || !VoyagerCompat.isAstronomer(cd)) {
                continue;
            }
            names.add(cd.getName());
            blocks.put(cd.getName(), buildAstronomerBlock(cd, building, about));
        }
        StringBuilder sb = new StringBuilder(" The colony has an Observatory");
        if (names.isEmpty()) {
            sb.append(" with nobody working in it yet.");
        } else {
            sb.append(" where ").append(joinNames(names)).append(names.size() == 1 ? " keeps" : " keep")
                    .append(" the night watch on the sky - up all night at the telescope, asleep by day - and the "
                            + "colony's sky book fills with what the plates catch");
            String latest = latestEntry(about);
            if (!latest.isEmpty()) {
                sb.append("; the latest entry is ").append(latest);
            }
            sb.append('.');
        }
        return sb.toString();
    }

    /** Blocks for the photographers at this Photo Booth; returns the colony-news sentence about it. */
    private static String scanPhotoBooth(IBuilding building, Map<String, String> blocks) {
        String about = VoyagerCompat.describe(building);
        List<String> names = new ArrayList<>();
        for (ICitizenData cd : building.getAllAssignedCitizen()) {
            if (cd == null || !VoyagerCompat.isPhotographer(cd)) {
                continue;
            }
            names.add(cd.getName());
            blocks.put(cd.getName(), buildPhotographerBlock(cd, building, about));
        }
        StringBuilder sb = new StringBuilder(" The colony has a Photo Booth");
        if (names.isEmpty()) {
            sb.append(" with no photographer yet.");
        } else {
            sb.append(" where ").append(joinNames(names)).append(names.size() == 1 ? " takes" : " take")
                    .append(" portraits of colonists and of paying visitors, and photographs every building as it "
                            + "goes up for the colony chronicle.");
        }
        return sb.toString();
    }

    /** "the Crab Nebula, caught last night" out of the Observatory's own description, or "". */
    private static String latestEntry(String about) {
        int i = about.indexOf("the latest entry is ");
        if (i < 0) {
            return "";
        }
        int end = about.indexOf('.', i);
        String tail = about.substring(i + "the latest entry is ".length(), end < 0 ? about.length() : end);
        int paren = tail.indexOf(" (");
        return paren < 0 ? tail : tail.substring(0, paren);
    }

    private static String buildAstronomerBlock(ICitizenData cd, IBuilding building, String about) {
        String status = VoyagerCompat.status(cd);
        String line = VoyagerCompat.statusLine(cd);
        int level = 0;
        try {
            level = building.getBuildingLevel();
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder("\n\nASTRONOMER TRUTH: you are the colony's astronomer at the Observatory"
                + " (level " + level + "). You work NIGHTS and sleep by day - the one colonist whose shift is the "
                + "opposite of everybody else's - and you live at the Observatory itself, in the bed in the study, so "
                + "you never walk home across a sleeping town at dawn. Your work: at dusk you go to the instrument "
                + "(or, when the colony wants it and the land offers a hill, up to a lookout with the colony's camera "
                + "and a guard escort) and keep the watch until the night's plate is exposed; by day you develop last "
                + "night's plate in the darkroom under the red lamp, and whatever the plate caught goes into the "
                + "colony's sky book - a first sighting pays the colony in real goods, a better lens reaches fainter "
                + "objects, and the darkroom prints the best plates as photographs of the night sky. ");
        if (!about.isEmpty()) {
            sb.append("YOUR OBSERVATORY: ").append(about).append(' ');
        }
        sb.append("RIGHT NOW: ").append(astronomerStatus(status, line)).append(' ');
        sb.append("Speak about the night sky with quiet wonder - the cold, the silence, the stars wheeling, the "
                + "smell of the developer in the darkroom - and invent flavour and feelings freely, but never invent "
                + "objects, discoveries, lenses or events that are not listed here. If you greet someone, work in ONE "
                + "short phrase about what you are up to right now.");
        return sb.toString();
    }

    private static String astronomerStatus(String status, String line) {
        if (!line.isEmpty()) {
            return "You are " + line + ".";
        }
        switch (status) {
            case "WALKING":
                return "You are on your way to the instrument.";
            case "OBSERVING":
                return "You are at the instrument, keeping the watch.";
            case "CLOUDED":
                return "The sky is clouded over - no watch tonight.";
            case "BLOCKED":
                return "You cannot reach the instrument - something is in the way, and it annoys you.";
            case "IDLE":
            default:
                return "You are off duty - the watch is kept at night.";
        }
    }

    private static String buildPhotographerBlock(ICitizenData cd, IBuilding building, String about) {
        String status = VoyagerCompat.status(cd);
        String line = VoyagerCompat.statusLine(cd);
        int level = 0;
        try {
            level = building.getBuildingLevel();
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder("\n\nPHOTOGRAPHER TRUTH: you are the colony's photographer at the Photo "
                + "Booth (level " + level + ") - a studio with a gallery wall and a camera stand"
                + (level >= 2 ? ", and a darkroom wing where film is developed under a red lamp" : "")
                + ". You live at the Photo Booth, in the bed by the gallery wall. Your work: at the bench you craft the "
                + "colony's cameras, film, photograph frames and albums; in the studio you take portraits of colonists, "
                + "and visitors sit for paid portraits whose money goes to the colony; and you keep the colony "
                + "chronicle - you walk out to every building as it goes up, photograph it half-built and finished, "
                + "and file the pictures in albums. ");
        if (!about.isEmpty()) {
            sb.append("YOUR STUDIO: ").append(about).append(' ');
        }
        sb.append("RIGHT NOW: ").append(photographerStatus(status, line)).append(' ');
        sb.append("Speak about your pictures with an artist's eye - the light, a sitter's expression, the smell of "
                + "the developer, the album filling up - and invent flavour and feelings freely, but never invent "
                + "portraits, sitters, sales or events that are not listed here. If you greet someone, work in ONE "
                + "short phrase about what you are up to right now.");
        return sb.toString();
    }

    private static String photographerStatus(String status, String line) {
        if (!line.isEmpty()) {
            return "You are " + line + ".";
        }
        switch (status) {
            case "CRAFTING":
                return "You are at the bench, crafting for the colony.";
            case "PORTRAIT":
                return "You are taking a colonist's portrait in the studio.";
            case "SITTING":
                return "A visitor is sitting for a paid portrait.";
            case "CHRONICLE":
                return "You are out photographing a building for the colony chronicle.";
            case "FILING":
                return "You are filing a photograph in the album.";
            case "IDLE":
            default:
                return "You are minding the studio between pictures.";
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
