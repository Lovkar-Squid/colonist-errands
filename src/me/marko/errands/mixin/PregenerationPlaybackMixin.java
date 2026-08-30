package me.marko.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.AudioGate;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.GeminiStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pregenerated greeting/delivery clips play on a bare GeminiStream with no live
 * Gemini session behind them - nothing listens to the microphone, so the clip
 * always played to the end even when the player started talking over it.
 *
 * We capture every clip's stream into AudioGate so it can be cut the moment the
 * player speaks nearby (McTalkingVoicechatPluginMixin) or the citizen's live
 * session starts talking (GeminiWsClientMixin).
 */
@Mixin(targets = "me.sshcrack.mc_talking.pregen.PregenerationPlayback", remap = false)
public abstract class PregenerationPlaybackMixin {

    private static final ThreadLocal<AbstractEntityCitizen> colonist_errands$CURRENT = new ThreadLocal<>();

    @Redirect(
            method = "playAudioIfPossible",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/ConversationManager;markBusy(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;)V")
    )
    private static void colonist_errands$captureCitizen(AbstractEntityCitizen citizen) {
        colonist_errands$CURRENT.set(citizen);
        ConversationManager.markBusy(citizen);
    }

    @Redirect(
            method = "playAudioIfPossible",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/manager/GeminiStream;addGeminiPcmWithPitch([BI)Z")
    )
    private static boolean colonist_errands$registerStream(GeminiStream stream, byte[] data, int sampleRate) {
        try {
            AbstractEntityCitizen citizen = colonist_errands$CURRENT.get();
            colonist_errands$CURRENT.remove();
            AudioGate.registerPregen(citizen, stream);
        } catch (Throwable ignored) {
        }
        return stream.addGeminiPcmWithPitch(data, sampleRate);
    }
}
