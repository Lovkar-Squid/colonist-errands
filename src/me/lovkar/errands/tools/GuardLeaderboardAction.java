package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.GuardScore;
import me.lovkar.errands.Texts;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;

public class GuardLeaderboardAction extends ErrandCommand {

    public GuardLeaderboardAction() {
        super("guard_leaderboard",
                "The player asks about the GUARD LEADERBOARD, the best guard, guard scores or kill counts "
                        + "('who is my best guard?', 'show me the guard leaderboard', 'how are the guards ranking?'). "
                        + "Returns the top guards with their combat scores (raider kills 15 pts, monster kills 10, "
                        + "minus damage taken, capped at half of what they earned). Announce it with flair, like a tournament herald - names and points, "
                        + "not a dry list. Optional 'sidebar': true shows the live leaderboard on everyone's screen, "
                        + "false hides it ('put the leaderboard on screen' / 'hide the leaderboard').",
                params("sidebar", bool(false)), RankGuard.GROUP_CHAT);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        try {
            String board = GuardScore.leaderboardText(colony.getID());
            String sidebarNote = "";
            if (parameters != null && parameters.has("sidebar")) {
                boolean show = parameters.get("sidebar").getAsBoolean();
                net.minecraft.server.MinecraftServer server = citizen.getServer();
                if (server != null) {
                    int colonyId = colony.getID();
                    server.submit(() -> GuardScore.setSidebar(server, colonyId, show));
                    sidebarNote = show ? " The live leaderboard is now shown on screen."
                            : " The on-screen leaderboard is now hidden.";
                }
            }
            result.addProperty("success", true);
            result.addProperty("info", board + sidebarNote + Texts.SILENT);
        } catch (Throwable t) {
            result.addProperty("success", false);
            result.addProperty("error", "Could not read the leaderboard right now.");
        }
        return result;
    }
}
