package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class ComeHereAction extends PlayerFunctionAction {

    public ComeHereAction() {
        super("come_here",
                "Walk once to the exact spot where the player is standing right now, then stay there "
                        + "(you do NOT keep following them - use follow_player for that). "
                        + "The walk starts as soon as the conversation ends, so say a short goodbye and call leave_conversation.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        MinecraftServer server = citizen.getServer();
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        if (player.level() != citizen.level()) {
            result.addProperty("success", false);
            result.addProperty("error", "The player is in another dimension.");
            return result;
        }
        ErrandManager.startPosErrand(citizen, player.blockPosition(), "player's spot", 20 * 240, 6.25);
        result.addProperty("success", true);
        result.addProperty("info", "You will walk to where the player stands now, once this conversation ends." + Texts.GOODBYE);
        return result;
    }
}
