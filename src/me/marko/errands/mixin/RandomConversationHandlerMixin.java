package me.marko.errands.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.C2cAudioFollower;
import me.sshcrack.mc_talking.ConversationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Marko's rule: random citizen-to-citizen chats are for citizens who have
 * nothing to do - the unemployed and children any time, workers only after
 * daylight work hours, guards never. Redirects both canCitizenSpeak checks
 * (initiator + partner pick) inside the random-conversation scan, so busy
 * workers are simply never chosen for gossip.
 */
@Mixin(targets = "me.sshcrack.mc_talking.handler.RandomConversationHandler", remap = false)
public abstract class RandomConversationHandlerMixin {

    @Redirect(
            method = "checkForRandomConversations",
            at = @At(value = "INVOKE",
                    target = "Lme/sshcrack/mc_talking/ConversationManager;canCitizenSpeak(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;)Z")
    )
    private static boolean colonist_errands$onlyFreeCitizensChat(AbstractEntityCitizen citizen) {
        return ConversationManager.canCitizenSpeak(citizen) && C2cAudioFollower.isFreeToChat(citizen);
    }
}
