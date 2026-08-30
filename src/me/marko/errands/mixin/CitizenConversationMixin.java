package me.marko.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import me.marko.errands.C2cAudioFollower;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
