package me.lovkar.errands;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.LiveConversationWsClient;
import me.sshcrack.mc_talking.manager.GeminiStream;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's report: "sometimes they are still cut off mid-sentence."
 * <p>
 * The cut is in mc_talking's own shutdown. {@code GeminiWsClient.close()} ends with
 * {@code stream.close()}, and {@code GeminiStream.close()} empties the audio queue
 * and stops the voice-chat player on the spot. But a session is closed the moment
 * Gemini reports the turn <i>generated</i>, and generation runs well ahead of
 * playback - so whatever of the last sentence was still queued is thrown away.
 * For a player conversation the goodbye loses its tail; for a citizen-to-citizen
 * live conversation it is worse, because the second speaker's reply is <i>held</i>
 * until the first has finished, and the close arrives before it was ever released.
 * <p>
 * So the close of a stream is deferred here until it has actually been heard:
 * <ol>
 *   <li>{@code flushAudio()} first - the last few hundred milliseconds sit below
 *       the stream's processing threshold and only a flush moves them to the
 *       player;</li>
 *   <li>if this is a live citizen-to-citizen session still holding its reply for
 *       the other speaker, wait for that speaker to finish, then release the
 *       reply ourselves (mc_talking will not - it only releases to a peer whose
 *       socket is still open);</li>
 *   <li>wait until the queue is empty and the player has stopped, then close.</li>
 * </ol>
 * The speaker is kept "busy" while their stream drains, so they stand and finish
 * the sentence instead of walking off with it, and nobody else starts a session
 * with them until it is done. A new session for the same citizen (the player
 * addressing them, say) cuts the drain at once - the player always comes first.
 * Thirty seconds is the most any stream is allowed; then it is cut regardless.
 * <p>
 * Barge-in is untouched: that goes through {@code stream.stop()}, not
 * {@code close()}, and a player talking over a citizen should still silence them.
 */
public final class StreamDrain {

    private StreamDrain() {
    }

    private static final int MAX_WAIT_TICKS = 30 * 20;
    private static final int HELD_WAIT_TICKS = 20 * 20;

    private static final class Pending {
        final GeminiWsClient client;
        final GeminiStream stream;
        final AbstractEntityCitizen entity;
        final UUID entityId;
        final long orderMs = System.currentTimeMillis();
        int startedTick = -1;
        boolean released = false;
        boolean busyMarked = false;

        Pending(GeminiWsClient client, GeminiStream stream) {
            this.client = client;
            this.stream = stream;
            AbstractEntityCitizen e = null;
            try {
                e = client.getEntity();
            } catch (Throwable ignored) {
            }
            this.entity = e;
            this.entityId = e != null ? e.getUUID() : null;
        }
    }

    private static final Map<GeminiStream, Pending> PENDING = new ConcurrentHashMap<>();

    private static Field fFrames;
    private static Field fIncoming;
    private static Field fPlayer;
    private static Field fClientStream;
    private static Field fHeld;

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Stands in for {@code stream.close()} inside {@code GeminiWsClient.close()}.
     * Called on whatever thread closed the session - it only queues.
     */
    public static void closeWhenDrained(GeminiWsClient client, GeminiStream stream) {
        if (stream == null) {
            return;
        }
        if (PENDING.containsKey(stream)) {
            return; // close() called twice - already draining
        }
        try {
            stream.flushAudio();
        } catch (Throwable ignored) {
        }
        boolean held = hasHeld(client);
        if (!held && !hasWork(stream)) {
            closeNow(stream); // nothing left to hear
            return;
        }
        PENDING.putIfAbsent(stream, new Pending(client, stream));
    }

    /** A fresh session for this citizen: whatever they were still saying gives way. */
    public static void newSessionFor(AbstractEntityCitizen entity) {
        if (entity == null) {
            return;
        }
        UUID id = entity.getUUID();
        for (Pending p : PENDING.values()) {
            if (id.equals(p.entityId)) {
                p.busyMarked = false; // the new session owns their busy state now
                finish(p, "a new session took over the voice");
            }
        }
    }

