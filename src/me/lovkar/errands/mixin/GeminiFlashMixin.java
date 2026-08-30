package me.lovkar.errands.mixin;

import me.lovkar.errands.ColonistErrands;
import me.sshcrack.gemini_live_lib.misc.GeminiFlash;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes mc_talking's memory loss: gemini-flash-lite sometimes wraps its JSON
 * answer in markdown code fences (```json ... ```), which makes
 * GsonMemoryResponse.GSON.fromJson throw and the whole conversation's memories
 * get discarded. We strip the fences from every simple flash response so the
 * JSON always parses. Upstream (sshcrack/talking-colonists) is unfixed as of
 * 1.7.1 / main@2026-08-08.
 */
@Mixin(value = GeminiFlash.class, remap = false)
public abstract class GeminiFlashMixin {

    @Unique
    private static boolean colonist_errands$loggedOnce = false;

    @Inject(
            method = "sendFlashRequest(Ljava/lang/String;Ljava/lang/String;Lme/sshcrack/gemini_live_lib/misc/GeminiFlash$GenerateContentRequest;I)Ljava/lang/String;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void colonist_errands$stripMarkdownFences(CallbackInfoReturnable<String> cir) {
        String original = cir.getReturnValue();
        String cleaned = colonist_errands$strip(original);
        if (cleaned != null && original != null && !cleaned.equals(original)) {
            if (!colonist_errands$loggedOnce) {
                colonist_errands$loggedOnce = true;
                ColonistErrands.LOGGER.info("[ColonistErrands] Stripped markdown fences from a Gemini flash response (memory fix active)");
            }
            cir.setReturnValue(cleaned);
        }
    }

    @Unique
    private static String colonist_errands$strip(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        int fence = t.indexOf("```");
        if (fence < 0) {
            return s;
        }
        int contentStart = t.indexOf('\n', fence);
        if (contentStart < 0) {
            contentStart = fence + 3;
            if (t.regionMatches(true, contentStart, "json", 0, 4)) {
                contentStart += 4;
            }
        } else {
            contentStart += 1;
        }
        int closing = t.indexOf("```", contentStart);
        String inner = (closing >= 0 ? t.substring(contentStart, closing) : t.substring(contentStart)).trim();
        return inner.isEmpty() ? s : inner;
    }
}
