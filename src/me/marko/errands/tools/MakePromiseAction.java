package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.PromiseStore;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class MakePromiseAction extends PlayerFunctionAction {

    public MakePromiseAction() {
        super("make_promise",
                "The player makes YOU a concrete promise ('I promise I'll bring you 10 bread', 'I'll build you a "
                        + "better house in three days'). Write it down so you NEVER forget it: you will bring it up "
                        + "in future conversations and remind the player when it is due. Pass the promise as one "
                        + "short sentence in your own words; pass due_in_days ONLY if the player named a timeframe "
                        + "(in colony days). Do not record vague smalltalk ('I'll visit again sometime') - only real "
                        + "promises. React naturally (grateful, or skeptical if they already broke promises). "
                        + "Set 'about' to what the promise addresses: housing (a house for you), food, health "
                        + "(healing/hospital), work (a job for you) or general - while that promise is open and not "
                        + "overdue you PATIENTLY stop pestering the player about that problem.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("promise", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                    put("due_in_days", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                    put("about", new me.sshcrack.gemini_live_lib.gson.properties.EnumProperty(
                            java.util.List.of("housing", "food", "health", "work", "general"), false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null || parameters == null || !parameters.has("promise")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'promise'.");
            return result;
        }
        String text = parameters.get("promise").getAsString().trim();
        if (text.isEmpty() || text.length() > 200) {
            result.addProperty("success", false);
            result.addProperty("error", "The promise must be one short sentence.");
            return result;
        }
        int dueInDays = 0;
        try {
            if (parameters.has("due_in_days")) {
                dueInDays = Math.max(0, Math.min(365, parameters.get("due_in_days").getAsInt()));
            }
        } catch (Throwable ignored) {
        }
        String about = "general";
        try {
            if (parameters.has("about")) {
                about = parameters.get("about").getAsString().trim().toLowerCase();
            }
        } catch (Throwable ignored) {
        }
        // Multiplayer: remember WHO promised (Marko plays with his girlfriend and sister).
        String account = me.marko.errands.PlayerIdentityBlock.conversingPlayerName(citizen);
        String who = account == null ? "The player" : me.marko.errands.AliasStore.display(account);
        String err = PromiseStore.add(data.getName(), text, dueInDays, about, account);
        if (err != null) {
            result.addProperty("success", false);
            result.addProperty("error", err);
            return result;
        }
        try {
            ((CitizenDataMemoryExtended) data).mc_talking$getOrInitializeMemory()
                    .addEvent(who + " promised you: \"" + text + "\""
                            + (dueInDays > 0 ? " (within " + dueInDays + " days)" : "")
                            + ". You wrote it down - it is " + who + "'s promise, nobody else's.");
        } catch (Throwable ignored) {
        }
        result.addProperty("success", true);
        result.addProperty("info", "Promise by " + who + " recorded: \"" + text + "\""
                + (dueInDays > 0 ? " (due in " + dueInDays + " colony days, today is day " + PromiseStore.currentDay() + ")" : "")
                + ". You will remember it is " + who + "'s promise, remind THEM (and only them) if it becomes "
                + "overdue - and until then you PATIENTLY stop complaining about that problem (you trust them). "
                + "Acknowledge it warmly in one sentence - do not repeat the whole promise back word for word.");
        return result;
    }
}
