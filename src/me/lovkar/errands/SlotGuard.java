package me.lovkar.errands;

import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.GeminiWsClient;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * Lovkar's log, one session: eleven "Evicted slot ... to make room". mc_talking
 * has {@code maxConcurrentAgents} slots (four on his machine); a live
 * citizen-to-citizen conversation takes two, a mumble one. When they are all in
 * use and something else wants one, {@code claimSlot} does not say no - it
 * <b>evicts the oldest non-player session</b>, which closes it wherever it was in
 * a sentence. And {@code hasLowPriorityCapacity} counts those evictable sessions
 * as free, so every caller believes there is room. The upshot: a third random
 * conversation starting somewhere in the colony kills the one you are listening
 * to. That, more than anything, is "sometimes they are cut off mid-sentence".
 * <p>
 * The fix is a matter of priority, decided per caller:
 * <ul>
 *   <li><b>Small talk</b> - citizen-to-citizen conversations (random, family,
 *       shop, huddle) and idle mumbling - may only take a slot that is free or
 *       whose holder has gone quiet. If every slot is busy with someone still
 *       talking, the new conversation simply does not start.</li>
 *   <li><b>Urgent contact</b> - a citizen coming to the player with a need - and
 *       <b>guard threats</b> keep mc_talking's behaviour: they may evict chatter.
 *       Eviction then prefers a slot that has gone quiet over one mid-sentence.</li>
 *   <li><b>The player</b> talking to a citizen is untouched - the player always
 *       gets a slot.</li>
 * </ul>
 * "Small talk" is marked by the calling code itself, via {@link #enter}/{@link #exit}
 * around mc_talking's own methods (see ConversationManagerMixin and
 * CitizenConversationMixin), so this class never has to guess from a stack trace.
 */
public final class SlotGuard {

    private SlotGuard() {
    }

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static Field fWalking;

    /** The code that follows, on this thread, is starting small talk. */
    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    public static boolean smallTalk() {
        return DEPTH.get()[0] > 0;
    }

    /** Called once per server tick: a flagged region never spans ticks, so anything left is a leak. */
    public static void resetForTick() {
        DEPTH.get()[0] = 0;
    }

    /**
     * Is this slot holder actually in the middle of something? An open session
     * (talking, or about to), an urgent contact in progress, or a citizen already
     * walking over to the player all count. A closed session whose slot has not
     * been released yet does not - that one may go.
     */
    public static boolean isTalking(UUID id) {
        if (id == null) {
            return false;
        }
        try {
            GeminiWsClient c = ConversationManager.getClients().get(id);
            if (c != null && !c.isClosed()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (ConversationManager.isUrgentConversation(id)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (fWalking == null) {
                Class<?> h = Class.forName("me.sshcrack.mc_talking.handler.UrgentContactHandler");
                fWalking = h.getDeclaredField("walkingCitizens");
                fWalking.setAccessible(true);
            }
            Map<?, ?> walking = (Map<?, ?>) fWalking.get(null);
            if (walking != null && walking.containsKey(id)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** mc_talking's maxConcurrentAgents, read reflectively (its YACL config types are not on our compile path). */
    public static int maxAgents() {
        try {
            Class<?> cfgClass = Class.forName("me.sshcrack.mc_talking.config.McTalkingConfig");
            Object handler = cfgClass.getField("INSTANCE").get(null);
            Object cfg = handler.getClass().getMethod("instance").invoke(handler);
            return ((Number) cfgClass.getField("maxConcurrentAgents").get(cfg)).intValue();
        } catch (Throwable t) {
            return Integer.MAX_VALUE; // unknown - never claim the pool is full
        }
    }

    /** A non-player slot holder that has gone quiet, in the same (oldest-first) order mc_talking uses. */
    public static UUID quietVictim(Iterable<UUID> slots) {
        for (UUID id : slots) {
            try {
                if (ConversationManager.getPlayerForEntity(id) != null) {
                    continue;
                }
            } catch (Throwable ignored) {
            }
            if (!isTalking(id)) {
                return id;
            }
        }
        return null;
    }

    public static int countQuietNonPlayer(Iterable<UUID> slots) {
        int n = 0;
        for (UUID id : slots) {
            try {
                if (ConversationManager.getPlayerForEntity(id) != null) {
                    continue;
                }
            } catch (Throwable ignored) {
            }
            if (!isTalking(id)) {
                n++;
            }
        }
        return n;
    }
}
