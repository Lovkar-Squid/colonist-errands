package me.lovkar.errands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lovkar's report: colonists sometimes wish him "good morning" in the evening.
 * <p>
 * The cause is baked into how pregenerated greetings work: the clip is written
 * and voiced ahead of time, with the prompt describing the world AT THAT MOMENT,
 * and then sits in a cache until the player happens to walk past - which can be
 * many in-game hours later. A greeting recorded at sunrise is still a sunrise
 * greeting at dusk.
 * <p>
 * Rather than throwing away perfectly good audio, we take the clock out of the
 * clips: every pregeneration prompt now asks for a greeting that works at any
 * hour. Live conversations still know the real time and greet properly.
 */
@Mixin(targets = "me.sshcrack.mc_talking.pregen.PregenerationTaskService", remap = false)
public abstract class PregenerationTaskServiceMixin {

    private static final String RULE =
            " IMPORTANT: this line is recorded now but may be heard hours later, so it must work at ANY time of day. "
                    + "Never say good morning, good afternoon, good evening or good night, and never refer to the "
                    + "time, the light, the weather or the meal you are about to have. Greet in a way that fits "
                    + "dawn and midnight alike.";

    @ModifyVariable(method = "startPregenerationIfPossible", at = @At("HEAD"), argsOnly = true, index = 1)
    private static String colonist_errands$timelessGreeting(String prompt) {
        try {
            if (prompt == null || prompt.contains("must work at ANY time of day")) {
                return prompt;
            }
            return prompt + RULE;
        } catch (Throwable t) {
            return prompt;
        }
    }
}
