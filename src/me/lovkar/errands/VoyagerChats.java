package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voyager integration, part 2: crew talk. With the Buddy System two Voyagers share one
 * Departure Point, and they spend a lot of time waiting together - for supplies, for the
 * next launch window, for the rocket to come back. When both are waiting (and near each
 * other, free and allowed to speak) they get a citizen-to-citizen conversation with a
 * memory note that says what they are waiting for and what the last expedition brought,
 * so the chat is astronaut shop talk rather than small talk. When one of them has just
 * landed, the crewmate welcomes them back and hears the story.
 */
public final class VoyagerChats {

    private VoyagerChats() {
    }

    private static final int CHECK_TICKS = 1200;                 // every minute
    private static final long GLOBAL_COOLDOWN_MS = 4 * 60_000;   // one crew chat per ~4 min across colonies
    private static final long PAIR_COOLDOWN_MS = 25 * 60_000;    // the same crew at most every 25 min
    private static final long WELCOME_COOLDOWN_MS = 12 * 60_000; // a homecoming chat at most every 12 min
    private static final long WELCOME_WINDOW_MS = 4 * 60_000;    // ...and only within 4 min of the landing
    private static final double NEAR_DIST_SQR = 24.0 * 24.0;
    private static final Set<String> WAITING = Set.of("IDLE", "PACKING", "WAITING_SUPPLIES", "WAITING_TOOLS",
            "WAITING_PLAN", "WAITING_WINDOW", "WAITING_ROCKET");

