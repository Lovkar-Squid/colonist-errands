package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * One-shot emergency protocol: civilians run home, every guard tower switches
 * to Guard mode manning its own tower. Ends with 'stand down'/dismiss (guards
 * restored) - civilians resume on arrival by themselves.
 */
public class RedAlertAction extends PlayerFunctionAction {

    private static final int MAX_SENT = 60;

    public RedAlertAction() {
        super("red_alert",
                "EMERGENCY PROTOCOL, use only when the player declares an emergency ('red alert', 'sound the "
                        + "alarm', 'everyone take cover'): ALL civilians immediately head home AND every guard "
                        + "tower switches to Guard mode - guards man their own towers. For a defensive line toward "
                        + "a specific direction or the current raid use defend_here instead (it can be called after "
                        + "this). The player ends the alert with 'stand down' (dismiss tool). Confirm like a "
                        + "soldier, briefly.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        int sentHome = 0;
        int towers = 0;

        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd == null || cd.getHomeBuilding() == null) continue;
            Optional<AbstractEntityCitizen> opt = cd.getEntity();
            if (opt == null || opt.isEmpty()) continue;
            AbstractEntityCitizen c = opt.get();
            if (!c.isAlive() || c.isRemoved()) continue;
            if (cd.getWorkBuilding() instanceof AbstractBuildingGuards) continue;
            boolean isSpeaker = c.getUUID().equals(citizen.getUUID());
            if (!isSpeaker && ConversationManager.isCitizenBusy(c)) continue;
            if (ErrandManager.hasErrand(c)) continue;
            if (sentHome >= MAX_SENT) break;
            ErrandManager.enqueuePosErrand(c, cd.getHomeBuilding().getPosition(), "home", 20 * 360, 25.0);
            sentHome++;
        }

        if (ErrandManager.hasActiveDefense()) {
            ErrandManager.standDownDefense(colony.getID());
        }
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (!(b instanceof AbstractBuildingGuards tower)) continue;
            try {
                GuardTaskSetting s = tower.getSetting(AbstractBuildingGuards.GUARD_TASK);
                if (s == null) continue;
                String prev = me.marko.errands.GuardSettings.value(s, GuardTaskSetting.PATROL);
                if (!me.marko.errands.GuardSettings.set(s, GuardTaskSetting.GUARD)) continue;
                tower.setGuardPos(tower.getPosition());
                try {
                    tower.markDirty();
                } catch (Throwable ignored) {
                }
                ErrandManager.kickGuardAI(tower);
                ErrandManager.registerDefense(tower, prev);
                towers++;
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("red_alert failed for a tower", t);
            }
        }

        MinecraftServer server = citizen.getServer();
        if (server != null) {
            Component msg = Component.literal("[Alarm] RED ALERT: civilians are heading home, guards are manning their towers!");
            server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(msg));
        }
        ColonistErrands.LOGGER.info("[Alarm] RED ALERT: {} civilians home, {} towers manned", sentHome, towers);
        result.addProperty("success", true);
        result.addProperty("info", "RED ALERT active: " + sentHome + " civilian(s) heading home, " + towers
                + " guard tower(s) switched to Guard mode manning their posts. The player ends it with 'stand down'."
                + " If they want the guards facing a direction or the raid, they can additionally order defend_here."
                + Texts.GOODBYE);
        return result;
    }
}
