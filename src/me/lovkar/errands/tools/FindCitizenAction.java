package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.Citizens;
import me.lovkar.errands.ErrandBuildings;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class FindCitizenAction extends PlayerFunctionAction {

    public FindCitizenAction() {
        super("find_citizen",
                "The player asks WHERE a colonist is ('where is Hada?'). Reports who they are, their job and "
                        + "workplace, and where they are right now relative to the player. If the player wants to be "
                        + "TAKEN there ('lead me to her', 'take me to him'), pass lead=true - then you personally "
                        + "guide the player to that colonist (you wait whenever the player falls behind).",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                    put("lead", new PrimitiveProperty(PrimitiveProperty.Type.BOOLEAN, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("name")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'name'.");
            return result;
        }
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        MinecraftServer server = citizen.getServer();
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);
        String query = parameters.get("name").getAsString();
        Citizens.Match match = Citizens.findByName(colony, citizen, query);
        if (match == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No colonist named '" + query + "' lives in this colony.");
            return result;
        }
        boolean lead = false;
        try {
            lead = parameters.has("lead") && parameters.get("lead").getAsBoolean();
        } catch (Throwable ignored) {
        }

        StringBuilder info = new StringBuilder(match.data.getName());
        try {
            IBuilding wb = match.data.getWorkBuilding();
            if (match.data.getJob() != null && wb != null) {
                info.append(" works at the ").append(ErrandBuildings.bestName(wb));
            } else {
                info.append(" has no job");
            }
        } catch (Throwable ignored) {
        }

        if (match.data.getEntity().isPresent()) {
            AbstractEntityCitizen target = match.data.getEntity().get();
            BlockPos pos = target.blockPosition();
            if (player != null) {
                info.append(" and is currently ").append(Citizens.directionFrom(player.blockPosition(), pos))
                        .append(" (around ").append(pos.toShortString()).append(")");
            } else {
                info.append(" and is currently around ").append(pos.toShortString());
            }
            if (target.isSleeping()) {
                info.append(", asleep right now");
            }
            info.append(".");
            if (lead && player != null) {
                ErrandManager.startGuideErrand(citizen, target, playerId, match.data.getName());
                result.addProperty("success", true);
                result.addProperty("info", info + " You will personally LEAD the player to them, starting when "
                        + "this conversation ends - tell the player to follow you." + Texts.GOODBYE);
                return result;
            }
        } else {
            info.append(", but they are nowhere to be seen right now (perhaps at ")
                    .append(match.data.getHomeBuilding() != null
                            ? "home, " + match.data.getHomeBuilding().getPosition().toShortString()
                            : "an unloaded part of the colony")
                    .append(").");
            if (lead) {
                result.addProperty("success", false);
                result.addProperty("error", info + " You cannot lead the player to someone who is not around.");
                return result;
            }
        }
        if (match.totalMatches > 1) {
            info.append(" (").append(match.totalMatches).append(" colonists matched that name; this is the nearest.)");
        }
        result.addProperty("success", true);
        result.addProperty("info", info + " Answer the player naturally with this.");
        return result;
    }
}
