package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.BuildWatch;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.Texts;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** "How is the building going?" / "Where are you stuck?" - real stage and coordinates. */
public class BuildStatusAction extends PlayerFunctionAction {

    public BuildStatusAction() {
        super("build_status",
                "The player asks how a BUILD is going or why it is not finishing ('how's the kitchen coming?', "
                        + "'where are you stuck?', 'why is the builder not moving?', 'what are the builders working "
                        + "on?'). Returns every builder's current work order, which stage they are on, where they "
                        + "are standing, and how long they have made no progress. A build stuck near the end is "
                        + "almost never missing materials - the builder cannot reach the next spot.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server != null && !server.isSameThread()) {
                info = server.submit(() -> BuildWatch.report(colony)).join();
            } else {
                info = BuildWatch.report(colony);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Build] build_status failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "I could not read the build orders right now.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }
}
