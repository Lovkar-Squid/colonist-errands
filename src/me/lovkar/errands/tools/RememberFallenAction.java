package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.Fallen;
import me.lovkar.errands.Texts;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The colony's roll of honour: who died, how, and what they did first. */
public class RememberFallenAction extends PlayerFunctionAction {

    public RememberFallenAction() {
        super("remember_fallen",
                "The player asks about the colony's DEAD - who has been lost, who died in the raid, "
                        + "'tell me about the fallen', 'who did we lose?', 'do you remember him?'. Returns this "
                        + "colony's roll of honour: names, how each one died and what they had done, guards' kill "
                        + "records included. Tell it the way someone who knew them would - pick out a name or two "
                        + "and say something real about them, do not read the whole list out.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        try {
            result.addProperty("success", true);
            result.addProperty("info", Fallen.memorialText(colony.getID()) + Texts.SILENT);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Memorial] remember_fallen failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "I could not bring their names to mind just now.");
        }
        return result;
    }
}
