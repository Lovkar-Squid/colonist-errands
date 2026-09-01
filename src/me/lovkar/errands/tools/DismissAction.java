package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DismissAction extends PlayerFunctionAction {

    public DismissAction() {
        super("dismiss",
                "Stand-down order for the whole colony: releases ALL gathered/summoned citizens waiting at a "
                        + "gathering point, ends all guard escorts, and stands the defensive formation down - "
                        + "everyone returns to their normal duty. "
                        + "Use when the player says 'dismissed', 'you can all go', 'stand down', 'razpustite se'.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        int n = ErrandManager.dismissAll();
        result.addProperty("success", true);
        result.addProperty("released", n);
        result.addProperty("info", (n == 0
                ? "Nobody was assembled; everyone is already on their normal duty. Confirm briefly."
                : n + " assignment(s) released - everyone returns to their normal duty. Confirm briefly like a soldier.")
                + Texts.GOODBYE);
        return result;
    }
}
