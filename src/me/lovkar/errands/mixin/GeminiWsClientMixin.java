package me.lovkar.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.AudioGate;
import me.lovkar.errands.ErrandManager;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.GeminiStream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Three fixes around Gemini's audio lifecycle:
 *
 * 1. onTurnComplete fires when Gemini finishes GENERATING - but the goodbye
 *    audio is still playing. We delay the actual endConversation by ~2 seconds
 *    so citizens finish their goodbye. (endConversation is idempotent.)
 *
 * 2. Doubled goodbye: after leave_conversation's tool result the model
 *    generates one more time and re-says the goodbye. AudioGate is armed by
 *    the tool; once the goodbye generation completes we drop all further
 *    incoming audio of this conversation before it reaches the speaker.
 *
 * 3. Doubled answers on "Session token invalidated": mc_talking clears the
 *    session token and re-sends the last prompt, so the fresh session answers
 *    AGAIN. At exactly that point we drop the stale queued audio of the first
 *    answer - the re-answer then plays once.
 *
 * 4. close() no longer cuts the voice: the stream is drained by StreamDrain
 *    before it is closed, so the last sentence is heard to the end.
 *
 * 5. Sessions that never end: when Gemini aborts an idle non-player session
 *    (close 1008), mc_talking reconnects it forever and the citizen stands under
 *    "Listening" all night. SessionReaper ends such sessions instead - see the
 *    onClose hook and the activity flag below.
 */
@Mixin(targets = "me.sshcrack.mc_talking.manager.GeminiWsClient", remap = false)
public abstract class GeminiWsClientMixin implements me.lovkar.errands.SessionActivity {

    @Shadow(remap = false)
    public abstract AbstractEntityCitizen getEntity();

    @Shadow(remap = false)
    @Final
    protected GeminiStream stream;

    @Shadow(remap = false)
    private boolean intentionalClose;

    @Shadow(remap = false)
    protected boolean shouldEndConversation;

    /** Audio or a finished turn came out of the CURRENT connection (reset at every setup) - see SessionReaper. */
    @Unique
    private volatile boolean colonist_errands$spoke;

    /** When the current connection completed its setup; 0 before the first one. */
    @Unique
    private volatile long colonist_errands$setupAt;

    @Override
    public boolean colonist_errands$spoke() {
        return this.colonist_errands$spoke;
    }

    @Override
    public long colonist_errands$setupAt() {
        return this.colonist_errands$setupAt;
    }

    @Redirect(
            method = "onTurnComplete",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/ConversationManager;endConversation(Ljava/util/UUID;Z)V")
    )
    private void colonist_errands$gracefulEnd(UUID playerId, boolean sendMessage) {
        ErrandManager.runLater(40, () -> {
            try {
                ConversationManager.endConversation(playerId, sendMessage);
            } catch (Throwable ignored) {
            }
        });
    }

