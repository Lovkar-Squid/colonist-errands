package me.lovkar.errands.tc;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.sshcrack.mc_talking.api.conversation.CitizenActivityReservation;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ConversationEligibility;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.conversation.ConversationStartResult;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything Colonist Errands asks Talking Colonists for, in one place.
 *
 * <p>Errands 2.x reached straight into mc_talking's internals - {@code ConversationManager},
 * the busy map, the duck-typed citizen memory. Talking Colonists 2.0 replaced all of that with a
 * public addon API, and this class is the whole of what Errands uses of it, so that the gameplay
 * classes (ErrandManager, the chats, the watchers) read the same as before: {@code Talk.isBusy},
 * {@code Talk.hold}, {@code Talk.remember}. Every method swallows what the core throws and falls
 * back to the harmless answer, because none of this is worth a crash in a tick handler.</p>
 *
 * <p>The one thing that changed shape is "busy". mc_talking 1.7 had a flag we set and cleared;
 * 2.0 has <em>activity leases</em> - a reservation with an owner and an expiry, which the core
 * takes away when the player addresses the citizen. {@link #hold} takes a lease the first time
 * and renews it while it keeps being called (the ErrandManager calls it every tick of an errand),
 * {@link #release} gives it back.</p>
 */
public final class Talk {

    private Talk() {
    }

    /** Owner id the core shows in its logs for our leases. */
    private static final String LEASE_OWNER = "colonist_errands:errand";
    private static final Duration LEASE = Duration.ofMinutes(5);
    /** Renew when less than this much of the lease is left (we renew on a tick cadence, so be generous). */
    private static final long RENEW_AFTER_MS = 60_000L;

    private static final class Lease {
        final CitizenActivityReservation reservation;
        long renewedAt;

        Lease(final CitizenActivityReservation reservation) {
            this.reservation = reservation;
            this.renewedAt = System.currentTimeMillis();
        }
    }

    private static final Map<UUID, Lease> LEASES = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ busy / leases

    /** Is the citizen in any conversation, or reserved by an addon (us included)? */
    public static boolean isBusy(final AbstractEntityCitizen citizen) {
        if (citizen == null) {
            return false;
        }
        try {
            return CitizenConversationService.isBusy(citizen);
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Keep the citizen for an errand: nobody starts a chat, mumble or urgent contact with them
     * while we hold the lease. Idempotent and cheap - call it every tick of the errand.
     *
     * @return true if we hold the lease now; false if the core refused (a player is talking to
     *         them, or another addon holds them) - the caller carries on and tries again next tick
     */
    public static boolean hold(final AbstractEntityCitizen citizen) {
        if (citizen == null) {
            return false;
        }
        final UUID id = citizen.getUUID();
        final long now = System.currentTimeMillis();
        try {
            final Lease lease = LEASES.get(id);
            if (lease != null && !lease.reservation.isClosed()) {
                if (now - lease.renewedAt > RENEW_AFTER_MS) {
                    if (lease.reservation.renew(LEASE)) {
                        lease.renewedAt = now;
                        return true;
                    }
                    LEASES.remove(id); // the core took it away (player takeover) - reserve afresh
                } else {
                    return true;
                }
            } else if (lease != null) {
                LEASES.remove(id);
            }
            final Optional<CitizenActivityReservation> fresh =
                    CitizenConversationService.reserveActivity(citizen, LEASE_OWNER, LEASE);
            if (fresh.isPresent()) {
                LEASES.put(id, new Lease(fresh.get()));
                return true;
            }
            return false;
        } catch (final Throwable t) {
            return false;
        }
    }

    /** The errand is over - let the citizen go. Safe to call when we never held them. */
    public static void release(final AbstractEntityCitizen citizen) {
        if (citizen == null) {
            return;
        }
        release(citizen.getUUID());
    }

    public static void release(final UUID citizenId) {
        final Lease lease = LEASES.remove(citizenId);
        if (lease != null) {
            try {
                lease.reservation.close();
            } catch (final Throwable ignored) {
            }
        }
    }

    /** Do we currently hold a lease on this citizen? */
    public static boolean isHeld(final UUID citizenId) {
        final Lease lease = LEASES.get(citizenId);
        return lease != null && !lease.reservation.isClosed();
    }

    /** Server stopping: drop every lease we still hold. */
    public static void releaseAll() {
        for (final UUID id : LEASES.keySet().toArray(new UUID[0])) {
            release(id);
        }
    }

    // ------------------------------------------------------------------ conversations

    /** The player this citizen is talking with right now, or null. */
    public static UUID activePlayerId(final AbstractEntityCitizen citizen) {
        if (citizen == null) {
            return null;
        }
        try {
            return CitizenConversationService.activePlayerId(citizen).orElse(null);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** The player this citizen is talking with right now, as an online player, or null. */
    public static ServerPlayer activePlayer(final AbstractEntityCitizen citizen) {
        final UUID id = activePlayerId(citizen);
        if (id == null) {
            return null;
        }
        try {
            final MinecraftServer server = citizen.getServer();
            return server == null ? null : server.getPlayerList().getPlayer(id);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Account name of the player this citizen is conversing with right now, or null. */
    public static String activePlayerName(final AbstractEntityCitizen citizen) {
        final ServerPlayer player = activePlayer(citizen);
        return player == null ? null : player.getGameProfile().getName();
    }

    /** Is the citizen in a conversation with a player (the one thing an errand must wait for)? */
    public static boolean isTalkingToPlayer(final AbstractEntityCitizen citizen) {
        return activePlayerId(citizen) != null;
    }

    public static boolean isPlayerInConversation(final ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            return CitizenConversationService.isPlayerInConversation(player);
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Could the citizen start a conversation of this kind right now? The core's answer includes
     * sleeping, visitor, cooldown, busy and every addon speech policy - ours too, so a citizen
     * our own rules keep quiet is "no" here as well.
     */
    public static boolean canSpeak(final AbstractEntityCitizen citizen, final ConversationKind kind) {
        if (citizen == null) {
            return false;
        }
        try {
            return CitizenConversationService.canSpeak(citizen, kind);
        } catch (final Throwable t) {
            return false;
        }
    }

    /** Like {@link #canSpeak} but with the reason, for logs. */
    public static String whyNot(final AbstractEntityCitizen citizen, final ConversationKind kind) {
        try {
            final ConversationEligibility e = CitizenConversationService.eligibility(citizen, kind);
            return e.eligible() ? "eligible" : e.status() + (e.detail() == null ? "" : " (" + e.detail() + ")");
        } catch (final Throwable t) {
            return "unknown";
        }
    }

    /** Could this citizen talk to a player now (sleeping / visitor / cooldown / busy say no)? */
    public static boolean canTalkToPlayer(final AbstractEntityCitizen citizen) {
        return canSpeak(citizen, ConversationKind.PLAYER);
    }

    /** Could this citizen take part in one of our citizen-to-citizen chats now? */
    public static boolean canChat(final AbstractEntityCitizen citizen) {
        return canSpeak(citizen, ConversationKind.CITIZEN_PAIR);
    }

    /**
     * Start a conversation between the player and the citizen, as if the player had addressed
     * them - used when a messenger or a courier reaches the player. Clears the automatic
     * cooldown first, because the citizen has a reason to speak.
     *
     * @return null when it started, otherwise the reason it did not
     */
    public static String startPlayerConversation(final ServerPlayer player, final AbstractEntityCitizen citizen) {
        if (player == null || citizen == null) {
            return "no player or citizen";
        }
        try {
            CitizenConversationService.resetAutomaticCooldown(citizen);
            final ConversationStartResult result = CitizenConversationService.startPlayerConversation(player, citizen);
            if (result.started()) {
                return null;
            }
            return result.status() + (result.detail() == null ? "" : " (" + result.detail() + ")");
        } catch (final Throwable t) {
            return "failed: " + t;
        }
    }

    public static void resetCooldown(final AbstractEntityCitizen citizen) {
        if (citizen == null) {
            return;
        }
        try {
            CitizenConversationService.resetAutomaticCooldown(citizen);
        } catch (final Throwable ignored) {
        }
    }

    /**
     * Ask the core to end the citizen's current conversation once the current line is spoken.
     * This is what leave_conversation does for player conversations and what the chat cap does
     * for citizen pairs.
     */
    public static boolean endGracefully(final AbstractEntityCitizen citizen) {
        if (citizen == null) {
            return false;
        }
        try {
            return CitizenConversationService.requestGracefulEnd(citizen);
        } catch (final Throwable t) {
            return false;
        }
    }

    /** Room for this many more ambient (non-player) speakers? */
    public static boolean hasAmbientCapacity(final int slots) {
        try {
            return CitizenConversationService.hasAmbientCapacity(slots);
        } catch (final Throwable t) {
            return false;
        }
    }

    public static boolean hasPlayerNearby(final AbstractEntityCitizen citizen, final double range) {
        if (citizen == null) {
            return false;
        }
        try {
            return CitizenConversationService.hasPlayerNearby(citizen, range);
        } catch (final Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ memory

    /** Give the citizen a memory of something that just happened (what 2.x wrote through the duck). */
    public static boolean remember(final ICitizenData data, final String event) {
        if (data == null || event == null || event.isBlank()) {
            return false;
        }
        try {
            return CitizenMemoryService.addEvent(data, event);
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.debug("[Talk] addEvent failed for {}", data.getName(), t);
            return false;
        }
    }

    public static boolean remember(final AbstractEntityCitizen citizen, final String event) {
        return citizen != null && remember(citizen.getCitizenData(), event);
    }

    /** A lasting fact about the citizen, as opposed to an event. */
    public static boolean rememberFact(final ICitizenData data, final String fact) {
        if (data == null || fact == null || fact.isBlank()) {
            return false;
        }
        try {
            return CitizenMemoryService.addFact(data, fact);
        } catch (final Throwable t) {
            return false;
        }
    }
}
