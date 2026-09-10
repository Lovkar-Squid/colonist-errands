package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class FollowPlayerAction extends ErrandCommand {

    public FollowPlayerAction() {
        super("follow_player",
                "Start following the player you are talking to, staying close to them as they move (for up to 5 minutes). "
                        + "Use when the player asks you to come with them or follow them. "
                        + "The following starts as soon as the conversation ends, so say a short goodbye and call {leave_conversation}.", RankGuard.GROUP_ERRANDS);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        UUID playerId = player.getUUID();
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
