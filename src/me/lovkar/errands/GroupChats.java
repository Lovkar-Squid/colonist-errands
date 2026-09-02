package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's question: can more than two of them talk at once?
 * <p>
 * Not in one voice, and the reason is worth writing down so nobody tries again.
 * mc_talking has two ways of running a citizen-to-citizen conversation, and both
 * are built for exactly two people:
 * <ul>
 *   <li><b>FLASH_TTS</b> renders the dialogue with Gemini's multi-speaker TTS,
 *       which accepts at most <b>two</b> speaker voices. A third name in the
 *       transcript has no voice to be read in.</li>
 *   <li><b>LIVE_WEBSOCKETS</b> wires two live sessions together as peers - each
 *       one's transcript is fed to the other, and one holds its audio while the
 *       other speaks. The wiring is one-to-one; there is no third socket.</li>
 * </ul>
 * So a huddle is built the way people actually stand in one: three of them
 * together, and the conversation goes round the circle - A with B, then B turns to
 * C, then C rounds it off with A. Each leg is a real two-way dialogue, and because
 * mc_talking writes each pair a memory of what they just discussed, the next leg
 * carries on from the last rather than starting over.
 * <p>
 * Two mc_talking rules have to be worked around deliberately: a citizen gets a
 * cooldown ({@code citizenCooldownSeconds}, two minutes by default) the moment a
 * session ends, which would stop the middle person turning to the next neighbour -
 * so the cooldown is lifted for the pair about to speak, and for them only. And
 * each leg needs two of the concurrent agent slots, so a leg that cannot get them
 * is skipped rather than allowed to evict somebody else's conversation.
 * <p>
 * A round is three API conversations instead of one, so it is rare by design: one
 * huddle every quarter of an hour at most, the same three people at most every
 * three quarters, and only where a player is close enough to hear it.
 */
public final class GroupChats {

    private GroupChats() {
    }

    /** How close the three have to be standing to count as one group. */
    private static final double HUDDLE_DIST_SQR = 7.0 * 7.0;
    /** ...and how far the pair may drift before the round is called off. */
    private static final double SPLIT_DIST_SQR = 14.0 * 14.0;
    /** No point spending three conversations where nobody can hear them. */
    private static final double PLAYER_RANGE = 26.0;

    private static final long GLOBAL_COOLDOWN_MS = 15 * 60_000L;
    private static final long TRIO_COOLDOWN_MS = 45 * 60_000L;

    /**
     * Each leg is kept short - the point is that it goes round, not that it lingers.
     * Server ticks, not the wall clock: a paused game must not count against them.
     */
    private static final int LEG_WRAP_UP_TICKS = 75 * 20;
    private static final int LEG_END_TICKS = 105 * 20;
    private static final int LEG_HARD_TICKS = 150 * 20;
    /** A beat between legs, long enough for the memory of the last one to be written. */
    private static final int BETWEEN_LEGS_TICKS = 7 * 20;
    /** However badly it goes, a round is over after this. */
    private static final int ROUND_MAX_TICKS = 12 * 60 * 20;
    /** Looking for a group is cheap; the cooldowns are what keep rounds rare. */
    private static final int LOOK_EVERY_TICKS = 30 * 20;
    /** How often, at most, to say in the log why no huddle started. */
    private static final long WHY_NOT_EVERY_MS = 5 * 60_000L;

    private static long lastRoundMs = 0;
    private static long lastWhyNotMs = 0;
    private static final Map<Long, Long> TRIO_LAST = new ConcurrentHashMap<>();

    private static volatile Round round = null;

    private static final class Round {
        final MinecraftServer server;
        final int colonyId;
        final List<AbstractEntityCitizen> circle; // exactly three, in order
        final int startedTick;
        volatile int leg = 0;        // 0 -> (0,1), 1 -> (1,2), 2 -> (2,0)
        int legStartedTick = 0;
        /** Set on the server thread once a finished leg has been noticed; -1 = not yet. */
        int legEndedTick = -1;
        volatile CitizenConversation current = null;
        boolean wrapUpAsked = false;
        boolean endRequested = false;

