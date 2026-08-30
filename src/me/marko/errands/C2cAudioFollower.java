package me.marko.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import me.sshcrack.mc_talking.McTalkingVoicechatPlugin;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chaperone for citizen-to-citizen (Flash/TTS) conversations - Marko's design:
 * two chatting citizens should stand TOGETHER while they talk, not wander off
 * while a static audio channel plays their dialogue into empty air.
 *
 * While a tracked conversation runs:
 *  - far apart (>5 blocks): both walk toward each other until they meet
 *  - together: both stand still and look at each other (same per-tick
 *    navigation-stop trick mc_talking uses for player conversations)
 *  - the locational audio channel is moved onto the speakers every few ticks
 *  - only if something teleports them >32 blocks apart (or a participant
 *    despawns) is the conversation aborted as a safety net
 *
 * Also remembers recent chat partners so the back_to_work tool can send BOTH
 * gossipers back to their jobs even after the chat already ended.
 */
public final class C2cAudioFollower {

    private C2cAudioFollower() {
    }

    private static final class Entry {
        final CitizenConversation conversation;
        final LocationalAudioChannel channel;
        final List<AbstractEntityCitizen> participants;
        boolean walkingChat;       // both are WALKER professions: chat on the move, no freezing
        boolean everTogether;      // they met at least once - safety splits apply only after this
        final long startedAt = System.currentTimeMillis();

        Entry(CitizenConversation conversation, LocationalAudioChannel channel,
              List<AbstractEntityCitizen> participants, boolean walkingChat) {
            this.conversation = conversation;
            this.channel = channel;
            this.participants = participants;
            this.walkingChat = walkingChat;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final Map<UUID, long[]> RECENT_PARTNER = new ConcurrentHashMap<>(); // citizen -> [partnerMost, partnerLeast, timeMs]
    private static final double TOGETHER_DIST_SQR = 5.0 * 5.0;
    private static final double SPLIT_DIST_SQR = 32.0 * 32.0;
    private static final double INITIAL_MAX_DIST_SQR = 64.0 * 64.0; // pair may start far apart - they walk to meet
    private static final double WALKING_DRIFT_SQR = 12.0 * 12.0;    // walking pair diverging -> stop and finish standing
    private static final long MAX_AGE_MS = 10 * 60_000;
    private static final long PARTNER_MEMORY_MS = 3 * 60_000;
    private static Field fState;

    /** Called from CitizenConversationMixin the moment the locational channel is created. */
    public static void register(Object conversation, LocationalAudioChannel channel, List<AbstractEntityCitizen> participants) {
        if (!(conversation instanceof CitizenConversation cc) || channel == null || participants == null) {
            return;
        }
        boolean walking = !participants.isEmpty();
        for (AbstractEntityCitizen c : participants) {
            try {
                if (c == null || JobChatPolicy.of(c.getCitizenData()) != JobChatPolicy.Policy.WALKER) {
                    walking = false;
                    break;
                }
            } catch (Throwable t) {
                walking = false;
                break;
            }
        }
        ENTRIES.add(new Entry(cc, channel, new ArrayList<>(participants), walking));
        rememberPartners(participants);
        ColonistErrands.LOGGER.info("[C2C] Chaperoning conversation ({} participants, {})", participants.size(),
                walking ? "walking chat - they stroll together" : "they will stand together while talking");
    }

    public static void tick(MinecraftServer server) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        int tickCount = server.getTickCount();
        long now = System.currentTimeMillis();
        for (Entry e : ENTRIES) {
            try {
                if (now - e.startedAt > MAX_AGE_MS || isEnded(e.conversation)) {
                    ENTRIES.remove(e);
                    continue;
                }
                List<AbstractEntityCitizen> alive = new ArrayList<>(2);
                for (AbstractEntityCitizen c : e.participants) {
                    if (c != null && !c.isRemoved()) {
                        alive.add(c);
                    }
                }
                if (alive.isEmpty()) {
                    abortQuietly(e, "all participants gone");
                    continue;
                }
                if (alive.size() >= 2) {
                    AbstractEntityCitizen a = alive.get(0);
                    AbstractEntityCitizen b = alive.get(1);
                    double d2 = a.distanceToSqr(b);
                    if (d2 <= TOGETHER_DIST_SQR) {
                        e.everTogether = true;
                    }
                    if (!e.everTogether) {
                        // The pair may be picked far apart (handler scans around the
                        // player) - first bring them together, never insta-abort.
                        if (d2 > INITIAL_MAX_DIST_SQR) {
                            abortQuietly(e, "too far apart to ever meet");
                            continue;
                        }
                        if (e.walkingChat && now - e.startedAt > 30_000) {
                            e.walkingChat = false; // strolling routes never met - bring them together
                        }
                        if (!e.walkingChat && tickCount % 10 == 0) {
                            walkQuietly(a, b);
                            walkQuietly(b, a);
                        }
                        // walking pairs stroll on their own; audio follows either way
                    } else if (e.walkingChat) {
                        // Patrol buddies / couriers stroll and talk. If their routes
                        // start diverging, they stop and finish the chat standing.
                        if (d2 > WALKING_DRIFT_SQR) {
                            e.walkingChat = false;
                            ColonistErrands.LOGGER.info("[C2C] Walking pair drifted - they stop to finish the chat");
                        }
                    } else if (d2 > SPLIT_DIST_SQR) {
                        abortQuietly(e, "participants got separated"); // safety net (teleports etc.)
                        continue;
                    } else if (d2 > TOGETHER_DIST_SQR) {
                        // Walk toward each other until they meet (again).
                        if (tickCount % 10 == 0) {
                            walkQuietly(a, b);
                            walkQuietly(b, a);
                        }
                    } else {
                        // Together: stand still, face each other (mc_talking's own trick).
                        freeze(a, b);
                        freeze(b, a);
                    }
                }
                if (tickCount % 5 == 0) {
                    AbstractEntityCitizen anchor = alive.get(0);
                    e.channel.updateLocation(McTalkingVoicechatPlugin.vcApi.createPosition(
                            anchor.getX(), anchor.getY() + 1.5, anchor.getZ()));
                }
            } catch (Throwable t) {
                ENTRIES.remove(e);
            }
        }
    }

