package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class WaitHereAction extends PlayerFunctionAction {

    public WaitHereAction() {
        super("wait_here",
                "Stay and wait at your current spot for a number of minutes (1-15, default 2) instead of working. "
                        + "Use when the player asks you to wait somewhere or stay put. "
                        + "The waiting starts when the conversation ends; the player can end it early by telling you to stop. "
                        + "Say a short goodbye and call leave_conversation.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("minutes", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        int minutes = 2;
        try {
            if (parameters != null && parameters.has("minutes")) {
                minutes = parameters.get("minutes").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        minutes = Math.max(1, Math.min(15, minutes));
        ErrandManager.startWaitErrand(citizen, minutes);
        result.addProperty("success", true);
        result.addProperty("info", "You will wait at your current spot for " + minutes
                + " minute(s) once this conversation ends." + Texts.GOODBYE);
        return result;
    }
}
