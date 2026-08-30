package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class EveryoneHomeAction extends PlayerFunctionAction {

    private static final int MAX_SENT = 60;

    public EveryoneHomeAction() {
        super("everyone_home",
                "Curfew: send every colonist (who has a home) to their house. Guards stay on duty. "
                        + "Use when the player orders everyone to go home, e.g. because of danger or nightfall. "
                        + "Citizens walk off one after another; they resume their routine after arriving (or ~6 minutes). "
                        + "Afterwards say a short goodbye and call leave_conversation.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        int count = 0;
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd == null || cd.getHomeBuilding() == null) continue;
            Optional<AbstractEntityCitizen> opt = cd.getEntity();
            if (opt == null || opt.isEmpty()) continue;
            AbstractEntityCitizen c = opt.get();
            if (!c.isAlive() || c.isRemoved()) continue;
            if (cd.getWorkBuilding() instanceof AbstractBuildingGuards) continue; // guards keep guarding
            boolean isSpeaker = c.getUUID().equals(citizen.getUUID());
            if (!isSpeaker && ConversationManager.isCitizenBusy(c)) continue;
            if (ErrandManager.hasErrand(c)) continue;
            if (count >= MAX_SENT) break;
            ErrandManager.enqueuePosErrand(c, cd.getHomeBuilding().getPosition(), "home", 20 * 360, 25.0);
            count++;
        }
        result.addProperty("success", true);
        result.addProperty("count", count);
        result.addProperty("info", count == 0
                ? "Nobody needed sending home."
                : count + " colonist(s) are heading home now (one after another). You go home too, once this conversation ends."
                        + Texts.GOODBYE);
        return result;
    }
}