    private static long lastChatMs = 0;
    private static final Map<Long, Long> PAIR_LAST = new ConcurrentHashMap<>();
    private static final Map<Long, Long> WELCOME_LAST = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_TICKS != 0 || !VoyagerCompat.isLoaded()) {
            return;
        }
        if (System.currentTimeMillis() - lastChatMs < GLOBAL_COOLDOWN_MS) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getRaiderManager().isRaided()) {
                    continue;
                }
                for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                    if (VoyagerCompat.isDeparturePoint(building) && tryCrewChat(server, building)) {
                        return; // at most one per tick across all colonies
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean tryCrewChat(MinecraftServer server, IBuilding building) {
        List<ICitizenData> crew = new ArrayList<>();
        for (ICitizenData cd : building.getAllAssignedCitizen()) {
            if (cd != null && VoyagerCompat.isVoyager(cd)) {
                crew.add(cd);
            }
        }
        if (crew.size() < 2) {
            return false;
        }
        ICitizenData a = crew.get(0);
        ICitizenData b = crew.get(1);
        if (a.getEntity().isEmpty() || b.getEntity().isEmpty()) {
            return false;
        }
        AbstractEntityCitizen ea = a.getEntity().get();
        AbstractEntityCitizen eb = b.getEntity().get();
        if (!ea.isAlive() || !eb.isAlive() || ea.isInvisible() || eb.isInvisible() || ea.level() != eb.level()) {
            return false; // invisible = out in the End
        }
        String sa = VoyagerCompat.status(a);
        String sb = VoyagerCompat.status(b);
        boolean aHome = justLanded(a);
        boolean bHome = justLanded(b);
        boolean welcome = (aHome && WAITING.contains(sb)) || (bHome && WAITING.contains(sa));
        boolean bothWaiting = WAITING.contains(sa) && WAITING.contains(sb);
        if (!welcome && !bothWaiting) {
            return false;
        }
        long pairKey = ((long) Math.min(a.getId(), b.getId()) << 32) | Math.max(a.getId(), b.getId());
        long now = System.currentTimeMillis();
        if (welcome) {
            Long last = WELCOME_LAST.get(pairKey);
            if (last != null && now - last < WELCOME_COOLDOWN_MS) {
                welcome = false;
                if (!bothWaiting) {
                    return false;
                }
            }
        }
        if (!welcome) {
            Long last = PAIR_LAST.get(pairKey);
            if (last != null && now - last < PAIR_COOLDOWN_MS) {
                return false;
            }
        }
        if (ea.distanceToSqr(eb) > NEAR_DIST_SQR) {
            return false;
        }
        if (!ConversationManager.canCitizenSpeak(ea) || !ConversationManager.canCitizenSpeak(eb)) {
            return false;
        }
        if (ConversationManager.isCitizenBusy(ea) || ConversationManager.isCitizenBusy(eb)) {
            return false;
        }
        if (!C2cAudioFollower.isFreeToChat(ea) || !C2cAudioFollower.isFreeToChat(eb)) {
            return false;
        }

        String look = VoyagerCompat.lookName(building);
        VoyagerCompat.Expedition last = VoyagerCompat.lastExpedition(building);
        String facts = last == null ? "Neither of you has flown yet - the first expedition is still ahead."
                : (last.voyagerName.isEmpty() ? "The last expedition" : last.voyagerName + "'s last expedition")
                        + (last.isKilled() ? " ended in death out there - they " : ": they ") + last.describe() + ".";

        if (welcome) {
            ICitizenData home = aHome ? a : b;
            ICitizenData greeter = aHome ? b : a;
            AbstractEntityCitizen eh = aHome ? ea : eb;
            AbstractEntityCitizen eg = aHome ? eb : ea;
            addMemory(eh, "You have JUST landed from the End at the " + look + " and your crewmate " + greeter.getName()
                    + " is there to welcome you. Tell them how it went in a few vivid sentences - stick to these facts: "
                    + facts + " Then ask what you missed at the colony.");
            addMemory(eg, "Your crewmate " + home.getName() + " has JUST landed from the End at the " + look
                    + ". Welcome them back like a fellow astronaut - ask how it went, what they saw, what they brought, "
                    + "whether they got hurt. Facts you will hear: " + facts + " Keep it to a few short exchanges.");
            WELCOME_LAST.put(pairKey, now);
            ColonistErrands.LOGGER.info("[Crew] {} welcomes {} back from the End at the {}",
                    greeter.getName(), home.getName(), look);
        } else {
            String reason = waitingReason(sa, sb, look);
            for (ICitizenData who : List.of(a, b)) {
                ICitizenData other = who == a ? b : a;
                addMemory(who.getEntity().get(), "You and your crewmate " + other.getName() + " are both waiting at the "
                        + look + " - " + reason + ". Have a short astronaut shop talk between missions: grumble or joke "
                        + "about the wait, swap impressions of the End (the void, the pale islands, the shriek of "
                        + "endermen), wonder what the next haul will bring. Facts you both know: " + facts
                        + " Keep it to a few short exchanges and do not invent events.");
            }
            PAIR_LAST.put(pairKey, now);
            ColonistErrands.LOGGER.info("[Crew] {} and {} talk shop at the {} ({})", a.getName(), b.getName(), look, reason);
        }
        lastChatMs = now;
        CitizenConversation conversation = new CitizenConversation(server, List.of(ea, eb));
        conversation.performConversation();
        return true;
    }

    private static String waitingReason(String sa, String sb, String look) {
        if ("WAITING_ROCKET".equals(sa) || "WAITING_ROCKET".equals(sb)) {
            return "the rocket is out and you wait for it to come back";
        }
        if ("WAITING_SUPPLIES".equals(sa) || "WAITING_SUPPLIES".equals(sb) || "WAITING_PLAN".equals(sa)
                || "WAITING_PLAN".equals(sb)) {
            return "the expedition supplies (cobblestone, ender pearls, torches) have not arrived at the hut yet";
        }
        if ("WAITING_TOOLS".equals(sa) || "WAITING_TOOLS".equals(sb)) {
            return "somebody still needs a proper pickaxe or sword before the next flight";
        }
        if ("WAITING_WINDOW".equals(sa) || "WAITING_WINDOW".equals(sb)) {
            return "the launch window is closed until the next one opens";
        }
        return "there is nothing to do until the next expedition";
    }

    private static boolean justLanded(ICitizenData cd) {
        long since = VoyagerLore.sinceLanding(cd.getName());
        return since >= 0 && since < WELCOME_WINDOW_MS && !"AWAY".equals(VoyagerCompat.status(cd));
    }

    private static void addMemory(AbstractEntityCitizen c, String event) {
        try {
            ((CitizenDataMemoryExtended) c.getCitizenData()).mc_talking$getOrInitializeMemory().addEvent(event);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        PAIR_LAST.clear();
        WELCOME_LAST.clear();
        lastChatMs = 0;
    }
}
