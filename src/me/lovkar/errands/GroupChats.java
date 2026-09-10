package me.lovkar.errands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.tc.PairChats;
import me.lovkar.errands.tc.Talk;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionHandle;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionPolicy;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import net.minecraft.server.MinecraftServer;

/**
 * Lovkar's question: can more than two of them talk at once?
 * <p>
 * With Talking Colonists 2.0, yes. Errands 2.x could only fake it - mc_talking's two ways of
 * running a citizen conversation were both built for exactly two people, so a huddle went round
 * the circle as three separate two-way chats. 2.0 has <em>controlled sessions</em>: one
 * conversation with any number of participants, one speaker at a time, a shared transcript
 * everybody's next line is grounded in, and an automatic floor (round-robin, nobody speaks twice
 * in a row, capped in turns and minutes) when the caller delegates it. That is a huddle.
 * <p>
 * Errands still owns everything around the words: who stands together (three citizens with
 * nothing better to do, within a few blocks, a player close enough to hear), the memory that
 * gives the chat its footing, keeping the three in place facing each other while it runs, and
 * calling it off when one walks away or the player joins in. It is rare by design: one huddle
 * every quarter of an hour at most, the same three people at most every three quarters.
 */
public final class GroupChats {

    private GroupChats() {
    }

    /** How close the three have to be standing to count as one group. */
    private static final double HUDDLE_DIST_SQR = 7.0 * 7.0;
    /** ...and how far one may drift before the round is called off. */
    private static final double SPLIT_DIST_SQR = 14.0 * 14.0;
    /** No point spending a conversation where nobody can hear it. */
    private static final double PLAYER_RANGE = 26.0;

    private static final long GLOBAL_COOLDOWN_MS = 15 * 60_000L;
    private static final long TRIO_COOLDOWN_MS = 45 * 60_000L;

    /** Six turns - two each - and three minutes of scheduling: a huddle, not a council. */
    private static final int TURNS = 6;
    private static final Duration DURATION = Duration.ofMinutes(3);
    private static final int RESPONSE_TOKENS = 256;
    /** However badly it goes, a round is over after this (server ticks: a paused game does not count). */
    private static final int ROUND_MAX_TICKS = 8 * 60 * 20;
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
        final List<AbstractEntityCitizen> circle; // exactly three
        final int startedTick;
        ControlledConversationSession session;
        AutonomousDiscussionHandle discussion;
        volatile boolean over;