    @Inject(method = "onGeneratedAudio", at = @At("HEAD"), cancellable = true, remap = false)
    private void colonist_errands$gateAudio(byte[] data, int sampleRate, CallbackInfo ci) {
        this.colonist_errands$spoke = true;
        try {
            UUID id = this.getEntity().getUUID();
            // A live session started talking: any pregenerated clip of the same
            // citizen must yield instead of overlapping/doubling.
            AudioGate.stopPregen(id);
            if (AudioGate.shouldDropAudio(id)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "onGenerationComplete", at = @At("HEAD"), remap = false)
    private void colonist_errands$trackGenComplete(CallbackInfo ci) {
        this.colonist_errands$spoke = true;
        try {
            AudioGate.onGenerationComplete(this.getEntity().getUUID());
        } catch (Throwable ignored) {
        }
    }

    /** A finished turn counts as activity too (text-only turns produce no audio). */
    @Inject(method = "onTurnComplete", at = @At("HEAD"), remap = false, require = 0)
    private void colonist_errands$trackTurn(CallbackInfo ci) {
        this.colonist_errands$spoke = true;
    }

    /** A fresh connection starts with a clean slate - whatever it produces from here on counts. */
    @Inject(method = "onSetupComplete", at = @At("HEAD"), remap = false, require = 0)
    private void colonist_errands$freshConnection(CallbackInfo ci) {
        this.colonist_errands$spoke = false;
        this.colonist_errands$setupAt = System.currentTimeMillis();
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void colonist_errands$clearGateOnClose(CallbackInfo ci) {
        try {
            AudioGate.clear(this.getEntity().getUUID());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Lovkar: "sometimes they are still cut off mid-sentence." close() ends with
     * stream.close(), which empties the audio queue on the spot - and the session
     * is closed when the turn is GENERATED, well before it has been HEARD. Hand the
     * stream to StreamDrain instead, which lets it play out (and releases a held
     * reply in a citizen-to-citizen conversation) before closing it.
     */
    @Redirect(
            method = "close",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/manager/GeminiStream;close()V"),
            remap = false,
            require = 0
    )
    private void colonist_errands$drainThenClose(GeminiStream s) {
        try {
            me.lovkar.errands.StreamDrain.closeWhenDrained((me.sshcrack.mc_talking.manager.GeminiWsClient) (Object) this, s);
        } catch (Throwable t) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** A new session for a citizen cuts whatever they were still finishing - the player comes first. */
    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
    private void colonist_errands$newSession(me.sshcrack.mc_talking.manager.audio.AudioProvider audioProvider,
                                             AbstractEntityCitizen entity, CallbackInfo ci) {
        try {
            me.lovkar.errands.StreamDrain.newSessionFor(entity);
        } catch (Throwable ignored) {
        }
    }

    /**
     * mc_talking's voice lists contain names Gemini no longer accepts (e.g.
     * "Archid") - deterministic per-citizen picks made such citizens unable to
     * talk at all (close 1007, all retries identical). VoiceFix rerolls past a
     * learned blocklist.
     */
    @Redirect(
            method = "getSetup",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/config/AvailableAI;getRandomVoice(Ljava/util/UUID;Z)Ljava/lang/String;"),
            require = 0
    )
    private String colonist_errands$safeVoice(me.sshcrack.mc_talking.config.AvailableAI ai, UUID uuid, boolean female) {
        try {
            return me.lovkar.errands.VoiceFix.pickVoice(ai, uuid, female);
        } catch (Throwable t) {
            return ai.getRandomVoice(uuid, female);
        }
    }

    /**
     * Two things happen the moment Gemini closes a socket on us. VoiceFix learns
     * rejected voices from the close reason (code 1007). And SessionReaper decides
     * whether a NON-PLAYER session that Gemini aborted (code 1008 "The operation was
     * aborted", typically after a minute and three quarters of silence) deserves
     * mc_talking's reconnect at all: a solo line or live chat that had already been
     * asked to end, or that produced nothing since it connected, is ended for good
     * instead - otherwise it reconnects into an empty session, idles into the next
     * abort, and the citizen stands under "Listening" all night. Ending it here
     * means calling close(), which flips mc_talking's own intentionalClose flag, so
     * the rest of onClose below treats it as an intentional close and does not
     * reconnect. Player conversations are never touched.
     */
    @Inject(method = "onClose", at = @At("HEAD"), remap = false, require = 0)
    private void colonist_errands$learnBrokenVoice(int code, String reason, boolean remote, CallbackInfo ci) {
        try {
            me.lovkar.errands.VoiceFix.noteCloseReason(code, reason);
        } catch (Throwable ignored) {
        }
        try {
            me.lovkar.errands.SessionReaper.onAbnormalClose(
                    (me.sshcrack.mc_talking.manager.GeminiWsClient) (Object) this, code, reason,
                    this.intentionalClose, this.shouldEndConversation, this.colonist_errands$spoke);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Injected right where the "Session token invalidated" branch wipes the
     * stored session token (only place setSessionToken is called inside
     * onClose). The last prompt gets re-sent to a fresh session which will
     * answer again - so the not-yet-played audio of the first answer is stale.
     */
    @Inject(
            method = "onClose",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/conversations/memory/data/CitizenMemories;setSessionToken(Ljava/lang/String;)V"),
            remap = false,
            require = 0
    )
    private void colonist_errands$dropStaleOnInvalidatedSession(CallbackInfo ci) {
        try {
            AbstractEntityCitizen e = this.getEntity();
            String who = e != null && e.getCitizenData() != null ? e.getCitizenData().getName() : "citizen";
            AudioGate.clearQueuedAudio(this.stream, who);
        } catch (Throwable ignored) {
        }
    }
}