        Round(MinecraftServer server, int colonyId, List<AbstractEntityCitizen> circle) {
            this.server = server;
            this.colonyId = colonyId;
            this.circle = circle;
            this.startedTick = server.getTickCount();
        }

        AbstractEntityCitizen speaker() {
            return circle.get(leg % 3);
        }

        AbstractEntityCitizen listener() {
            return circle.get((leg + 1) % 3);
        }

        AbstractEntityCitizen bystander() {
            return circle.get((leg + 2) % 3);
        }
    }

    public static void tick(MinecraftServer server) {
        Round cur = round;
        if (cur != null) {
            if (server.getTickCount() % 20 == 0) {
                advance(cur);
            }
            return;
        }
        if (server.getTickCount() % LOOK_EVERY_TICKS != 0) {
            return;
        }
        if (!Settings.groupChats()) {
            return;
        }
        if (System.currentTimeMillis() - lastRoundMs < GLOBAL_COOLDOWN_MS) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getRaiderManager().isRaided()) {
                    continue;
                }
                if (tryStartRound(server, colony)) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Running a round
    // ------------------------------------------------------------------

    private static void advance(Round cur) {
        try {
            int now = cur.server.getTickCount();
            if (now - cur.startedTick > ROUND_MAX_TICKS) {
                endRound(cur, "it had gone on long enough");
                return;
            }
            for (AbstractEntityCitizen c : cur.circle) {
                if (c == null || !c.isAlive() || c.isRemoved()) {
                    endRound(cur, "one of them is gone");
                    return;
                }
            }
            CitizenConversation running = cur.current;
            if (running == null) {
                // Between legs. ENDED means the last part is GENERATED, not heard - its
                // audio can still be playing for half a minute, during which the two who
                // spoke are kept busy by the chaperone. Wait for that, then a short beat.
                if (anyBusy(cur)) {
                    cur.legEndedTick = -1;
                    return;
                }
                if (cur.legEndedTick < 0) {
                    cur.legEndedTick = now;
                } else if (now - cur.legEndedTick >= BETWEEN_LEGS_TICKS) {
                    startLeg(cur);
                }
                return;
            }

            // A leg is running: keep them together, and wind it up in good time.
            AbstractEntityCitizen a = cur.speaker();
            AbstractEntityCitizen b = cur.listener();
            if (a.distanceToSqr(b) > SPLIT_DIST_SQR) {
                endRound(cur, "they drifted apart");
                return;
            }
            int age = now - cur.legStartedTick;
            if (!cur.wrapUpAsked && age > LEG_WRAP_UP_TICKS) {
                cur.wrapUpAsked = true;
                ChatWindDown.askToWrapUp(a, b, "Round this part of the conversation off now - one last thought "
                        + "and hand it over. " + cur.bystander().getCitizenData().getName()
                        + " is stood right there waiting to pick it up.");
            }
            if (!cur.endRequested && age > LEG_END_TICKS) {
                cur.endRequested = true;
                if (!ChatWindDown.endAfterThisLine(a, b)) {
                    return; // Flash/TTS: one rendered clip, it ends when it ends
                }
            }
            if (age > LEG_HARD_TICKS) {
                try {
                    running.abort();
                } catch (Throwable ignored) {
                }
                legFinished(cur, cur.leg, running);
            }
        } catch (Throwable t) {
            endRound(cur, "something went wrong");
        }
    }

