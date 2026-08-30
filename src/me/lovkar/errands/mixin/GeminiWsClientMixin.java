package me.lovkar.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.AudioGate;
import me.lovkar.errands.ErrandManager;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.GeminiStream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 */
@Mixin(targets = "me.sshcrack.mc_talking.manager.GeminiWsClient", remap = false)
public abstract class GeminiWsClientMixin {

    @Shadow(remap = false)
    public abstract AbstractEntityCitizen getEntity();

    @Shadow(remap = false)
    @Final
    protected GeminiStream stream;

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
        try {
            AudioGate.onGenerationComplete(this.getEntity().getUUID());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void colonist_errands$clearGateOnClose(CallbackInfo ci) {
        try {
            AudioGate.clear(this.getEntity().getUUID());
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

    /** Learn rejected voices from Gemini's close reason (code 1007). */
    @Inject(method = "onClose", at = @At("HEAD"), remap = false, require = 0)
    private void colonist_errands$learnBrokenVoice(int code, String reason, boolean remote, CallbackInfo ci) {
        try {
            me.lovkar.errands.VoiceFix.noteCloseReason(code, reason);
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
