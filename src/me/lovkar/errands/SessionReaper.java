package me.lovkar.errands;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.LiveConversationWsClient;
import me.sshcrack.mc_talking.handler.UrgentContactHandler;
import me.sshcrack.mc_talking.manager.CitizenWsClient;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import me.sshcrack.mc_talking.network.AiStatus;
import me.sshcrack.mc_talking.util.AiStatusHelper;
import me.sshcrack.mc_talking.util.CitizenHelper;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Lovkar: "sometimes somebody does not go to sleep - it says Listening over their
 * head and they do not move." And: "for a couple of nights now it has not said
 * that everyone is tucked into bed."
 * <p>
 * Both are one bug, and it is in the plumbing of Talking Colonists rather than in
 * MineColonies. A citizen counts as <i>busy</i> while mc_talking holds a session or
 * a slot for them ({@code ConversationManager.isCitizenBusy}), and a busy citizen's
 * AI is pinned to IDLE by mc_talking's own mixins - no work, no wandering and, at
 * night, no walking to bed. So one session that never ends is one colonist who
 * never sleeps, and MineColonies only says "All citizens are tucked into bed" when
 * every last one of them is.
 * <p>
 * The session that never ends, from Lovkar's log: a low-priority solo session
 * (a rumor being passed on, a mumble) whose model answered the prompt with a tool
 * call and then said nothing. No audio, no turn, so mc_talking's "conversation
 * ended" hook never fired. After a minute and three quarters Gemini aborts the
 * idle socket with close code 1008 ("The operation was aborted"), mc_talking
 * treats that as an unknown code and reconnects - into an empty session with
 * nothing pending, which idles until the next 1008, and so on for the rest of
 * the evening. Its five-attempt cap never bites because it resets on every
 * successful setup. Meanwhile the citizen stands there under a "Listening" label.
 * <p>
 * Two lines of defence, neither of which ever touches a conversation the player
 * is in:
 * <ol>
 *   <li>{@link #onAbnormalClose} - at the moment Gemini drops a non-player session,
 *       if the session had already been asked to end, or had produced nothing since
 *       it connected, it is ended for good instead of reconnected: the client is
 *       closed (which makes mc_talking's own close handler treat it as intentional,
 *       so no reconnect), its slot is handed back the same way mc_talking would
 *       have at the end of the line, and the status label is cleared. A session
 *       that was actually talking when the socket dropped still gets mc_talking's
 *       reconnect - once; if the fresh connection then just sits there, the next
 *       drop ends it.</li>
 *   <li>{@link #tick} - a slow sweep every five seconds over everything that can
 *       pin a citizen: solo lines that have said nothing 75 s after connecting
 *       (ended before Gemini's own idle abort would get to them), background
 *       (pregeneration) slots older than three minutes, live non-player sessions
 *       older than ten, closed sockets still holding a slot, slots with no session
 *       behind them, urgent walks to the player that never arrive, and plain
 *       "busy" marks nobody lifted. Each has a generous limit well past anything
 *       that happens in normal play.</li>
 * </ol>
 * Everything is logged with a {@code [Sessions]} prefix so the log says who was
 * freed and why.
 */
public final class SessionReaper {

    private SessionReaper() {
    }

    private static final int CHECK_TICKS = 100;                    // every five seconds
    private static final long PREGEN_MAX_MS = 3 * 60_000L;         // a pregenerated line takes seconds
    private static final long COMPACTION_MAX_MS = 10 * 60_000L;    // memory compaction, a bit longer
    private static final long SESSION_MAX_MS = 10 * 60_000L;       // a mumble is one line, a live chat ten turns
    private static final long SOLO_IDLE_MS = 75_000L;              // a solo line that has not started after its setup
    private static final long CLOSED_LINGER_MS = 90_000L;          // a closed socket still sitting in the client map
    private static final long ORPHAN_SLOT_MS = 3 * 60_000L;        // a slot with no session behind it
    private static final long WALK_MAX_MS = 4 * 60_000L;           // an urgent walk to the player that never arrives
    private static final long BUSY_MAX_MS = 15 * 60_000L;          // a busy mark nobody lifted

    private static final Map<UUID, Long> BG_SEEN = new HashMap<>();
    private static final Map<GeminiWsClient, Long> SESSION_SEEN = new WeakHashMap<>();
    private static final Map<GeminiWsClient, Long> CLOSED_SEEN = new WeakHashMap<>();
    private static final Map<UUID, Long> ORPHAN_SEEN = new HashMap<>();
    private static final Map<UUID, Long> WALK_SEEN = new HashMap<>();
    private static final Map<UUID, Long> BUSY_SEEN = new HashMap<>();

    private static Field fAdded;
    private static Field fBusy;
    private static Field fBgSlots;
    private static Field fBgTypes;
    private static Field fWalking;
    private static Field fSystemEnd;
    private static boolean reflected;

    // ------------------------------------------------------------------
    // 1. At the moment Gemini drops a session (called from GeminiWsClientMixin, websocket thread)
    // ------------------------------------------------------------------

    /**
     * Decide what to do with a session Gemini has just closed. Returns true when the
     * session was ended here (the caller's close handler then sees an intentional
     * close and does not reconnect).
     *
     * @param intentional  mc_talking closed it itself (a reconnect's own close, or a real end)
     * @param endRequested the model or the code already asked for the conversation to end
     * @param spoke        audio or a finished turn came out of this connection since its setup
     */
    public static boolean onAbnormalClose(GeminiWsClient client, int code, String reason, boolean intentional,
                                          boolean endRequested, boolean spoke) {
        try {
            if (client == null || intentional || code == 1000 || code == 1001 || code == 1007) {
                return false; // normal ends; 1007 is a rejected setup (voice), VoiceFix and mc_talking's cap own that
            }
            if (reason != null && reason.contains("BidiGenerateContent session")) {
                return false; // an invalid resume token - mc_talking clears it and re-sends the prompt
            }
            AbstractEntityCitizen entity = client.getEntity();
            if (entity == null) {
                return false;
            }
            if (ConversationManager.getPlayerForEntity(entity.getUUID()) != null) {
                return false; // the player's conversation - never ours to end
            }
            if (!endRequested && spoke) {
                return false; // it was talking when the line dropped: one reconnect is fair
            }
            String what = "Gemini closed the session (" + code
                    + (reason == null || reason.isBlank() ? "" : ", \"" + reason.trim() + "\"") + ") and it "
                    + (endRequested ? "had already been asked to end" : "had produced nothing since it connected");
            end(client, what);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 2. The slow sweep (server thread)
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            backgroundSlots(server, now);
        } catch (Throwable ignored) {
        }
        try {
            liveSessions(now);
        } catch (Throwable ignored) {
        }
        try {
            orphanSlots(server, now);
        } catch (Throwable ignored) {
        }
        try {
            walkers(server, now);
        } catch (Throwable ignored) {
        }
        try {
            busyMarks(server, now);
        } catch (Throwable ignored) {
        }
    }

    /** Pregeneration and compaction slots that never finished. */
    private static void backgroundSlots(MinecraftServer server, long now) throws Exception {
        reflect();
        Set<UUID> slots;
        synchronized (ConversationManager.class) {
            slots = new HashSet<>((Set<UUID>) fBgSlots.get(null));
        }
        Map<?, ?> types = (Map<?, ?>) fBgTypes.get(null);
        BG_SEEN.keySet().retainAll(slots);
        for (UUID id : slots) {
            long age = ageOf(BG_SEEN, id, now);
            Object type = types.get(id);
            boolean pregen = type == null || "PREGEN".equals(String.valueOf(type));
            if (age < (pregen ? PREGEN_MAX_MS : COMPACTION_MAX_MS)) {
                continue;
            }
            ConversationManager.releaseBackgroundSlot(id); // drops the slot and closes its client
            BG_SEEN.remove(id);
            AbstractEntityCitizen c = CitizenHelper.findCitizen(server, id);
            clearStatusIfFree(c, id);
            ColonistErrands.LOGGER.info("[Sessions] {} - background {} session had been running for {} min without "
                    + "finishing; released the slot so they can get on with their day",
                    nameOf(c, id), pregen ? "pregeneration" : "compaction", age / 60_000);
        }
    }

    /** Non-player live sessions: closed sockets still holding a slot, and sessions that outlived any conversation. */
    private static void liveSessions(long now) {
        Map<UUID, GeminiWsClient> clients = new HashMap<>(ConversationManager.getClients());
        Set<GeminiWsClient> current = new HashSet<>(clients.values());
        SESSION_SEEN.keySet().retainAll(current);
        CLOSED_SEEN.keySet().retainAll(current);
        for (Map.Entry<UUID, GeminiWsClient> e : clients.entrySet()) {
            GeminiWsClient client = e.getValue();
            if (client == null) {
                continue;
            }
            long age = ageOf(SESSION_SEEN, client, now);
            if (ConversationManager.getPlayerForEntity(e.getKey()) != null) {
                CLOSED_SEEN.remove(client);
                continue; // the player's - mc_talking ends those itself
            }
            boolean closed = false;
            try {
                closed = client.isClosed();
            } catch (Throwable ignored) {
            }
            if (closed) {
                long closedFor = ageOf(CLOSED_SEEN, client, now);
                if (closedFor >= CLOSED_LINGER_MS) {
                    end(client, "its socket has been closed for " + (closedFor / 1000) + " s but it still held the slot");
                }
                continue;
            }
            CLOSED_SEEN.remove(client);
            if (age >= SESSION_MAX_MS) {
                end(client, "it had been open for " + (age / 60_000) + " minutes, longer than any conversation lasts");
                continue;
            }
            // A solo line (mumble, rumor, urgent contact) gets its prompt the moment the
            // setup completes and starts talking within seconds. One that has produced
            // nothing a minute and a quarter later never will - end it before Gemini's
            // own idle abort would, so the citizen is not pinned in the meantime.
            if (client instanceof CitizenWsClient cws && cws.isMumbling() && client instanceof SessionActivity act) {
                long setupAt = act.colonist_errands$setupAt();
                boolean open = false;
                try {
                    open = client.isOpen();
                } catch (Throwable ignored) {
                }
                if (open && setupAt > 0 && !act.colonist_errands$spoke() && now - setupAt >= SOLO_IDLE_MS) {
                    end(client, "it had said nothing in the " + ((now - setupAt) / 1000) + " s since it connected");
                }
            }
        }
    }

    /** A reserved slot with no session, no player and no walk behind it. */
    private static void orphanSlots(MinecraftServer server, long now) throws Exception {
        reflect();
        List<UUID> orphans = new ArrayList<>();
        Set<UUID> walking = walkingIds();
        synchronized (ConversationManager.class) {
            Set<UUID> added = (Set<UUID>) fAdded.get(null);
            Map<UUID, GeminiWsClient> clients = ConversationManager.getClients();
            for (UUID id : added) {
                if (clients.containsKey(id) || walking.contains(id) || ConversationManager.getPlayerForEntity(id) != null) {
                    continue;
                }
                orphans.add(id);
            }
        }
        ORPHAN_SEEN.keySet().retainAll(orphans);
        for (UUID id : orphans) {
            long age = ageOf(ORPHAN_SEEN, id, now);
            if (age < ORPHAN_SLOT_MS) {
                continue;
            }
            ConversationManager.releaseSlot(id);
            ORPHAN_SEEN.remove(id);
            AbstractEntityCitizen c = CitizenHelper.findCitizen(server, id);
            clearStatusIfFree(c, id);
            ColonistErrands.LOGGER.info("[Sessions] {} - a conversation slot was reserved for {} min with no session "
                    + "behind it; released it so they can get on with their day", nameOf(c, id), age / 60_000);
        }
    }

    /** Urgent walks to the player that never arrive (mc_talking has no timeout of its own). */
    private static void walkers(MinecraftServer server, long now) throws Exception {
        Set<UUID> walking = walkingIds();
        WALK_SEEN.keySet().retainAll(walking);
        for (UUID id : walking) {
            long age = ageOf(WALK_SEEN, id, now);
            if (age < WALK_MAX_MS) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) fWalking.get(null);
            map.remove(id);
            WALK_SEEN.remove(id);
            AbstractEntityCitizen c = CitizenHelper.findCitizen(server, id);
            UrgentContactHandler.abortWalking(id, server); // stops the walk, clears the label, frees the slot
            ColonistErrands.LOGGER.info("[Sessions] {} - had been walking to the player for {} min without getting "
                    + "there; called the walk off so they can get on with their day", nameOf(c, id), age / 60_000);
        }
    }

    /** A "busy" mark that outlived whatever set it. */
    private static void busyMarks(MinecraftServer server, long now) throws Exception {
        reflect();
        Set<UUID> busy = new HashSet<>((Set<UUID>) fBusy.get(null));
        BUSY_SEEN.keySet().retainAll(busy);
        for (UUID id : busy) {
            long age = ageOf(BUSY_SEEN, id, now);
            if (age < BUSY_MAX_MS) {
                continue;
            }
            BUSY_SEEN.remove(id);
            AbstractEntityCitizen c = CitizenHelper.findCitizen(server, id);
            if (c != null) {
                ConversationManager.markNotBusy(c);
            } else {
                ((Set<UUID>) fBusy.get(null)).remove(id);
            }
            clearStatusIfFree(c, id);
            ColonistErrands.LOGGER.info("[Sessions] {} - had been marked busy for {} min straight; lifted the mark "
                    + "so they can get on with their day", nameOf(c, id), age / 60_000);
        }
    }

    // ------------------------------------------------------------------
    // Ending a session the way mc_talking would have
    // ------------------------------------------------------------------

    /**
     * Close a non-player session for good and hand its slot back. For a solo
     * (mumble / rumor / urgent-contact) session that is mc_talking's own
     * end-of-line callback; for anything else the client is unregistered, if it
     * still owns the entry, and the cooldown recorded. The other half of a live
     * citizen-to-citizen chat is asked to finish after its current line.
     */
    static void end(GeminiWsClient client, String why) {
        AbstractEntityCitizen entity = null;
        UUID id = null;
        try {
            entity = client.getEntity();
            id = entity == null ? null : entity.getUUID();
        } catch (Throwable ignored) {
        }
        String who = nameOf(entity, id);
        String kind = kindOf(client);
        try {
            client.close(); // intentionalClose = true, status NONE, stream drained, no reconnect
        } catch (Throwable ignored) {
        }
        boolean handedBack = false;
        if (client instanceof CitizenWsClient cws) {
            Consumer<CitizenWsClient> onEnd = systemEndOf(cws);
            if (onEnd != null) {
                try {
                    onEnd.accept(cws); // clients.remove (if still ours), releaseSlot, recordCooldown
                    handedBack = true;
                } catch (Throwable ignored) {
                }
            }
        }
        if (!handedBack && entity != null) {
            releaseIfOwner(entity, client);
        }
        if (client instanceof LiveConversationWsClient live) {
            askPeerToFinish(live);
        }
        if (entity != null) {
            try {
                AiStatusHelper.setAiStatusSynced(entity, AiStatus.NONE);
            } catch (Throwable ignored) {
            }
        }
        ColonistErrands.LOGGER.info("[Sessions] {} - {}: {}; ended it so they can get on with their day", who, kind, why);
    }

    private static void releaseIfOwner(AbstractEntityCitizen entity, GeminiWsClient client) {
        try {
            synchronized (ConversationManager.class) {
                if (ConversationManager.getClients().get(entity.getUUID()) == client) {
                    ConversationManager.unregisterExternalClient(entity); // clients.remove + releaseSlot
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            ConversationManager.recordCooldown(entity);
        } catch (Throwable ignored) {
        }
    }

    /** The other speaker of a live chat whose partner just vanished: let them finish the line, then stop. */
    private static void askPeerToFinish(LiveConversationWsClient live) {
        try {
            LiveConversationWsClient peer = live.getPeer();
            if (peer == null || peer.isClosed()) {
                return;
            }
            UUID peerId = peer.getEntity().getUUID();
            if (ConversationManager.getPlayerForEntity(peerId) != null
                    || ConversationManager.getClients().get(peerId) != peer) {
                return;
            }
            peer.endConversationWhenPossible();
        } catch (Throwable ignored) {
        }
    }

    private static void clearStatusIfFree(AbstractEntityCitizen c, UUID id) {
        if (c == null) {
            return;
        }
        try {
            if (ConversationManager.getClients().containsKey(id)) {
                return; // a live session owns the label
            }
            AiStatusHelper.setAiStatusSynced(c, AiStatus.NONE);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Reflection into mc_talking's private state
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Consumer<CitizenWsClient> systemEndOf(CitizenWsClient client) {
        try {
            if (fSystemEnd == null) {
                Field f = CitizenWsClient.class.getDeclaredField("onSystemConversationEnded");
                f.setAccessible(true);
                fSystemEnd = f;
            }
            return (Consumer<CitizenWsClient>) fSystemEnd.get(client);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> walkingIds() {
        try {
            reflect();
            Map<UUID, ?> map = (Map<UUID, ?>) fWalking.get(null);
            return new HashSet<>(map.keySet());
        } catch (Throwable t) {
            return Set.of();
        }
    }

    private static void reflect() throws Exception {
        if (reflected) {
            return;
        }
        Field added = ConversationManager.class.getDeclaredField("addedEntities");
        added.setAccessible(true);
        Field busy = ConversationManager.class.getDeclaredField("busyEntities");
        busy.setAccessible(true);
        Field bgSlots = ConversationManager.class.getDeclaredField("backgroundSlots");
        bgSlots.setAccessible(true);
        Field bgTypes = ConversationManager.class.getDeclaredField("backgroundSlotTypes");
        bgTypes.setAccessible(true);
        Field walking = UrgentContactHandler.class.getDeclaredField("walkingCitizens");
        walking.setAccessible(true);
        fAdded = added;
        fBusy = busy;
        fBgSlots = bgSlots;
        fBgTypes = bgTypes;
        fWalking = walking;
        reflected = true;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static <K> long ageOf(Map<K, Long> seen, K key, long now) {
        Long first = seen.get(key);
        if (first == null) {
            seen.put(key, now);
            return 0;
        }
        return now - first;
    }

    private static String kindOf(GeminiWsClient client) {
        if (client instanceof LiveConversationWsClient) {
            return "live citizen-to-citizen chat";
        }
        if (client instanceof CitizenWsClient cws) {
            return cws.isMumbling() ? "solo line (mumble, rumor or urgent contact)" : "session";
        }
        return "session";
    }

    private static String nameOf(AbstractEntityCitizen c, UUID id) {
        try {
            if (c != null && c.getCitizenData() != null) {
                return c.getCitizenData().getName();
            }
        } catch (Throwable ignored) {
        }
        return id == null ? "citizen" : "citizen " + id;
    }

    public static void clearAll() {
        BG_SEEN.clear();
        SESSION_SEEN.clear();
        CLOSED_SEEN.clear();
        ORPHAN_SEEN.clear();
        WALK_SEEN.clear();
        BUSY_SEEN.clear();
    }
}