    private static boolean anyBusy(Round cur) {
        for (AbstractEntityCitizen c : cur.circle) {
            try {
                if (ConversationManager.isCitizenBusy(c)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void startLeg(Round cur) {
        // legFinished() runs on mc_talking's own thread, so the round can be over
        // between the tick deciding to start a leg and this line.
        if (round != cur || cur.leg >= 3) {
            return;
        }
        AbstractEntityCitizen a = cur.speaker();
        AbstractEntityCitizen b = cur.listener();
        AbstractEntityCitizen c = cur.bystander();
        String an = a.getCitizenData().getName();
        String bn = b.getCitizenData().getName();
        String cn = c.getCitizenData().getName();

        // The pair about to speak have just finished a session with someone else in
        // this same circle, so mc_talking's cooldown would block them. Lift it for
        // these two only - everybody else in the colony keeps theirs.
        try {
            ConversationManager.forceRemoveCooldown(a);
            ConversationManager.forceRemoveCooldown(b);
        } catch (Throwable ignored) {
        }

        if (!ConversationManager.canCitizenSpeak(a) || !ConversationManager.canCitizenSpeak(b)
                || ConversationManager.isCitizenBusy(a) || ConversationManager.isCitizenBusy(b)) {
            endRound(cur, bn + " could not pick the conversation up");
            return;
        }
        if (!ConversationManager.hasLowPriorityCapacity(2)) {
            endRound(cur, "no free slots for the next part");
            return;
        }

        String whoIsThere = " The three of you - you, " + bn + " and " + cn + " - are stood together in a group.";
        if (cur.leg == 0) {
            memory(a, "You have fallen into conversation with " + bn + "." + whoIsThere
                    + " Start it off: something on your mind, the colony, the work, the day. Keep it short - "
                    + cn + " will want a word too.");
            memory(b, "You have fallen into conversation with " + an + "." + whoIsThere
                    + " Talk with them for a moment; you will turn to " + cn + " straight after.");
        } else {
            memory(a, "You have just been talking with " + cn + ", and now " + bn
                    + " picks it up with you." + whoIsThere
                    + " Carry on from what was just said rather than starting a new subject.");
            memory(b, "You have been listening to " + an + " and " + cn
                    + ", and now it is your turn with " + an + "." + whoIsThere
                    + " Pick up what they were just saying and put your own view on it.");
        }

        try {
            C2cAudioFollower.expectStationary(a, b);
            CitizenConversation conversation = new CitizenConversation(cur.server, List.of(a, b));
            final int legIndex = cur.leg;
            cur.current = conversation;
            cur.legStartedTick = cur.server.getTickCount();
            cur.legEndedTick = -1;
            cur.wrapUpAsked = false;
            cur.endRequested = false;
            conversation.setOnStateChanged(state -> {
                if (state == CitizenConversation.ConversationState.ENDED) {
                    legFinished(cur, legIndex, conversation);
                }
            });
            conversation.performConversation();
            ColonistErrands.LOGGER.info("[Group] Part {} of 3 - {} and {} talk, {} is listening in",
                    cur.leg + 1, an, bn, cn);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Group] Could not start part {} of the huddle", cur.leg + 1, t);
            endRound(cur, "the conversation would not start");
        }
    }

    /** Called from mc_talking's own state callback - may fire twice, and off-thread. */
    private static void legFinished(Round cur, int legIndex, CitizenConversation which) {
        synchronized (GroupChats.class) {
            if (round != cur || cur.leg != legIndex || cur.current != which) {
                return; // already moved on
            }
            cur.current = null;
            cur.leg = legIndex + 1;
        }
        if (cur.leg >= 3) {
            endRound(cur, "the three of them had all had their say");
        }
    }

    private static void endRound(Round cur, String why) {
        synchronized (GroupChats.class) {
            if (round != cur) {
                return;
            }
            round = null;
        }
        CitizenConversation running = cur.current;
        cur.current = null;
        if (running != null) {
            try {
                running.abort();
            } catch (Throwable ignored) {
            }
        }
        ColonistErrands.LOGGER.info("[Group] Huddle over - {}", why);
    }

    // ------------------------------------------------------------------
    // Finding a group
    // ------------------------------------------------------------------

    private static boolean tryStartRound(MinecraftServer server, IColony colony) {
        List<AbstractEntityCitizen> free = new ArrayList<>();
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                AbstractEntityCitizen e = freeToTalk(cd);
                if (e != null) {
                    free.add(e);
                }
            }
        } catch (Throwable t) {
            return false;
        }
        if (free.size() < 3) {
            return false;
        }
        int trios = 0, noPlayer = 0, onCooldown = 0;
        for (int i = 0; i < free.size(); i++) {
            for (int j = i + 1; j < free.size(); j++) {
                if (free.get(i).distanceToSqr(free.get(j)) > HUDDLE_DIST_SQR) {
                    continue;
                }
                for (int k = j + 1; k < free.size(); k++) {
                    AbstractEntityCitizen a = free.get(i);
                    AbstractEntityCitizen b = free.get(j);
                    AbstractEntityCitizen c = free.get(k);
                    if (a.distanceToSqr(c) > HUDDLE_DIST_SQR || b.distanceToSqr(c) > HUDDLE_DIST_SQR) {
                        continue;
                    }
                    trios++;
                    if (!ConversationManager.hasPlayerNearby(a, server, PLAYER_RANGE)) {
                        noPlayer++;
                        continue; // nobody around to hear it
                    }
                    long key = trioKey(a, b, c);
                    Long last = TRIO_LAST.get(key);
                    if (last != null && System.currentTimeMillis() - last < TRIO_COOLDOWN_MS) {
                        onCooldown++;
                        continue;
                    }
                    if (!ConversationManager.hasLowPriorityCapacity(2)) {
                        whyNot("no free agent slots - somebody else is talking");
                        return false;
                    }
                    TRIO_LAST.put(key, System.currentTimeMillis());
                    lastRoundMs = System.currentTimeMillis();
                    Round started = new Round(server, colony.getID(), List.of(a, b, c));
                    round = started;
                    ColonistErrands.LOGGER.info("[Group] {}, {} and {} are stood together - a three-way it is",
                            a.getCitizenData().getName(), b.getCitizenData().getName(), c.getCitizenData().getName());
                    startLeg(started);
                    return true;
                }
            }
        }
        if (trios > 0) {
            whyNot(trios + " group(s) of three stood together, " + noPlayer + " with no player near enough to hear, "
                    + onCooldown + " that huddled recently");
        }
        return false;
    }

    /** Once in a while, say why nothing happened - otherwise a quiet feature is indistinguishable from a broken one. */
    private static void whyNot(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastWhyNotMs < WHY_NOT_EVERY_MS) {
            return;
        }
        lastWhyNotMs = now;
        ColonistErrands.LOGGER.info("[Group] No huddle this time: {}", reason);
    }

