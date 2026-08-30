package me.marko.errands;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.GeminiStream;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central audio control fixing two symptoms Marko reported:
 *
 * 1) DOUBLED GOODBYE: after an errand tool + leave_conversation, Gemini must
 *    respond to the tool result and tends to re-say the goodbye. We arm a gate
 *    when leave_conversation runs; once the goodbye generation finishes
 *    streaming (or a grace window passes), all further audio of that
 *    conversation is dropped before it ever reaches the speaker queue.
 *    Also: when mc_talking re-sends the last prompt after "Session token
 *    invalidated", the fresh session answers AGAIN - we clear the stale queued
 *    audio at that exact point so the answer is heard once, not twice.
 *
 * 2) NON-INTERRUPTIBLE SPEECH: pregenerated greeting/delivery clips play on a
 *    bare GeminiStream with no live session - nothing listens to the mic, so
 *    they always play to the end. We register every pregen stream and stop it
 *    the moment the player starts speaking nearby (or a live session for the
 *    same citizen starts talking).
 */
public final class AudioGate {

    private AudioGate() {
    }

    // ------------------------------------------------------------------
    // Part 1: mute-after-goodbye for live conversations (key = citizen UUID)
    // ------------------------------------------------------------------

    private static final Map<UUID, Long> LEAVE_ARMED_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> MUTED_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_GEN_COMPLETE = new ConcurrentHashMap<>();

    /** Max time goodbye audio may keep streaming in after leave_conversation. */
    private static final long ARM_GRACE_MS = 2500;
    /** genComplete this recent before the leave call = goodbye already fully streamed. */
    private static final long RECENT_GEN_MS = 1000;
    /** Safety: no gate survives longer than this (conversation ends way earlier). */
    private static final long SELF_EXPIRE_MS = 30_000;

    /** Called by LeaveConversationAction the moment the tool executes. */
    public static void onLeaveCalled(UUID citizen) {
        long now = System.currentTimeMillis();
        Long lastGen = LAST_GEN_COMPLETE.get(citizen);
        if (lastGen != null && now - lastGen < RECENT_GEN_MS) {
            // The goodbye generation already completed - everything after is the duplicate.
            MUTED_AT.put(citizen, now);
            ColonistErrands.LOGGER.info("[AudioGate] {} muted immediately after leave (goodbye already streamed)", citizen);
        } else {
            LEAVE_ARMED_AT.put(citizen, now);
            ColonistErrands.LOGGER.info("[AudioGate] {} armed: goodbye may finish, then mute", citizen);
        }
    }

    /** Called from the GeminiWsClient mixin on every generationComplete. */
    public static void onGenerationComplete(UUID citizen) {
        long now = System.currentTimeMillis();
        LAST_GEN_COMPLETE.put(citizen, now);
        if (LEAVE_ARMED_AT.containsKey(citizen) && !MUTED_AT.containsKey(citizen)) {
            MUTED_AT.put(citizen, now);
            ColonistErrands.LOGGER.info("[AudioGate] {} goodbye finished streaming -> further audio muted", citizen);
        }
    }

    /** true = drop this incoming audio chunk (post-goodbye duplicate generation). */
    public static boolean shouldDropAudio(UUID citizen) {
        Long muted = MUTED_AT.get(citizen);
        long now = System.currentTimeMillis();
        if (muted != null) {
            if (now - muted > SELF_EXPIRE_MS) {
                clear(citizen);
                return false;
            }
            return true;
        }
        Long armed = LEAVE_ARMED_AT.get(citizen);
        if (armed != null && now - armed > ARM_GRACE_MS) {
            MUTED_AT.put(citizen, now);
            ColonistErrands.LOGGER.info("[AudioGate] {} grace window over -> further audio muted", citizen);
            return true;
        }
        return false;
    }