    private static void walkQuietly(AbstractEntityCitizen who, AbstractEntityCitizen to) {
        try {
            EntityNavigationUtils.walkToPos(who, to.blockPosition(), 2, true);
        } catch (Throwable ignored) {
        }
    }

    private static void freeze(AbstractEntityCitizen who, AbstractEntityCitizen lookTarget) {
        try {
            if (!who.getNavigation().isDone()) {
                who.getNavigation().stop();
            }
            who.getLookControl().setLookAt((Entity) lookTarget, 30.0f, 30.0f);
        } catch (Throwable ignored) {
        }
    }

    private static void abortQuietly(Entry e, String why) {
        ENTRIES.remove(e);
        try {
            e.conversation.abort();
            ColonistErrands.LOGGER.info("[C2C] Stopped conversation audio ({})", why);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isEnded(CitizenConversation conversation) {
        try {
            if (fState == null) {
                fState = CitizenConversation.class.getDeclaredField("state");
                fState.setAccessible(true);
            }
            Object st = ((AtomicReference<?>) fState.get(conversation)).get();
            return st != null && "ENDED".equals(((Enum<?>) st).name());
        } catch (Throwable t) {
            return false; // fall back to MAX_AGE expiry
        }
    }

    // ------------------------------------------------------------------
    // Support for the back_to_work tool
    // ------------------------------------------------------------------

    private static void rememberPartners(List<AbstractEntityCitizen> participants) {
        if (participants.size() < 2) {
            return;
        }
        try {
            UUID a = participants.get(0).getUUID();
            UUID b = participants.get(1).getUUID();
            long now = System.currentTimeMillis();
            RECENT_PARTNER.put(a, new long[]{b.getMostSignificantBits(), b.getLeastSignificantBits(), now});
            RECENT_PARTNER.put(b, new long[]{a.getMostSignificantBits(), a.getLeastSignificantBits(), now});
        } catch (Throwable ignored) {
        }
    }

    /** Aborts any tracked conversation this citizen is part of. true if one was running. */
    public static boolean abortFor(UUID citizenId) {
        for (Entry e : ENTRIES) {
            for (AbstractEntityCitizen c : e.participants) {
                if (c != null && citizenId.equals(c.getUUID())) {
                    abortQuietly(e, "ordered back to work");
                    return true;
                }
            }
        }
        return false;
    }

    /** The citizen's current or recent (3 min) chat partner, if known. */
    public static UUID partnerOf(UUID citizenId) {
        long[] memo = RECENT_PARTNER.get(citizenId);
        if (memo == null || System.currentTimeMillis() - memo[2] > PARTNER_MEMORY_MS) {
            return null;
        }
        return new UUID(memo[0], memo[1]);
    }

    /**
     * Marko's rule v3, per-job (JobChatPolicy): CHATTY professions chat during
     * work, WALKER professions (guards, couriers) chat while on the move,
     * FOCUSED ones only when idle at the job. Guards never gossip while on our
     * escort/defense duty or while the colony is being raided.
     */
    public static boolean isFreeToChat(AbstractEntityCitizen citizen) {
        try {
            // One chat per ~3 minutes per citizen - no gossip chains (and no
            // "builders feel slower because they chat between every task").
            if (partnerOf(citizen.getUUID()) != null) {
                return false;
            }
            ICitizenData data = citizen.getCitizenData();
            if (data == null || data.getJob() == null) {
                return true;
            }
            // Builders waiting for materials count as "idle at job", but their
            // claimed work order means the colony is waiting on THEM - no chats.
            if (data.getJob() instanceof com.minecolonies.core.colony.jobs.JobBuilder
                    && data.getWorkBuilding() != null) {
                try {
                    BlockPos hut = data.getWorkBuilding().getPosition();
                    for (var wo : data.getColony().getWorkManager().getWorkOrders().values()) {
                        if (wo.isClaimed() && hut.equals(wo.getClaimedBy())) {
                            return false;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // Marko's report: couriers bringing materials to builders stopped to
            // chat (or got pulled toward a chat partner) and the delivery never
            // landed. A courier with deliveries QUEUED is working logistics the
            // whole colony waits on - no chats until the queue is empty. Also no
            // chats for MY errand couriers (fetch/deliver runs).
            if (data.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman dman) {
                try {
                    if (dman.getCurrentTask() != null || !dman.getTaskQueue().isEmpty()) {
                        return false;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (ErrandManager.hasErrand(citizen)) {
                return false; // mid-errand (fetching, delivering, guiding...) - never chat
            }
            if (data.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard) {
                if (ErrandManager.isOnMilitaryDuty(citizen)) {
                    return false;
                }
                try {
                    if (data.getColony() != null && data.getColony().getRaiderManager().isRaided()) {
                        return false; // battle stations, no gossip
                    }
                } catch (Throwable ignored) {
                }
            }
            return switch (JobChatPolicy.of(data)) {
                case CHATTY, WALKER -> true;
                case FOCUSED -> {
                    boolean idle = false;
                    try {
                        idle = data.isIdleAtJob();
                    } catch (Throwable ignored) {
                    }
                    yield idle;
                }
            };
        } catch (Throwable t) {
            return true;
        }
    }
}
