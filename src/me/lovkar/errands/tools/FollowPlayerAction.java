package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class FollowPlayerAction extends PlayerFunctionAction {

    public FollowPlayerAction() {
        super("follow_player",
                "Start following the player you are talking to, staying close to them as they move (for up to 5 minutes). "
                        + "Use when the player asks you to come with them or follow them. "
                        + "The following starts as soon as the conversation ends, so say a short goodbye and call leave_conversation.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        ErrandManager.startFollowErrand(citizen, playerId);
        result.addProperty("success", true);
        result.addProperty("info", "You will follow the player once this conversation ends (up to 5 minutes, or until told to stop)."
                + Texts.GOODBYE);
        return result;
    }
}
