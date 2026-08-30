package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.Citizens;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class CallCitizenAction extends PlayerFunctionAction {

    public CallCitizenAction() {
        super("call_citizen",
                "Send for a SPECIFIC colonist BY NAME: the player asks for someone ('call Elyse for me', "
                        + "'send Hada over', 'I want to talk to the builder Rodbertus'). That colonist walks to the "
                        + "player and starts a conversation on arrival. Pass the name exactly as the player said it "
                        + "(first name is enough). For groups of guards use summon_guards/gather_at instead.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
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
            ((CitizenDataMemoryExtended) match.data).mc_talking$getOrInitializeMemory()
                    .addEvent("The player sent for you personally - you are walking over to talk to them now.");
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