        Round(MinecraftServer server, int colonyId, List<AbstractEntityCitizen> circle) {
            this.server = server;
            this.colonyId = colonyId;
            this.circle = circle;
            this.startedTick = server.getTickCount();
        }
    }

    public static void tick(MinecraftServer server) {
        Round cur = round;
        if (cur != null) {
            if (server.getTickCount() % 10 == 0) {
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
                if (Talk.isTalkingToPlayer(c)) {
                    endRound(cur, "the player joined in - they come first");
                    return;
                }
            }
            AutonomousDiscussionHandle discussion = cur.discussion;
            if (discussion != null) {
                AutonomousDiscussionHandle.State state = discussion.state();
                if (state == AutonomousDiscussionHandle.State.COMPLETED) {
                    endRound(cur, "the three of them had all had their say");
                    return;
                }
                if (state == AutonomousDiscussionHandle.State.PAUSED) {
                    // The core pauses rather than retries: a player barged in, or the slots ran out.
                    endRound(cur, "the conversation was interrupted ("
                            + discussion.pauseReason().map(Enum::name).orElse("paused") + ")");
                    return;
                }
            }
            // Keep them together: they stand in a triangle, each looking at the next.
            for (int i = 0; i < 3; i++) {
                AbstractEntityCitizen a = cur.circle.get(i);
                AbstractEntityCitizen b = cur.circle.get((i + 1) % 3);
                if (a.distanceToSqr(b) > SPLIT_DIST_SQR) {
                    endRound(cur, "they drifted apart");
                    return;
                }
                PairChats.freeze(a, b);
            }
        } catch (Throwable t) {
            endRound(cur, "something went wrong");
        }
    }

    private static void startRound(Round cur) {
        AbstractEntityCitizen a = cur.circle.get(0);
        AbstractEntityCitizen b = cur.circle.get(1);
        AbstractEntityCitizen c = cur.circle.get(2);
        String an = a.getCitizenData().getName();
        String bn = b.getCitizenData().getName();
        String cn = c.getCitizenData().getName();

        String together = " The three of you are stood together in a group, talking. Keep each thing you say "
                + "short - the others want a word too - and answer what was just said rather than starting "
                + "a new subject each time.";
        memory(a, "You have fallen into conversation with " + bn + " and " + cn + "." + together
                + " Start it off: something on your mind, the colony, the work, the day.");
        memory(b, "You have fallen into conversation with " + an + " and " + cn + "." + together);
        memory(c, "You have fallen into conversation with " + an + " and " + bn + "." + together);

        try {
            cur.session = CitizenConversationService.createControlledSession(cur.server, cur.circle,
                    "A chat between neighbours stood together: something on your mind, the colony, the work, the day. "
                            + "Short turns; answer each other.",
                    ControlledConversationOptions.noAddonTools());
            cur.discussion = cur.session.delegateAutonomousDiscussion(
                    new AutonomousDiscussionPolicy(TURNS, DURATION, RESPONSE_TOKENS));
            cur.discussion.completion().thenAccept(reason -> {
                if (reason == AutonomousDiscussionHandle.CompletionReason.STOPPED) {
                    return; // we stopped it ourselves - endRound already said why
                }
                endRound(cur, "the conversation ran its course (" + reason + ")");
            });
            ColonistErrands.LOGGER.info("[Group] {}, {} and {} are stood together - a three-way it is ({} turns at most)",
                    an, bn, cn, TURNS);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Group] Could not start the huddle", t);
            endRound(cur, "the conversation would not start");
        }
    }

    private static void endRound(Round cur, String why) {
        synchronized (GroupChats.class) {
            if (cur.over) {
                return;
            }
            cur.over = true;
            if (round == cur) {
                round = null;
            }
        }
        try {
            if (cur.discussion != null) {
                cur.discussion.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cur.session != null) {
                cur.session.end(ControlledConversationSession.EndReason.COMPLETED);
            }
        } catch (Throwable ignored) {
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
                    if (!Talk.hasPlayerNearby(a, PLAYER_RANGE)) {
                        noPlayer++;
                        continue; // nobody around to hear it
                    }
                    long key = trioKey(a, b, c);
                    Long last = TRIO_LAST.get(key);
                    if (last != null && System.currentTimeMillis() - last < TRIO_COOLDOWN_MS) {
                        onCooldown++;
                        continue;
                    }
                    if (!Talk.hasAmbientCapacity(1)) {
                        whyNot("no free agent slot - somebody else is talking");
                        return false;
                    }
                    TRIO_LAST.put(key, System.currentTimeMillis());
                    lastRoundMs = System.currentTimeMillis();
                    Round started = new Round(server, colony.getID(), List.of(a, b, c));
                    round = started;
                    startRound(started);
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
     * Awake, off duty, not on an errand and not already talking. The core's per-citizen
     * cooldown is deliberately NOT a bar here: a huddle is rare by its own cooldowns, and
     * controlled turns are not subject to the ambient cooldown anyway - insisting on it
     * meant that near a player, where mumbles and greetings keep everybody on cooldown,
     * no huddle ever began.
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
            if (ErrandManager.hasErrand(e) || Talk.isBusy(e) || !PairChats.isFreeToChat(e)) {
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
        Talk.remember(c, event);
    }

    public static void clearAll() {
        Round cur = round;
        if (cur != null) {
            endRound(cur, "the server is stopping");
        }
        TRIO_LAST.clear();
        lastRoundMs = 0;
        round = null;
    }
}
