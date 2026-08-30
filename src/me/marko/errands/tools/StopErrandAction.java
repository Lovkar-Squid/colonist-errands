package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.ErrandManager;
import me.marko.errands.WatchManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StopErrandAction extends PlayerFunctionAction {

    public StopErrandAction() {
        super("stop_errand",
                "Stop your current errand (walking somewhere, waiting, following, or escorting the player as a guard) "
                        + "and return to your normal routine. Use when the player says stop, that's enough, you can go, "
                        + "you're free, or go back to work. After stopping, if the player is done talking, also call "
                        + "leave_conversation.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        boolean hadErrand = ErrandManager.cancel(citizen);
        boolean hadGuard = ErrandManager.stopGuardFollowFor(citizen);
        int watches = WatchManager.clearFor(citizen);
        result.addProperty("success", true);
        if (watches > 0) {
            result.addProperty("watches_cancelled", watches);
        }
        if (hadGuard) {
            result.addProperty("info", "Escort ended; you and your tower return to your previous guard duty."
                    + (hadErrand ? " Your other errand was cancelled too." : ""));
        } else if (hadErrand) {
            result.addProperty("info", "Errand cancelled; you return to your normal routine.");
        } else {
            result.addProperty("info", "You had no active errand; you continue your normal routine.");
        }
        return result;
    }
}
