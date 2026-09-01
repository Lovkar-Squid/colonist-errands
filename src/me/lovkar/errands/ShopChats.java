package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.ICivilianData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's idea: when there is no customer in the shop or on the way to it, and
 * two people are stood together at the Marketplace, let them talk - the way real
 * shop staff do in a quiet hour.
 * <p>
 * One catch worth knowing: MC Trade Post registers the marketplace with
 * {@code new WorkerBuildingModule(shopkeeper, Creativity, Knowledge, false, b -> 1)}
 * - that trailing {@code b -> 1} is the capacity, so a marketplace has exactly ONE
 * shopkeeper at every level. Waiting for two marketplace employees to stand
 * together would therefore wait forever. So the pair is the shopkeeper plus
 * whoever else is actually in the shop with them: another shopkeeper from a second
 * marketplace, or any colonist who happens to be at the counter. One of the two is
 * always the shop's own worker, so it always reads as shop talk.
 * <p>
 * "Customer" is a real thing we can test rather than guess at. MC Trade Post
 * sends tavern VISITORS shopping through {@code EntityAIShoppingTask}, whose
 * state machine runs GOING_SHOPPING -> IS_SHOPPING -> PICK_DISPLAY. So a shopper
 * is either standing near the marketplace, or is somewhere in the colony with one
 * of those states on their AI - and either way the staff stay on the counter.
 * <p>
 * Everything here is soft: no MC Trade Post, no marketplaces, no chat. The
 * conversation itself is mc_talking's own citizen-to-citizen one, so the audio
 * follower, the slot accounting and the memories all behave exactly as they do
 * for family chats.
 */
public final class ShopChats {

    private ShopChats() {
    }

    private static final long GLOBAL_COOLDOWN_MS = 6 * 60_000L;
    private static final long PAIR_COOLDOWN_MS = 25 * 60_000L;
    /**
     * Within earshot - which for two shopkeepers is a call across the street, not
     * a huddle. They keep their counters; the chaperone only turns them to face
     * one another (see {@link C2cAudioFollower#expectStationary}).
     */
    private static final double TOGETHER_SQR = 16.0 * 16.0;
    /** ...and both actually at the shop, not off on a delivery. */
    private static final double AT_SHOP_SQR = 20.0 * 20.0;
    /** A shopper this close to the counter counts as "in the shop". */
    private static final double CUSTOMER_SQR = 24.0 * 24.0;

    /** A shop chat never outlives this, customer or no customer. */
    private static final long MAX_CHAT_MS = 3 * 60_000L;

    private static long lastChatMs = 0;
    private static final Map<Long, Long> PAIR_LAST = new ConcurrentHashMap<>();

    /**
     * The chat that is running right now. We keep hold of it because the pair are
     * held STATIONARY while they talk - the chaperone stops their navigation every
     * tick - so if a customer walks in we have to break the conversation off
     * ourselves, or the shopkeeper would stand there facing the wrong way while
     * somebody waits at the counter.
     */
    private static volatile Active active = null;

    private static final class Active {
        final CitizenConversation conversation;
        final IBuilding market;
        final int colonyId;
        final AbstractEntityCitizen a;
        final AbstractEntityCitizen b;
        final long startedMs = System.currentTimeMillis();

        Active(CitizenConversation conversation, IBuilding market, int colonyId,
               AbstractEntityCitizen a, AbstractEntityCitizen b) {
            this.conversation = conversation;
            this.market = market;
            this.colonyId = colonyId;
            this.a = a;
            this.b = b;
        }
    }

