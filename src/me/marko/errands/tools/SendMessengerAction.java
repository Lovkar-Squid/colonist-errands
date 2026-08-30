package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.ErrandBuildings;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class SendMessengerAction extends PlayerFunctionAction {

    public SendMessengerAction() {
        super("send_messenger",
                "Act as a messenger: you walk to a building (the nearest of the chosen type, e.g. 'guardtower' for "
                        + "the gatehouse guard), deliver the player's message to the worker there, and that worker then "
                        + "comes to the player and starts a conversation with them. "
                        + "Use when the player asks you to go tell someone at a building to come to them. "
                        + "building_name is REQUIRED: copy the EXACT word or phrase the player used for the destination building, "
                        + "verbatim and in the player's language (e.g. 'gatehouse', 'north tower') - named buildings are matched "
                        + "by it first and it takes priority over the type. "
                        + "You start walking when this conversation ends - say a short goodbye and call leave_conversation.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("target", new EnumProperty(ErrandBuildings.BUILDING_TYPES, true));
                    put("building_name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("target")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'target' parameter.");
            return result;
        }
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        String query = parameters.get("target").getAsString().trim().toLowerCase();
        String nameQuery = null;
        try {
            if (parameters.has("building_name")) {
                nameQuery = parameters.get("building_name").getAsString();
            }
        } catch (Throwable ignored) {
        }

        IBuilding building = ErrandBuildings.resolve(colony, citizen, query, nameQuery);
        String label = building != null ? ErrandBuildings.bestName(building) : query;
        if (building == null) {
            result.addProperty("success", false);
            result.addProperty("error", (nameQuery != null && !nameQuery.isBlank()
                    ? "No building named '" + nameQuery + "' and no building of type '" + query + "' exists in this colony. "
                            + "Tell the player they can rename a building in its GUI so you can find it by name."
                    : "No building of type '" + query + "' exists in this colony."));
            return result;
        }
        ErrandManager.startMessengerErrand(citizen, building, playerId, label);
        result.addProperty("success", true);
        result.addProperty("info", "You will walk to the " + label + " (" + building.getPosition().toShortString()
                + ") and send its worker to the player. This starts when the conversation ends." + Texts.GOODBYE);
        return result;
    }
}
