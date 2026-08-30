package me.lovkar.errands.mixin;

import me.lovkar.errands.AliasStore;
import me.lovkar.errands.PromiseStore;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Appends to every citizen roleplay prompt:
 *  - the players' preferred names ("call me Lovkar") - all citizens use them
 *    immediately, no rumor spreading needed
 *  - THIS citizen's open/recent promises from the player (Lovkar's idea #22),
 *    so citizens bring them up, remind about deadlines, thank or sulk.
 */
@Mixin(targets = "me.sshcrack.mc_talking.api.prompt.CitizenPromptService", remap = false)
public abstract class CitizenPromptServiceMixin {

    @Inject(method = "generateCitizenRoleplayPrompt", at = @At("RETURN"), cancellable = true)
    private static void colonist_errands$appendAliases(CitizenPromptView view, CallbackInfoReturnable<String> cir) {
        colonist_errands$append(view, cir);
    }

    @Inject(method = "generateSystemControlledRoleplayPrompt", at = @At("RETURN"), cancellable = true)
    private static void colonist_errands$appendAliases2(CitizenPromptView view, CallbackInfoReturnable<String> cir) {
        colonist_errands$append(view, cir);
    }

    private static void colonist_errands$append(CitizenPromptView view, CallbackInfoReturnable<String> cir) {
        try {
            if (cir.getReturnValue() == null) {
                return;
            }
            String block = AliasStore.promptBlock();
            try {
                block = block + me.lovkar.errands.PlayerIdentityBlock.build(view);
            } catch (Throwable ignored) {
            }
            try {
                if (view != null) {
                    block = block + PromiseStore.promptBlockFor(view.name());
                }
            } catch (Throwable ignored) {
            }
            if (!block.isEmpty()) {
                cir.setReturnValue(cir.getReturnValue() + block);
            }
        } catch (Throwable ignored) {
        }
    }
}