    public static void tick(MinecraftServer server) {
        // The customer comes first: check every 2 seconds while a chat is running.
        if (active != null && server.getTickCount() % 40 == 0) {
            watchActive(server);
        }
        if (server.getTickCount() % 2400 != 0) { // every ~2 minutes
            return;
        }
        if (active != null) {
            return; // one at a time, and it is still going
        }
        if (System.currentTimeMillis() - lastChatMs < GLOBAL_COOLDOWN_MS) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getRaiderManager().isRaided()) {
                    continue;
                }
                if (tryStartShopChat(server, colony)) {
                    return; // at most one across all colonies per pass
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Break the chat off the moment somebody needs serving. */
    private static void watchActive(MinecraftServer server) {
        Active cur = active;
        if (cur == null) {
            return;
        }
        try {
            if (System.currentTimeMillis() - cur.startedMs > MAX_CHAT_MS) {
                stop(cur, "it had gone on long enough");
                return;
            }
            IColony colony = null;
            for (IColony c : IColonyManager.getInstance().getAllColonies()) {
                if (c.getID() == cur.colonyId) {
                    colony = c;
                    break;
                }
            }
            if (colony == null) {
                stop(cur, "the colony is gone");
                return;
            }
            if (customerAround(colony, cur.market)) {
                memory(cur.a, "A customer walked in while you were talking - you broke off mid-sentence and went "
                        + "back to the counter. Business first.");
                memory(cur.b, "A customer walked in while you were talking - you broke off mid-sentence. "
                        + "Business first.");
                stop(cur, "a customer came in");
            }
        } catch (Throwable t) {
            stop(cur, "something went wrong watching the shop");
        }
    }

    private static void stop(Active cur, String why) {
        active = null;
        try {
            cur.conversation.abort();
        } catch (Throwable ignored) {
        }
        ColonistErrands.LOGGER.info("[Shop] Counter chat ended - {}", why);
    }

    private static boolean tryStartShopChat(MinecraftServer server, IColony colony) {
        List<IBuilding> markets;
        try {
            markets = TradePost.marketplaces(colony);
        } catch (Throwable t) {
            return false; // no MC Trade Post in this world
        }
        if (markets == null || markets.isEmpty()) {
            return false;
        }
        for (IBuilding market : markets) {
            try {
                if (customerAround(colony, market)) {
                    continue; // someone to serve - the counter comes first
                }
                List<AbstractEntityCitizen> own = freeStaff(market);
                if (own.isEmpty()) {
                    continue;
                }
                List<AbstractEntityCitizen> withThem = new ArrayList<>(own);
                withThem.addAll(othersAtTheShop(colony, market, own));
                if (withThem.size() < 2) {
                    continue;
                }
                for (int i = 0; i < own.size(); i++) {
                    for (int j = 0; j < withThem.size(); j++) {
                        AbstractEntityCitizen a = own.get(i);
                        AbstractEntityCitizen b = withThem.get(j);
                        if (a == b) {
                            continue;
                        }
                        if (a.distanceToSqr(b) > TOGETHER_SQR) {
                            continue;
                        }
                        long pairKey = ((long) Math.min(a.getId(), b.getId()) << 32) | Math.max(a.getId(), b.getId());
                        Long last = PAIR_LAST.get(pairKey);
                        if (last != null && System.currentTimeMillis() - last < PAIR_COOLDOWN_MS) {
                            continue;
                        }
                        if (!ConversationManager.hasLowPriorityCapacity(2)) {
                            return false; // do not evict somebody else's conversation for small talk
                        }
                        start(server, market, a, b, pairKey, own.contains(b));
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** A shopper standing in the shop, or one on their way to it. */
    private static boolean customerAround(IColony colony, IBuilding market) {
        try {
            BlockPos shop = market.getPosition();
            Map<Integer, ICivilianData> visitors = colony.getVisitorManager().getCivilianDataMap();
            if (visitors == null) {
                return false;
            }
            for (ICivilianData v : visitors.values()) {
                try {
                    Object raw = v.getEntity().orElse(null);
                    if (!(raw instanceof net.minecraft.world.entity.Entity e) || !e.isAlive()) {
                        continue;
                    }
                    if (e.blockPosition().distSqr(shop) <= CUSTOMER_SQR) {
                        return true;
                    }
                    String state = "";
                    if (e instanceof AbstractEntityCitizen ce) {
                        state = String.valueOf(ce.getEntityStateController().getState());
                    }
                    if (state.contains("SHOPPING") || state.contains("PICK_DISPLAY")) {
                        return true; // on their way, or picking something out
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Marketplace staff who are at the shop, awake and free to talk. */
    private static List<AbstractEntityCitizen> freeStaff(IBuilding market) {
        List<AbstractEntityCitizen> out = new ArrayList<>();
        try {
            BlockPos shop = market.getPosition();
            for (ICitizenData cd : market.getAllAssignedCitizen()) {
                try {
                    AbstractEntityCitizen e = cd.getEntity().orElse(null);
                    if (e == null || !e.isAlive() || e.isSleeping() || cd.isAsleep()) {
                        continue;
                    }
                    if (e.blockPosition().distSqr(shop) > AT_SHOP_SQR) {
                        continue; // away from the shop - not a quiet moment at the counter
                    }
                    if (ErrandManager.hasErrand(e) || ConversationManager.isCitizenBusy(e)) {
                        continue;
                    }
                    if (!ConversationManager.canCitizenSpeak(e)) {
                        continue;
                    }
                    out.add(e);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Anyone else actually stood in the shop. The marketplace only ever employs
     * one person, so without this the chat could never happen at all.
     */
    private static List<AbstractEntityCitizen> othersAtTheShop(IColony colony, IBuilding market,
                                                               List<AbstractEntityCitizen> own) {
        List<AbstractEntityCitizen> out = new ArrayList<>();
        try {
            BlockPos shop = market.getPosition();
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                try {
                    AbstractEntityCitizen e = cd.getEntity().orElse(null);
                    if (e == null || own.contains(e) || !e.isAlive() || e.isSleeping() || cd.isAsleep()) {
                        continue;
                    }
                    if (cd.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard) {
                        continue; // on duty, not chatting in a shop
                    }
                    if (e.blockPosition().distSqr(shop) > AT_SHOP_SQR) {
                        continue;
                    }
                    if (ErrandManager.hasErrand(e) || ConversationManager.isCitizenBusy(e)
                            || !ConversationManager.canCitizenSpeak(e)) {
                        continue;
                    }
                    out.add(e);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void start(MinecraftServer server, IBuilding market,
                              AbstractEntityCitizen a, AbstractEntityCitizen b, long pairKey,
                              boolean bothOnStaff) {
        PAIR_LAST.put(pairKey, System.currentTimeMillis());
        lastChatMs = System.currentTimeMillis();
        String an = a.getCitizenData().getName();
        String bn = b.getCitizenData().getName();
        String takings = "";
        try {
            int[] econ = TradePost.econStats(market);
            if (econ != null && econ.length >= 2) {
                takings = " So far the shop has sold " + econ[0] + " item(s) for " + econ[1] + " in takings.";
            }
        } catch (Throwable ignored) {
        }
        String topic = " Pass the quiet time together: gossip about the customers, what is selling and what is "
                + "not, prices, the state of the shelves, or just the day." + takings;
        boolean acrossTheWay = a.distanceToSqr(b) > 8.0 * 8.0;
        String whereA = bothOnStaff
                ? (acrossTheWay ? " is minding the other shop across the way - close enough to call over to."
                                : " is behind the counter with you.")
                : " is here in the shop with you.";
        String whereB = bothOnStaff
                ? (acrossTheWay ? " is minding the other shop across the way - close enough to call over to."
                                : " is behind the counter with you.")
                : " is minding it, right here with you.";
        memory(a, "You are minding the shop and it is empty - no customer in and none on the way - and " + bn
                + whereA + " You stay at your own counter while you talk." + topic);
        memory(b, "The shop is empty - no customer in and none on the way - and " + an
                + whereB + " You stay where you are while you talk." + topic);
        try {
            C2cAudioFollower.expectStationary(a, b);
            CitizenConversation conversation = new CitizenConversation(server, List.of(a, b));
            active = new Active(conversation, market, market.getColony().getID(), a, b);
            conversation.performConversation();
            ColonistErrands.LOGGER.info("[Shop] {} and {} chat behind the counter - no customers right now", an, bn);
        } catch (Throwable t) {
            active = null;
            ColonistErrands.LOGGER.warn("[Shop] Could not start the counter chat", t);
        }
    }

    private static void memory(AbstractEntityCitizen c, String event) {
        try {
            ((CitizenDataMemoryExtended) c.getCitizenData()).mc_talking$getOrInitializeMemory().addEvent(event);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        PAIR_LAST.clear();
        lastChatMs = 0;
        active = null;
    }
}
