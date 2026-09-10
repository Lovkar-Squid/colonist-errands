package me.lovkar.errands.tc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.HospitalCheck;
import me.lovkar.errands.JobChatPolicy;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationHandle;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/**
 * Citizen-to-citizen chats, started and chaperoned - Lovkar's design: two chatting citizens stand
 * TOGETHER while they talk, not wander off while their voices play into empty air.
 *
 * <p>Errands 2.x built these on mc_talking's {@code CitizenConversation} and had to follow the
 * audio channel around, watch the stream drain, and re-mark the pair busy while the last words
 * played. Talking Colonists 2.0 owns all of that: {@code createPairConversation} hands back a
 * handle whose state goes GENERATING, PLAYING_AUDIO, ENDED, the voice follows the speaker, and the
 * pair count as busy until the audio really is over. What is left for Errands is the part that
 * was always ours: who chats (the job policy), the memories that give a chat its subject, and the
 * chaperoning - far apart they walk toward each other, together they stand and face each other;
 * WALKER professions (patrol buddies, couriers) stroll and talk; a pair a caller declared
 * stationary (two shopkeepers across a street) only turn to face one another.</p>
 */
public final class PairChats {

    private PairChats() {
    }

    /** One chat in progress. */
    public static final class Chat {
        public final AbstractEntityCitizen a;
        public final AbstractEntityCitizen b;
        final CitizenConversationHandle handle;
        final boolean stationary;
        boolean walking;
        boolean everTogether;
        final long startedAt = System.currentTimeMillis();
        volatile CitizenConversationHandle.State state = CitizenConversationHandle.State.READY;
        volatile boolean over;

        Chat(final AbstractEntityCitizen a, final AbstractEntityCitizen b, final CitizenConversationHandle handle,
             final boolean stationary, final boolean walking) {
            this.a = a;
            this.b = b;
            this.handle = handle;
            this.stationary = stationary;
            this.walking = walking;
        }

        /** True once the core reported the conversation ended (audio included) or we gave up on it. */
        public boolean isOver() {
            return over || state == CitizenConversationHandle.State.ENDED;
        }

        public boolean involves(final UUID citizenId) {
            return citizenId != null && (citizenId.equals(a.getUUID()) || citizenId.equals(b.getUUID()));
        }
    }

    private static final List<Chat> CHATS = new CopyOnWriteArrayList<>();
    /** citizen -> [partnerMost, partnerLeast, timeMs], for back_to_work after the chat is already over. */
    private static final Map<UUID, long[]> RECENT_PARTNER = new ConcurrentHashMap<>();

    private static final double TOGETHER_DIST_SQR = 5.0 * 5.0;
    private static final double SPLIT_DIST_SQR = 32.0 * 32.0;
    private static final double INITIAL_MAX_DIST_SQR = 64.0 * 64.0;
    private static final double WALKING_DRIFT_SQR = 12.0 * 12.0;
    private static final long MAX_AGE_MS = 10 * 60_000L;
    private static final long PARTNER_MEMORY_MS = 3 * 60_000L;

    /**
     * Start a chat between two citizens. The caller has already checked they may talk
     * ({@link Talk#canChat}, {@link #isFreeToChat}) and given each a memory of why.
     *
     * @param stationary they talk from where they stand (across a counter, a street) - never walked together
     * @return the chat, or null if the core would not start it
     */
    public static Chat start(final MinecraftServer server, final AbstractEntityCitizen a, final AbstractEntityCitizen b,
                             final boolean stationary) {
        if (server == null || a == null || b == null || a == b) {
            return null;
        }
        try {
            final CitizenConversationHandle handle = CitizenConversationService.createPairConversation(server, a, b);
            final boolean walking = !stationary
                    && JobChatPolicy.of(a.getCitizenData()) == JobChatPolicy.Policy.WALKER
                    && JobChatPolicy.of(b.getCitizenData()) == JobChatPolicy.Policy.WALKER;
            final Chat chat = new Chat(a, b, handle, stationary, walking);
            handle.setStateListener(state -> {
                chat.state = state;
                if (state == CitizenConversationHandle.State.ENDED) {
                    CHATS.remove(chat);
                }
            });
            CHATS.add(chat);
            rememberPartners(a, b);
            handle.start();
            ColonistErrands.LOGGER.info("[C2C] {} and {} start talking ({})", name(a), name(b),
                    stationary ? "from where they stand" : walking ? "strolling together" : "standing together");
            return chat;
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.warn("[C2C] could not start a chat between {} and {}", name(a), name(b), t);
            return null;
        }
    }

