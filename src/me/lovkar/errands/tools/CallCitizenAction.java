package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.Citizens;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.lovkar.errands.tc.Talk;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.UUID;

public class CallCitizenAction extends ErrandCommand {

    public CallCitizenAction() {
        super("call_citizen",
                "Send for a SPECIFIC colonist BY NAME: the player asks for someone ('call Elyse for me', "
                        + "'send Hada over', 'I want to talk to the builder Rodbertus'). That colonist walks to the "
                        + "player and starts a conversation on arrival. Pass the name exactly as the player said it "
                        + "(first name is enough). For groups of guards use {summon_guards}/{gather_at} instead.",
                params("name", string(true)), RankGuard.GROUP_ERRANDS);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("name")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'name'.");
            return result;
        }
        UUID playerId = player.getUUID();
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        String query = parameters.get("name").getAsString();
        Citizens.Match match = Citizens.findByName(colony, citizen, query);
        if (match == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No colonist named '" + query + "' lives here. Tell the player honestly.");
            return result;
        }
        if (match.data.getEntity().isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", match.data.getName() + " is not around right now (not loaded). "
                    + "Suggest the player looks near their home or workplace.");
            return result;
        }
        AbstractEntityCitizen target = match.data.getEntity().get();
        try {
            Talk.remember(match.data, "The player sent for you personally - you are walking over to talk to them now.");
        } catch (Throwable ignored) {
        }
        ErrandManager.enqueueContactPlayer(target, playerId);
        String note = match.totalMatches > 1
                ? " (Note: " + match.totalMatches + " colonists matched that name - the nearest one was picked.)"
                : "";
        result.addProperty("success", true);
        result.addProperty("info", match.data.getName() + " has been sent for and is on their way to the player."
                + note + Texts.GOODBYE);
        return result;
    }
}
