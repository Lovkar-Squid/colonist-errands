package me.lovkar.errands;

import me.lovkar.errands.tc.PairChats;
import me.lovkar.errands.tc.Talk;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.ICivilianData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
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

    /**
     * How long a counter chat is meant to last, in server ticks - Lovkar paused the
     * game with a customer at the counter, and a wall clock would have counted the
     * pause as the pair refusing to stop. At three minutes Talking Colonists is asked
     * to end the chat after the current sentence (its graceful end); only if they are
     * still going well past that is it cut.
     */
    private static final int MAX_CHAT_TICKS = 3 * 60 * 20;
    /** ...and at this point it is cut regardless. */
    private static final int HARD_STOP_TICKS = MAX_CHAT_TICKS + 45 * 20;
    /**
     * A customer will not wait for a whole goodbye - one line to excuse themselves.
     * Live sessions need the current sentence to finish before the excuse can even
     * be generated; twelve seconds was never enough (log: "did not stop" at 14 s).
     * Shoppers browse the displays for a good while, so twenty-five is affordable.
     */
    private static final int CUSTOMER_GRACE_TICKS = 25 * 20;

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
        final PairChats.Chat conversation;
        final IBuilding market;
        final int colonyId;
        final AbstractEntityCitizen a;
        final AbstractEntityCitizen b;
        final int startedTick;
        /** Set once mc_talking has been told to close after the current sentence. */
        volatile boolean endRequested = false;
        /** The tick a customer first appeared on - negative while the shop is still empty. */
        volatile int customerSinceTick = -1;

        Active(PairChats.Chat conversation, IBuilding market, int colonyId,
               AbstractEntityCitizen a, AbstractEntityCitizen b, int startedTick) {
            this.conversation = conversation;
            this.market = market;
            this.colonyId = colonyId;
            this.a = a;
            this.b = b;
            this.startedTick = startedTick;
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

    /**
     * Runs every two seconds while a chat is on. Two things can end it - a customer
     * arriving, or the clock - and neither of them should chop a sentence in half if
     * it can be helped. Both go through the same three steps: ask them to finish,
     * then let mc_talking close after the current sentence, and only cut the audio
     * if they somehow carry on past all of that.
     */
    private static void watchActive(MinecraftServer server) {
        Active cur = active;
        if (cur == null) {
            return;
        }
        try {
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

            int now = server.getTickCount();
            int age = now - cur.startedTick;

            // --- somebody at the counter --------------------------------------
            if (customerAround(colony, cur.market)) {
                if (cur.customerSinceTick < 0) {
                    cur.customerSinceTick = now;
                    // Talking Colonists 2.0 has no way to slip a line into a running pair chat,
                    // but it can end one after the current sentence - so they break off cleanly.
                    boolean asked = PairChats.end(cur.conversation);
                    String note = asked
                            ? "A customer walked in while you were talking. You excused yourself and went back to "
                                    + "the counter. Business first."
                            : "A customer walked in while you were talking - you broke off and went back to the "
                                    + "counter. Business first.";
                    memory(cur.a, note);
                    memory(cur.b, note);
                    if (asked) {
                        ColonistErrands.LOGGER.info("[Shop] A customer came in - {} and {} are excusing themselves",
                                cur.a.getCitizenData().getName(), cur.b.getCitizenData().getName());
                    } else {
                        stop(cur, "a customer came in");
                        return;
                    }
                } else if (now - cur.customerSinceTick > CUSTOMER_GRACE_TICKS) {
                    stop(cur, "a customer came in and they did not stop");
                    return;
                }
                return; // the clock can wait - the counter cannot
            }
            cur.customerSinceTick = -1; // the customer left again before we cut in

            // --- the clock ----------------------------------------------------
            if (cur.conversation.isOver()) {
                finished(cur);
                return;
            }
            if (!cur.endRequested && age > MAX_CHAT_TICKS) {
                cur.endRequested = true;
                if (PairChats.end(cur.conversation)) {
                    ColonistErrands.LOGGER.info("[Shop] Counter chat closing after the current line");
                } else {
                    stop(cur, "it had gone on long enough");
                    return;
                }
            }
            if (age > HARD_STOP_TICKS) {
                stop(cur, "it ran well past its time and had to be cut");
            }
        } catch (Throwable t) {
            stop(cur, "something went wrong watching the shop");
        }
    }

    /** Cut it now. The last resort - the clock asks for a graceful end first. */
    private static void stop(Active cur, String why) {
        if (active == cur) {
            active = null;
        }
        PairChats.cancel(cur.conversation, null);
        ColonistErrands.LOGGER.info("[Shop] Counter chat ended - {}", why);
    }

    /** They finished by themselves - which, with the wind-down, is now the normal way. */
    private static void finished(Active cur) {
        if (active != cur) {
            return;
        }
        active = null;
        ColonistErrands.LOGGER.info("[Shop] Counter chat over - they finished it themselves");
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
                        if (!Talk.hasAmbientCapacity(2)) {
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
                    if (ErrandManager.hasErrand(e) || Talk.isBusy(e)) {
                        continue;
                    }
                    if (!Talk.canChat(e)) {
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
                    if (ErrandManager.hasErrand(e) || Talk.isBusy(e)
                            || !Talk.canChat(e)) {
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
            // Two shopkeepers call across to each other from their counters - a stationary pair.
            PairChats.Chat conversation = PairChats.start(server, a, b, true);
            if (conversation == null) {
                return;
            }
            active = new Active(conversation, market, market.getColony().getID(), a, b, server.getTickCount());
            ColonistErrands.LOGGER.info("[Shop] {} and {} chat behind the counter - no customers right now", an, bn);
        } catch (Throwable t) {
            active = null;
            ColonistErrands.LOGGER.warn("[Shop] Could not start the counter chat", t);
        }
    }

    private static void memory(AbstractEntityCitizen c, String event) {
        try {
            Talk.remember(c.getCitizenData(), event);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        PAIR_LAST.clear();
        lastChatMs = 0;
        active = null;
    }
}
