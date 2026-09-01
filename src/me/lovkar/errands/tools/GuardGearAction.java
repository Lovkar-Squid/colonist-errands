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

/** "Are my guards armed?" - who is missing armor, a weapon or a shield. */
public class GuardGearAction extends PlayerFunctionAction {

    public GuardGearAction() {
        super("guard_gear",
                "The player asks whether the GUARDS ARE EQUIPPED ('are my guards armed?', 'do the guards have armor?', "
                        + "'who is missing gear?', 'why do my guards keep dying?'). Lists guards missing armor pieces, "
                        + "a weapon or a shield, with their hut level. Deliver it like a soldier reporting a supply "
                        + "problem - worst cases first, not a spreadsheet.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server != null && !server.isSameThread()) {
                info = server.submit(() -> GuardGearCheck.report(colony)).join();
            } else {
                info = GuardGearCheck.report(colony);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Gear] guard_gear failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "I could not check the armoury right now.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }
}
