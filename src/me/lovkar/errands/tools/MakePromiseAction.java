package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.PromiseStore;
import me.lovkar.errands.tc.Talk;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;

public class MakePromiseAction extends ErrandCommand {

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
                params("promise", string(true), "due_in_days", integer(false),
                        "about", enumOf(java.util.List.of("housing", "food", "health", "work", "general"), false)), RankGuard.GROUP_CHAT);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
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
        // Multiplayer: remember WHO promised (Lovkar plays with his girlfriend and sister).
        String account = player.getGameProfile().getName();
        String who = account == null ? "The player" : me.lovkar.errands.AliasStore.display(account);
        String err = PromiseStore.add(data.getName(), text, dueInDays, about, account);
        if (err != null) {
            result.addProperty("success", false);
            result.addProperty("error", err);
            return result;
        }
        try {
            Talk.remember(data, who + " promised you: \"" + text + "\""
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
