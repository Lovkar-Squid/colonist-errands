package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;

public class WaitHereAction extends ErrandCommand {

    public WaitHereAction() {
        super("wait_here",
                "Stay and wait at your current spot for a number of minutes (1-15, default 2) instead of working. "
                        + "Use when the player asks you to wait somewhere or stay put. "
                        + "The waiting starts when the conversation ends; the player can end it early by telling you to stop. "
                        + "Say a short goodbye and call {leave_conversation}.",
                params("minutes", integer(false)), RankGuard.GROUP_ERRANDS);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
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