    /** Conversation is over (client.close()) - forget all gate state for this citizen. */
    public static void clear(UUID citizen) {
        LEAVE_ARMED_AT.remove(citizen);
        MUTED_AT.remove(citizen);
        LAST_GEN_COMPLETE.remove(citizen);
    }

    // ------------------------------------------------------------------
    // Part 1b: drop stale queued audio when the session is re-prompted
    // ------------------------------------------------------------------

    private static Field fFrames;
    private static Field fIncoming;
    private static Field fBuffered;
    private static Field fRemaining;

    /**
     * Empties a GeminiStream's queued-but-unplayed audio WITHOUT stopping the
     * voicechat player (it drains to a natural pause on its own). Used when
     * "Session token invalidated" forces a fresh session that will re-answer
     * the same prompt - the old queued answer would otherwise play too.
     */
    public static void clearQueuedAudio(GeminiStream stream, String who) {
        if (stream == null) {
            return;
        }
        try {
            if (fFrames == null) {
                fFrames = GeminiStream.class.getDeclaredField("audioFrames");
                fFrames.setAccessible(true);
                fIncoming = GeminiStream.class.getDeclaredField("incomingData");
                fIncoming.setAccessible(true);
                fBuffered = GeminiStream.class.getDeclaredField("totalBufferedBytes");
                fBuffered.setAccessible(true);
                fRemaining = GeminiStream.class.getDeclaredField("remainingSamples");
                fRemaining.setAccessible(true);
            }
            Queue<?> frames = (Queue<?>) fFrames.get(stream);
            List<?> incoming = (List<?>) fIncoming.get(stream);
            int dropped = frames.size();
            synchronized (incoming) {
                dropped += incoming.size();
                incoming.clear();
                fBuffered.setInt(stream, 0);
            }
            frames.clear();
            fRemaining.set(stream, new short[0]);
            if (dropped > 0) {
                ColonistErrands.LOGGER.info("[AudioGate] {}: dropped {} stale queued audio chunk(s) before session re-prompt (anti-double)", who, dropped);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[AudioGate] could not clear queued audio", t);
        }
    }

    // ------------------------------------------------------------------
    // Part 2: interruptible pregenerated clips
    // ------------------------------------------------------------------

    private static final class PregenEntry {
        final GeminiStream stream;
        final AbstractEntityCitizen citizen;
        final long startedAt = System.currentTimeMillis();

        PregenEntry(GeminiStream stream, AbstractEntityCitizen citizen) {
            this.stream = stream;
            this.citizen = citizen;
        }
    }

    private static final Map<UUID, PregenEntry> PREGEN = new ConcurrentHashMap<>();
    private static final long PREGEN_STALE_MS = 60_000;
    private static Field fPregenSlots;

    /** Registered from the PregenerationPlayback mixin whenever a cached clip starts. */
    public static void registerPregen(AbstractEntityCitizen citizen, GeminiStream stream) {
        if (citizen == null || stream == null) {
            return;
        }
        purgeStale();
        PREGEN.put(citizen.getUUID(), new PregenEntry(stream, citizen));
    }

    public static boolean hasActivePregen() {
        return !PREGEN.isEmpty();
    }

    /** Stops the pregen clip of ONE citizen (e.g. its live session started talking). */
    public static void stopPregen(UUID citizenId) {
        if (PREGEN.isEmpty()) {
            return;
        }
        PregenEntry e = PREGEN.remove(citizenId);
        if (e != null) {
            stopEntry(e, "live voice took over");
        }
    }

    // Debounce so a cough, a click or open-mic background noise doesn't cut
    // clips: only SUSTAINED speech (several voice packets in a row) counts.
    private static final Map<UUID, long[]> VOICE_STREAK = new ConcurrentHashMap<>(); // player -> [streak, lastMs, streakStartMs]
    private static final int STREAK_NEEDED = 6;        // ~120ms of continuous voice
    private static final long STREAK_RESET_MS = 900;   // gaps under this = still the SAME sentence
    /** A freshly started clip may NOT be cut - it gets to say its first phrase. */
    private static final long EARLY_PROTECT_MS = 2000;

    /**
     * Called for every mic packet with voice content. TRUE barge-in semantics
     * (Marko: greetings addressed at him were strangled at birth): a clip is
     * cut only when the player DELIBERATELY talks over it - meaning the clip
     * already had 2 seconds to speak AND the player's current speech burst
     * began after the clip started. Natural pauses between words (up to
     * ~900 ms) do NOT count as a new burst, so finishing a sentence that
     * started before the clip never kills it.
     */
    public static void onPlayerVoicePacket(ServerPlayer player, int opusBytes) {
        if (player == null || opusBytes <= 15) {
            return;
        }
        // Track the streak on EVERY packet (not only while a clip plays) so the
        // burst start is truthful when a clip appears mid-sentence.
        long now = System.currentTimeMillis();
        long[] st = VOICE_STREAK.computeIfAbsent(player.getUUID(), k -> new long[]{0, 0, 0});
        if (now - st[1] > STREAK_RESET_MS) {
            st[0] = 0;
            st[2] = now; // a NEW speech burst starts here
        }
        st[0]++;
        st[1] = now;
        if (PREGEN.isEmpty()) {
            return;
        }
        if (st[0] >= STREAK_NEEDED) {
            // Only the clip playing right AT the player, and only if this
            // speech burst began AFTER the clip started playing.
            stopPregenNear(player, 7.0, st[2]);
        }
    }

    /** The player talks over a clip: cut pregen clips near them that began BEFORE this speech burst. */
    public static void stopPregenNear(ServerPlayer player, double radius, long speechBurstStartMs) {
        if (PREGEN.isEmpty() || player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        double r2 = radius * radius;
        Iterator<Map.Entry<UUID, PregenEntry>> it = PREGEN.entrySet().iterator();
        while (it.hasNext()) {
            PregenEntry e = it.next().getValue();
            try {
                // "Takoj jih prekine": a clip in its first moments is untouchable -
                // the citizen gets to actually say something before barge-in applies.
                if (now - e.startedAt < EARLY_PROTECT_MS) {
                    continue;
                }
                // Clip started after (or right around) the burst start -> the player
                // was already talking when it began; do NOT strangle it at birth.
                if (speechBurstStartMs > 0 && e.startedAt >= speechBurstStartMs - 300) {
                    continue;
                }
                if (e.citizen.isRemoved() || e.citizen.level() != player.level()
                        || player.distanceToSqr(e.citizen) <= r2) {
                    it.remove();
                    stopEntry(e, "player talked over it");
                }
            } catch (Throwable t) {
                it.remove();
            }
        }
    }

    private static void stopEntry(PregenEntry e, String why) {
        try {
            e.stream.stop();
        } catch (Throwable ignored) {
        }
        try {
            // Same cleanup PregenerationPlayback would do at natural end of clip.
            if (fPregenSlots == null) {
                Class<?> c = Class.forName("me.sshcrack.mc_talking.pregen.PregenerationPlayback");
                fPregenSlots = c.getDeclaredField("ACTIVE_PREGENERATED_PLAYBACK");
                fPregenSlots.setAccessible(true);
            }
            ((Map<?, ?>) fPregenSlots.get(null)).remove(e.citizen.getUUID());
        } catch (Throwable ignored) {
        }
        try {
            ConversationManager.markNotBusy(e.citizen);
        } catch (Throwable ignored) {
        }
        try {
            ColonistErrands.LOGGER.info("[AudioGate] Cut pregenerated clip of {} ({})",
                    e.citizen.getCitizenData() != null ? e.citizen.getCitizenData().getName() : e.citizen.getUUID(), why);
        } catch (Throwable ignored) {
        }
    }

    private static void purgeStale() {
        if (PREGEN.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        PREGEN.entrySet().removeIf(en -> now - en.getValue().startedAt > PREGEN_STALE_MS);
    }
}