    /**
     * Awake, off duty, not on an errand and not already talking. mc_talking's
     * per-citizen cooldown is deliberately NOT a bar here: a huddle is rare by its
     * own cooldowns, and {@link #startLeg} lifts the citizen cooldown for the pair
     * anyway - insisting on it for the first pair only meant that near a player,
     * where mumbles and greetings keep everybody on cooldown, no huddle ever began.
     */
    private static AbstractEntityCitizen freeToTalk(ICitizenData cd) {
        try {
            AbstractEntityCitizen e = cd.getEntity().orElse(null);
            if (e == null || !e.isAlive() || e.isSleeping() || cd.isAsleep()) {
                return null;
            }
            if (cd.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard) {
                return null; // on duty
            }
            if (ErrandManager.hasErrand(e) || ConversationManager.isCitizenBusy(e)
                    || !C2cAudioFollower.isFreeToChat(e)) {
                return null;
            }
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long trioKey(AbstractEntityCitizen a, AbstractEntityCitizen b, AbstractEntityCitizen c) {
        int[] ids = {a.getId(), b.getId(), c.getId()};
        java.util.Arrays.sort(ids);
        return ((long) ids[0] << 42) ^ ((long) ids[1] << 21) ^ ids[2];
    }

    private static void memory(AbstractEntityCitizen c, String event) {
        try {
            ((CitizenDataMemoryExtended) c.getCitizenData()).mc_talking$getOrInitializeMemory().addEvent(event);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        TRIO_LAST.clear();
        lastRoundMs = 0;
        round = null;
    }
}