    /** Ask the pair to finish after the current line (the core's graceful end). */
    public static boolean end(final Chat chat) {
        if (chat == null || chat.isOver()) {
            return false;
        }
        return Talk.endGracefully(chat.a) | Talk.endGracefully(chat.b);
    }

    /** Stop the chat now. */
    public static void cancel(final Chat chat, final String why) {
        if (chat == null) {
            return;
        }
        CHATS.remove(chat);
        if (chat.over) {
            return;
        }
        chat.over = true;
        try {
            chat.handle.cancel();
        } catch (final Throwable ignored) {
        }
        if (why != null) {
            ColonistErrands.LOGGER.info("[C2C] {} and {} stop talking - {}", name(chat.a), name(chat.b), why);
        }
    }

    /** Aborts any chat this citizen is part of. true if one was running. */
    public static boolean abortFor(final UUID citizenId) {
        for (final Chat chat : CHATS) {
            if (chat.involves(citizenId)) {
                cancel(chat, "ordered back to work");
                return true;
            }
        }
        return false;
    }

    /** Is this citizen in one of our chats right now? */
    public static boolean isChatting(final UUID citizenId) {
        for (final Chat chat : CHATS) {
            if (chat.involves(citizenId) && !chat.isOver()) {
                return true;
            }
        }
        return false;
    }

    public static void tick(final MinecraftServer server) {
        if (CHATS.isEmpty()) {
            return;
        }
        final int tickCount = server.getTickCount();
        final long now = System.currentTimeMillis();
        for (final Chat chat : CHATS) {
            try {
                if (chat.isOver()) {
                    CHATS.remove(chat);
                    continue;
                }
                if (now - chat.startedAt > MAX_AGE_MS) {
                    cancel(chat, "ten minutes is enough for anyone");
                    continue;
                }
                final AbstractEntityCitizen a = chat.a;
                final AbstractEntityCitizen b = chat.b;
                if (a.isRemoved() || b.isRemoved() || !a.isAlive() || !b.isAlive()) {
                    cancel(chat, "one of them is gone");
                    continue;
                }
                if (Talk.isTalkingToPlayer(a) || Talk.isTalkingToPlayer(b)) {
                    // The player addressed one of them - the player comes first. The core has
                    // already ended the chat for that citizen; make sure the other stops too.
                    cancel(chat, "the player is talking to one of them");
                    continue;
                }
                final double d2 = a.distanceToSqr(b);
                if (d2 <= TOGETHER_DIST_SQR) {
                    chat.everTogether = true;
                }
                if (chat.stationary) {
                    if (d2 > SPLIT_DIST_SQR) {
                        cancel(chat, "stationary pair moved apart");
                        continue;
                    }
                    freeze(a, b);
                    freeze(b, a);
                } else if (!chat.everTogether) {
                    if (d2 > INITIAL_MAX_DIST_SQR) {
                        cancel(chat, "too far apart to ever meet");
                        continue;
                    }
                    if (chat.walking && now - chat.startedAt > 30_000L) {
                        chat.walking = false; // strolling routes never met - bring them together
                    }
                    if (!chat.walking && tickCount % 10 == 0) {
                        walkQuietly(a, b);
                        walkQuietly(b, a);
                    }
                } else if (chat.walking) {
                    if (d2 > WALKING_DRIFT_SQR) {
                        chat.walking = false;
                        ColonistErrands.LOGGER.info("[C2C] Walking pair drifted - they stop to finish the chat");
                    }
                } else if (d2 > SPLIT_DIST_SQR) {
                    cancel(chat, "participants got separated");
                } else if (d2 > TOGETHER_DIST_SQR) {
                    if (tickCount % 10 == 0) {
                        walkQuietly(a, b);
                        walkQuietly(b, a);
                    }
                } else {
                    freeze(a, b);
                    freeze(b, a);
                }
            } catch (final Throwable t) {
                CHATS.remove(chat);
            }
        }
    }

