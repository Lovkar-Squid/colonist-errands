package me.lovkar.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import me.lovkar.errands.C2cAudioFollower;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import me.sshcrack.mc_talking.conversations.CitizenConversation;

import java.util.List;

/**
 * Flash/TTS citizen-to-citizen conversations play on a STATIC locational audio
 * channel placed where the participants stood when generation began - by the
 * time the dialogue actually plays, the citizens have wandered off and the
 * voices hang in empty air. We capture the channel here and let
 * C2cAudioFollower keep it glued to the speakers.
 */
@Mixin(targets = "me.sshcrack.mc_talking.conversations.CitizenConversation", remap = false)
public abstract class CitizenConversationMixin {

    @Shadow(remap = false)
    @Final
    private List<AbstractEntityCitizen> participants;

    @Shadow(remap = false)
    private me.sshcrack.mc_talking.manager.GeminiStream stream;

    /**
     * Lovkar: "sometimes they are still cut off mid-sentence." In the Flash/TTS
     * path the audio arrives in chunks, and the stream only moves them to the
     * player once 192,000 bytes have piled up - four seconds of speech. Whatever
     * is left below that line when the last chunk lands is never played unless
     * somebody flushes, and nobody did: the goodbye was silently dropped from
     * every Flash conversation. ENDED is exactly the moment the last chunk is in.
     */
    /** A live citizen-to-citizen conversation is small talk: it may not evict anyone (see SlotGuard). */
    @Inject(method = "performLiveWebsocketConversation", at = @At("HEAD"), remap = false, require = 0)
    private void colonist_errands$smallTalkBegins(CallbackInfo ci) {
        me.lovkar.errands.SlotGuard.enter();
    }

    @Inject(method = "performLiveWebsocketConversation", at = @At("RETURN"), remap = false, require = 0)
    private void colonist_errands$smallTalkEnds(CallbackInfo ci) {
        me.lovkar.errands.SlotGuard.exit();
    }

    @Inject(method = "setState", at = @At("HEAD"), remap = false, require = 0)
    private void colonist_errands$flushTail(CitizenConversation.ConversationState newState, CallbackInfo ci) {
        try {
            if (newState == CitizenConversation.ConversationState.ENDED && this.stream != null) {
                this.stream.flushAudio();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "constructLocationalAudioChannel", at = @At("RETURN"), remap = false)
    private void colonist_errands$followSpeakers(CallbackInfoReturnable<LocationalAudioChannel> cir) {
        try {
            LocationalAudioChannel channel = cir.getReturnValue();
            if (channel != null) {
                C2cAudioFollower.register(this, channel, this.participants);
            }
        } catch (Throwable ignored) {
        }
    }
}
