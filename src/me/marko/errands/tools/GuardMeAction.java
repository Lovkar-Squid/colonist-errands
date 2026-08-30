package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardFollowModeSetting;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import me.marko.errands.Texts;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ErrandManager;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class GuardMeAction extends PlayerFunctionAction {

    public GuardMeAction() {
        super("guard_me",
                "Bodyguard escort for the player. who='my_tower' (default): your own guard building switches to tight "
                        + "Follow mode - ONLY works if you are a guard. who='all_towers': EVERY guard tower in the colony "
                        + "escorts the player - use when the player orders ALL guards to protect them; any citizen may "
                        + "relay that order. The escort starts when the conversation ends and lasts until the player says "
                        + "it's enough / you can go (stop_errand, one tower) or 'dismissed'/'stand down' (dismiss, all), "
                        + "or at most 20 minutes.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("who", new EnumProperty(List.of("my_tower", "all_towers"), false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        boolean allTowers = false;
        try {
            if (parameters != null && parameters.has("who")) {
                allTowers = "all_towers".equalsIgnoreCase(parameters.get("who").getAsString().trim());
            }
        } catch (Throwable ignored) {
        }
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        MinecraftServer server = citizen.getServer();
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }

        if (allTowers) {
            int activated = 0;
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (!(b instanceof AbstractBuildingGuards g)) continue;
                if (ErrandManager.hasGuardFollow(g)) continue;
                if (activateFollow(g, player, playerId)) {
                    activated++;
                }
            }
            result.addProperty("success", activated > 0);
            result.addProperty("towers", activated);
            result.addProperty("info", activated == 0
                    ? "No guard towers could be switched to escort (maybe they already escort the player)."
                    : "All " + activated + " guard tower(s) now escort and protect the player, starting when this "
                            + "conversation ends. The player ends it with 'dismissed' / 'stand down'." + Texts.GOODBYE);
            return result;
        }

        ICitizenData data = citizen.getCitizenData();
        IBuilding wb = data == null ? null : data.getWorkBuilding();
        if (!(wb instanceof AbstractBuildingGuards guards)) {
            result.addProperty("success", false);
            result.addProperty("error", "You are not a guard - only guards escort personally. If the player wants ALL "
                    + "guards to protect them, call guard_me again with who='all_towers'.");
            return result;
        }
        if (ErrandManager.hasGuardFollow(guards)) {
            result.addProperty("success", true);
            result.addProperty("info", "You are already escorting the player.");
            return result;
        }
        if (!activateFollow(guards, player, playerId)) {
            result.addProperty("success", false);
            result.addProperty("error", "Could not switch your guard building to follow mode.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", "You (and the guards of your tower) will escort and protect the player, starting when "
                + "this conversation ends." + Texts.GOODBYE);
        return result;
    }

    private static boolean activateFollow(AbstractBuildingGuards guards, ServerPlayer player, UUID playerId) {
        try {
            GuardTaskSetting setting = guards.getSetting(AbstractBuildingGuards.GUARD_TASK);
            if (setting == null) {
                return false;
            }
            String previous = me.marko.errands.GuardSettings.value(setting, GuardTaskSetting.PATROL);
            String previousMode = null;
            try {
                GuardFollowModeSetting fm = guards.getSetting(AbstractBuildingGuards.FOLLOW_MODE);
                if (fm != null) {
                    previousMode = me.marko.errands.GuardSettings.value(fm, null);
                    me.marko.errands.GuardSettings.set(fm, GuardFollowModeSetting.TIGHT); // stay close
                }
            } catch (Throwable ignored) {
            }
            if (!me.marko.errands.GuardSettings.set(setting, GuardTaskSetting.FOLLOW)) {
                return false; // this tower doesn't offer FOLLOW - skip it, never break its setting
            }
            guards.setPlayerToFollow(player);
            try {
                guards.markDirty();
            } catch (Throwable ignored) {
            }
            ErrandManager.registerGuardFollow(guards, previous, previousMode, playerId);
            return true;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("guard_me activation failed", t);
            return false;
        }
    }
}