    /** True while this stream still has audio queued or playing. */
    public static boolean hasWork(GeminiStream stream) {
        if (stream == null) {
            return false;
        }
        try {
            reflect();
            Queue<?> frames = (Queue<?>) fFrames.get(stream);
            List<?> incoming = (List<?>) fIncoming.get(stream);
            Object player = fPlayer.get(stream);
            boolean playing = player instanceof AudioPlayer ap && ap.isStarted() && !ap.isStopped();
            return !frames.isEmpty() || !incoming.isEmpty() || playing;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isDraining(GeminiStream stream) {
        return stream != null && PENDING.containsKey(stream);
    }

    // ------------------------------------------------------------------
    // Server tick
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (Pending p : PENDING.values()) {
            try {
                if (p.startedTick < 0) {
                    p.startedTick = now;
                }
                int age = now - p.startedTick;

                // Stand still and finish the sentence.
                if (p.entity != null && p.entity.isAlive() && !p.entity.isRemoved()) {
                    ConversationManager.markBusy(p.entity);
                    p.busyMarked = true;
                }

                if (!p.released) {
                    if (!hasHeld(p.client)) {
                        p.released = true;
                    } else if (!peerStillTalking(p) || age > HELD_WAIT_TICKS) {
                        ((LiveConversationWsClient) p.client).releaseHeldAudio();
                        p.stream.flushAudio();
                        p.released = true;
                    } else {
                        continue; // the other speaker is mid-sentence - our reply waits its turn
                    }
                }

                if (!hasWork(p.stream)) {
                    finish(p, "played to the end");
                    continue;
                }
                if (age > MAX_WAIT_TICKS) {
                    finish(p, "ran out of patience after 30 s");
                    continue;
                }
                // A sub-threshold tail with the player already stopped only ever plays on a flush.
                if (!playing(p.stream) && framesEmpty(p.stream)) {
                    p.stream.flushAudio();
                }
            } catch (Throwable t) {
                finish(p, "something went wrong");
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static boolean peerStillTalking(Pending p) {
        try {
            if (!(p.client instanceof LiveConversationWsClient live)) {
                return false;
            }
            LiveConversationWsClient peer = live.getPeer();
            if (peer == null) {
                return false;
            }
            GeminiStream ps = streamOf(peer);
            if (ps != null && hasWork(ps)) {
                return true; // they are speaking - let them finish
            }
            if (hasHeld(peer)) {
                // Both closed with a line still in hand: whoever closed first speaks first.
                Pending pp = ps != null ? PENDING.get(ps) : null;
                return pp != null && pp.orderMs < p.orderMs;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasHeld(GeminiWsClient client) {
        try {
            if (!(client instanceof LiveConversationWsClient)) {
                return false;
            }
            reflect();
            List<?> held = (List<?>) fHeld.get(client);
            synchronized (held) {
                return !held.isEmpty();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static GeminiStream streamOf(GeminiWsClient client) {
        try {
            reflect();
            return (GeminiStream) fClientStream.get(client);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean playing(GeminiStream stream) throws Exception {
        Object player = fPlayer.get(stream);
        return player instanceof AudioPlayer ap && ap.isStarted() && !ap.isStopped();
    }

    private static boolean framesEmpty(GeminiStream stream) throws Exception {
        return ((Queue<?>) fFrames.get(stream)).isEmpty();
    }

    private static void finish(Pending p, String why) {
        if (PENDING.remove(p.stream) == null) {
            return;
        }
        if (p.busyMarked && p.entity != null) {
            try {
                ConversationManager.markNotBusy(p.entity);
            } catch (Throwable ignored) {
            }
        }
        closeNow(p.stream);
        String who = "citizen";
        try {
            if (p.entity != null && p.entity.getCitizenData() != null) {
                who = p.entity.getCitizenData().getName();
            }
        } catch (Throwable ignored) {
        }
        ColonistErrands.LOGGER.info("[Voice] {} - stream closed: {}", who, why);
    }

    /** GeminiStream.stop() can block for up to two seconds - never on the server thread. */
    private static void closeNow(GeminiStream stream) {
        Thread t = new Thread(() -> {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
        }, "colonist_errands-stream-close");
        t.setDaemon(true);
        t.start();
    }

    private static void reflect() throws Exception {
        if (fFrames != null) {
            return;
        }
        Field frames = GeminiStream.class.getDeclaredField("audioFrames");
        frames.setAccessible(true);
        Field incoming = GeminiStream.class.getDeclaredField("incomingData");
        incoming.setAccessible(true);
        Field player = GeminiStream.class.getDeclaredField("player");
        player.setAccessible(true);
        Field clientStream = GeminiWsClient.class.getDeclaredField("stream");
        clientStream.setAccessible(true);
        Field held = LiveConversationWsClient.class.getDeclaredField("heldAudioChunks");
        held.setAccessible(true);
        fIncoming = incoming;
        fPlayer = player;
        fClientStream = clientStream;
        fHeld = held;
        fFrames = frames; // last - it is the "initialised" flag
    }

    public static void clearAll() {
        for (Pending p : PENDING.values()) {
            p.busyMarked = false;
            finish(p, "world closing");
        }
        PENDING.clear();
    }
}