    private static void walkQuietly(final AbstractEntityCitizen who, final AbstractEntityCitizen to) {
        try {
            EntityNavigationUtils.walkToPos(who, to.blockPosition(), 2, true);
        } catch (final Throwable ignored) {
        }
    }

    /** Stand still and look at the partner (the same per-tick trick the core uses for player conversations). */
    public static void freeze(final AbstractEntityCitizen who, final AbstractEntityCitizen lookTarget) {
        try {
            if (!who.getNavigation().isDone()) {
                who.getNavigation().stop();
            }
            who.getLookControl().setLookAt((Entity) lookTarget, 30.0f, 30.0f);
        } catch (final Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ partners (back_to_work)

    private static void rememberPartners(final AbstractEntityCitizen a, final AbstractEntityCitizen b) {
        try {
            final UUID ia = a.getUUID();
            final UUID ib = b.getUUID();
            final long now = System.currentTimeMillis();
            RECENT_PARTNER.put(ia, new long[] {ib.getMostSignificantBits(), ib.getLeastSignificantBits(), now});
            RECENT_PARTNER.put(ib, new long[] {ia.getMostSignificantBits(), ia.getLeastSignificantBits(), now});
        } catch (final Throwable ignored) {
        }
    }

    /** The citizen's current or recent (3 min) chat partner, if known. */
    public static UUID partnerOf(final UUID citizenId) {
        final long[] memo = RECENT_PARTNER.get(citizenId);
        if (memo == null || System.currentTimeMillis() - memo[2] > PARTNER_MEMORY_MS) {
            return null;
        }
        return new UUID(memo[0], memo[1]);
    }

    // ------------------------------------------------------------------ who may chat

    /**
     * Lovkar's rule v3, per job (JobChatPolicy): CHATTY professions chat during work, WALKER
     * professions (guards, couriers) chat while on the move, FOCUSED ones only when idle at the
     * job. Guards never gossip on our escort/defense duty or while the colony is raided; a builder
     * with a claimed work order and a courier with deliveries queued are working for the whole
     * colony and do not stop for a chat; a patient under the healer's care stays put.
     */
    public static boolean isFreeToChat(final AbstractEntityCitizen citizen) {
        try {
            if (partnerOf(citizen.getUUID()) != null) {
                return false; // one chat per ~3 minutes per citizen - no gossip chains
            }
            final ICitizenData data = citizen.getCitizenData();
            if (data == null || data.getJob() == null) {
                return true;
            }
            if (data.getJob() instanceof com.minecolonies.core.colony.jobs.JobBuilder && data.getWorkBuilding() != null) {
                try {
                    final BlockPos hut = data.getWorkBuilding().getPosition();
                    for (final var wo : data.getColony().getWorkManager().getWorkOrders().values()) {
                        if (wo.isClaimed() && hut.equals(wo.getClaimedBy())) {
                            return false;
                        }
                    }
                } catch (final Throwable ignored) {
                }
            }
            if (data.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman dman) {
                try {
                    if (dman.getCurrentTask() != null || !dman.getTaskQueue().isEmpty()) {
                        return false;
                    }
                } catch (final Throwable ignored) {
                }
            }
            if (ErrandManager.hasErrand(citizen)) {
                return false; // mid-errand (fetching, delivering, guiding...) - never chat
            }
            if (HospitalCheck.underCare(citizen)) {
                return false;
            }
            if (data.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard) {
                if (ErrandManager.isOnMilitaryDuty(citizen)) {
                    return false;
                }
                try {
                    if (data.getColony() != null && data.getColony().getRaiderManager().isRaided()) {
                        return false; // battle stations, no gossip
                    }
                } catch (final Throwable ignored) {
                }
            }
            return switch (JobChatPolicy.of(data)) {
                case CHATTY, WALKER -> true;
                case FOCUSED -> {
                    boolean idle = false;
                    try {
                        idle = data.isIdleAtJob();
                    } catch (final Throwable ignored) {
                    }
                    yield idle;
                }
            };
        } catch (final Throwable t) {
            return true;
        }
    }

    public static void clearAll() {
        for (final Chat chat : CHATS) {
            cancel(chat, null);
        }
        CHATS.clear();
        RECENT_PARTNER.clear();
    }

    private static String name(final AbstractEntityCitizen c) {
        try {
            return c.getCitizenData().getName();
        } catch (final Throwable t) {
            return "citizen";
        }
    }
}
