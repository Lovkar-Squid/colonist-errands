package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.happiness.ExpirationBasedHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier;
import me.marko.errands.PromiseStore;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class ResolvePromiseAction extends PlayerFunctionAction {

    public ResolvePromiseAction() {
        super("resolve_promise",
                "Close your oldest open promise made by the PERSON SPEAKING TO YOU (their own promises are resolved "
                        + "first; only if they have none, the oldest from anyone). kept=true when it was fulfilled "
                        + "(they brought/did what was promised - thank them warmly). kept=false when it is openly "
                        + "broken or cancelled ('I can't keep that promise') - you may be visibly disappointed. Call "
                        + "it only when fulfillment or cancellation actually happened in the conversation, never on "
                        + "your own guess.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("kept", new PrimitiveProperty(PrimitiveProperty.Type.BOOLEAN, true));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
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
        String account = me.marko.errands.PlayerIdentityBlock.conversingPlayerName(citizen);
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
            ((CitizenDataMemoryExtended) data).mc_talking$getOrInitializeMemory()
                    .addEvent(kept ? maker + " KEPT their promise: \"" + text + "\". You are grateful to " + maker + "."
                            : maker + " BROKE/cancelled their promise: \"" + text + "\". You are disappointed in " + maker + ".");
        } catch (Throwable ignored) {
        }
        // Kept/broken promises are hard facts feeding the per-player rapport too.
        try {
            String makerAccount = p.byPlayer != null && !p.byPlayer.isBlank() ? p.byPlayer : account;
            me.marko.errands.RelationStore.promiseResolved(data.getName(), makerAccount, kept, PromiseStore.currentDay());
        } catch (Throwable ignored) {
        }
        // Marko's idea #24: real gameplay consequence - a temporary happiness
        // modifier, same mechanism MineColonies quests use for their rewards.
        String moodNote = "";
        try {
            data.getCitizenHappinessHandler().addModifier(new ExpirationBasedHappinessModifier(
                    "promise", 2.0, new StaticHappinessSupplier(kept ? 2.0 : 0.4), 3));
            moodNote = kept ? " Your mood got a real BOOST for the next 3 days."
                    : " Your mood took a real HIT for the next 3 days.";
            me.marko.errands.ColonistErrands.LOGGER.info("[Promises] {} happiness {} for 3 days",
                    data.getName(), kept ? "boosted" : "penalized");
        } catch (Throwable t) {
            me.marko.errands.ColonistErrands.LOGGER.warn("promise happiness modifier failed", t);
        }
        result.addProperty("success", true);
        result.addProperty("info", (kept
                ? maker + "'s promise \"" + text + "\" marked as KEPT - thank them sincerely in one sentence."
                : maker + "'s promise \"" + text + "\" marked as BROKEN - you may show honest, mild disappointment in one sentence.")
                + crossNote + moodNote + " Your remaining open promises (if any) stay tracked.");
        return result;
    }
}
