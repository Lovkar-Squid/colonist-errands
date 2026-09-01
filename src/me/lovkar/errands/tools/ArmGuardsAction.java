package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.GuardGearCheck;
import me.lovkar.errands.Texts;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** "Get my guards armed" - orders every missing piece of kit through the colony. */
public class ArmGuardsAction extends PlayerFunctionAction {

    public ArmGuardsAction() {
        super("arm_guards",
                "The player wants the guards EQUIPPED - 'arm all my guards', 'get the guards their armor', "
                        + "'prioritise arming the watch', 'order gear for whoever is missing it'. Files the colony's "
                        + "own equipment requests for every missing armor piece and weapon at once, instead of waiting "
                        + "for each guard to notice at their hut. To only ASK who is missing what, use guard_gear.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server != null && !server.isSameThread()) {
                info = server.submit(() -> GuardGearCheck.armAll(colony)).join();
            } else {
                info = GuardGearCheck.armAll(colony);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Gear] arm_guards failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "I could not put the order through to the quartermaster.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }
}
