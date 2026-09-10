package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.happiness.ExpirationBasedHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier;
import me.lovkar.errands.PromiseStore;
import me.lovkar.errands.tc.Talk;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;

public class ResolvePromiseAction extends ErrandCommand {

    public ResolvePromiseAction() {
        super("resolve_promise",
                "Close your oldest open promise made by the PERSON SPEAKING TO YOU (their own promises are resolved "
                        + "first; only if they have none, the oldest from anyone). kept=true when it was fulfilled "
                        + "(they brought/did what was promised - thank them warmly). kept=false when it is openly "
                        + "broken or cancelled ('I can't keep that promise') - you may be visibly disappointed. Call "
                        + "it only when fulfillment or cancellation actually happened in the conversation, never on "
                        + "your own guess.",
                params("kept", bool(true)), RankGuard.GROUP_CHAT);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null || parameters == null || !parameters.has("kept")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'kept'.");
            return result;
        }
        boolean kept;
        try {
            kept = parameters.get("kept").getAsBoolean();
        } catch (Throwable t) {
            result.addProperty("success", false);
            result.addProperty("error", "'kept' must be true or false.");
            return result;
        }
        // Multiplayer: resolve the CURRENT speaker's own promise first.
        String account = player.getGameProfile().getName();
        PromiseStore.Promise p = PromiseStore.resolveOldest(data.getName(), kept, account);
        if (p == null) {
            result.addProperty("success", false);
            result.addProperty("error", "You have no open promises from anyone to resolve.");
            return result;
        }
        String text = p.text;
        String maker = PromiseStore.makerLabel(p);
        String crossNote = "";
        if (account != null && p.byPlayer != null && !account.equals(p.byPlayer)) {
            crossNote = " NOTE: that promise was made by " + maker + ", not by the person you are talking to - "
                    + "mention that ('so " + maker + " came through after all' style).";
        }
        try {
            Talk.remember(data, kept ? maker + " KEPT their promise: \"" + text + "\". You are grateful to " + maker + "."
                            : maker + " BROKE/cancelled their promise: \"" + text + "\". You are disappointed in " + maker + ".");
        } catch (Throwable ignored) {
        }
        // Kept/broken promises are hard facts feeding the per-player rapport too.
        try {
            String makerAccount = p.byPlayer != null && !p.byPlayer.isBlank() ? p.byPlayer : account;
            me.lovkar.errands.RelationStore.promiseResolved(data.getName(), makerAccount, kept, PromiseStore.currentDay());
        } catch (Throwable ignored) {
        }
        // Lovkar's idea #24: real gameplay consequence - a temporary happiness
        // modifier, same mechanism MineColonies quests use for their rewards.
        String moodNote = "";
        try {
            data.getCitizenHappinessHandler().addModifier(new ExpirationBasedHappinessModifier(
                    "promise", 2.0, new StaticHappinessSupplier(kept ? 2.0 : 0.4), 3));
            moodNote = kept ? " Your mood got a real BOOST for the next 3 days."
                    : " Your mood took a real HIT for the next 3 days.";
            me.lovkar.errands.ColonistErrands.LOGGER.info("[Promises] {} happiness {} for 3 days",
                    data.getName(), kept ? "boosted" : "penalized");
        } catch (Throwable t) {
            me.lovkar.errands.ColonistErrands.LOGGER.warn("promise happiness modifier failed", t);
        }
        result.addProperty("success", true);
        result.addProperty("info", (kept
                ? maker + "'s promise \"" + text + "\" marked as KEPT - thank them sincerely in one sentence."
                : maker + "'s promise \"" + text + "\" marked as BROKEN - you may show honest, mild disappointment in one sentence.")
                + crossNote + moodNote + " Your remaining open promises (if any) stay tracked.");
        return result;
    }
}
