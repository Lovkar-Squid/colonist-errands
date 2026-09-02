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
            try {
                // Lovkar's report: hungry citizens beg the player for food while
                // carrying edible food / while the restaurant can serve them, and
                // keep asking right after being handed some.
                if (view != null) {
                    block = block + me.lovkar.errands.FoodCheck.promptLine(view.name(), view.saturation());
                }
            } catch (Throwable ignored) {
            }
            try {
                if (view != null) {
                    block = block + me.lovkar.errands.SupplyCheck.promptLine(view.name());
                }
            } catch (Throwable ignored) {
            }
            try {
                if (view != null) {
                    block = block + me.lovkar.errands.GuardScore.promptLine(view.name());
                    block = block + me.lovkar.errands.GuardGearCheck.promptLine(view.name());
                }
            } catch (Throwable ignored) {
            }
            try {
                // Lovkar's idea: the colony's dead are remembered by name, and the
                // ones who died fighting are spoken of as brave.
                if (view != null) {
                    block = block + me.lovkar.errands.Fallen.promptBlock(view.name());
                }
            } catch (Throwable ignored) {
            }
            try {
                // Lovkar's idea: the university's work is the colony's shared story -
                // colonists know what is on the benches and what was just finished.
                if (view != null) {
                    block = block + me.lovkar.errands.ResearchWatcher.promptBlock(view.name());
                }
            } catch (Throwable ignored) {
            }
            try {
                // Lovkar's report: housed colonists still talked as if they slept in
                // the mud - a level 1 house scores 0.33 on MineColonies' housing
                // factor and gets described as a shack.
                if (view != null) {
                    block = block + me.lovkar.errands.HomeCheck.promptLine(view.name());
                }
            } catch (Throwable ignored) {
            }
            try {
                // Lovkar's report: "good morning" in the evening.
                if (view != null) {
                    block = block + me.lovkar.errands.TimeOfDay.promptLine(view.currentGameTimeTicks());
                }
            } catch (Throwable ignored) {
            }
            try {
                // Lovkar's report: a builder said she was stuck on a residence
                // while standing on a half-finished kitchen.
                if (view != null) {
                    block = block + me.lovkar.errands.BuildWatch.promptLine(view.name());
                    block = block + me.lovkar.errands.BedCheck.promptLine(view.name());
                    block = block + me.lovkar.errands.ConstructionWatcher.promptBlock(view.name());
                    block = block + me.lovkar.errands.ColonyMap.promptBlock(view.name());
                }
            } catch (Throwable ignored) {
            }
            try {
                // Voyager mod (optional): a Voyager knows their Departure Point, what they are
                // waiting for and what the last expedition brought; everyone else gets colony news.
                if (view != null) {
                    block = block + me.lovkar.errands.VoyagerLore.promptBlock(view.name());
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
