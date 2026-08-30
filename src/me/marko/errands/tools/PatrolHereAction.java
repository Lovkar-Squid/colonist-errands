package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardPatrolModeSetting;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;

/**
 * Voice-set patrol routes (Marko's idea #9): the player stands somewhere and
 * tells a guard "patrol here" - the point is added to the guard building's
 * MANUAL patrol route. More points are added the same way; "normal patrols"
 * resets back to automatic.
 */
public class PatrolHereAction extends PlayerFunctionAction {

    public PatrolHereAction() {
        super("patrol_here",
                "GUARDS ONLY: manage your tower's patrol route by voice. mode='start': begin a NEW manual patrol "
                        + "route with the player's current spot as its first point. mode='add': add the player's "
                        + "current spot as ANOTHER point to the route (player walks somewhere and says 'add this "
                        + "point too'). mode='reset': clear the manual route and return to normal automatic patrols. "
                        + "Your whole tower patrols the route. Fails politely if you are not a guard.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("mode", new EnumProperty(List.of("start", "add", "reset"), true));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null || !(data.getWorkBuilding() instanceof AbstractBuildingGuards tower)) {
            result.addProperty("success", false);
            result.addProperty("error", "You are not a guard - only guards patrol. Suggest the player tells a guard.");
            return result;
        }
        String mode = "start";
        try {
            if (parameters != null && parameters.has("mode")) {
                mode = parameters.get("mode").getAsString().trim().toLowerCase();
            }
        } catch (Throwable ignored) {
        }
        MinecraftServer server = citizen.getServer();
        java.util.UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);

        try {
            if (mode.equals("reset")) {
                tower.resetPatrolTargets();
                GuardPatrolModeSetting pm = tower.getSetting(AbstractBuildingGuards.PATROL_MODE);
                if (pm != null) {
                    pm.set(GuardPatrolModeSetting.AUTO);
                }
                tower.markDirty();
                ErrandManager.kickGuardAI(tower);
                ColonistErrands.LOGGER.info("[Patrol] {} reset to automatic patrols", data.getName());
                result.addProperty("success", true);
                result.addProperty("info", "Manual patrol route cleared - your tower is back on normal automatic patrols."
                        + Texts.GOODBYE);
                return result;
            }
            if (player == null) {
                result.addProperty("success", false);
                result.addProperty("error", "No player conversation is active.");
                return result;
            }
            BlockPos point = player.blockPosition();
            if (mode.equals("start")) {
                tower.resetPatrolTargets();
            }
            tower.addPatrolTarget(point);
            GuardPatrolModeSetting pm = tower.getSetting(AbstractBuildingGuards.PATROL_MODE);
            if (pm != null) {
                pm.set(GuardPatrolModeSetting.MANUAL);
            }
            GuardTaskSetting task = tower.getSetting(AbstractBuildingGuards.GUARD_TASK);
            if (task != null && !GuardTaskSetting.PATROL.equals(me.marko.errands.GuardSettings.value(task, null))) {
                me.marko.errands.GuardSettings.set(task, GuardTaskSetting.PATROL);
            }
            tower.markDirty();
            ErrandManager.kickGuardAI(tower);
            ColonistErrands.LOGGER.info("[Patrol] {} {} patrol point {} (manual route)", data.getName(), mode, point.toShortString());
            result.addProperty("success", true);
            result.addProperty("info", (mode.equals("start")
                    ? "New manual patrol route started - first point is the player's spot "
                    : "Added another patrol point at the player's spot ")
                    + point.toShortString() + ". Your tower now patrols these points. The player can add more spots "
                    + "by standing there and saying so, or say 'normal patrols' to reset." + Texts.GOODBYE);
            return result;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("patrol_here failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Could not change the patrol route.");
            return result;
        }
    }
}
