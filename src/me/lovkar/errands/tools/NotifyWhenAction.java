package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.Texts;
import me.lovkar.errands.WatchManager;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class NotifyWhenAction extends ErrandCommand {

    public NotifyWhenAction() {
        super("notify_when",
                "The player asks you to TELL THEM when you find or get something while working "
                        + "(e.g. to a miner: 'let me know when you find diamonds or gold'). "
                        + "Your inventory is watched; once you gain at least 'count' (default 1) of the item, the player "
                        + "is notified. notify='chat' (default): they get a message and you keep working. "
                        + "notify='come_to_me': you walk to the player and report the find in person. "
                        + "Pass the item exactly as the player named it ('diamond', 'gold', 'iron'...). One item per call - "
                        + "call twice for two items. The watch expires after about an hour.",
                params("item", string(true), "count", integer(false), "notify", enumOf(List.of("chat", "come_to_me"), false)), RankGuard.GROUP_ERRANDS);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("item")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'item'.");
            return result;
        }
        UUID playerId = player.getUUID();
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        String item = parameters.get("item").getAsString();
        int count = 1;
        boolean comeToMe = false;
        try {
            if (parameters.has("count")) {
                count = Math.max(1, Math.min(1000, parameters.get("count").getAsInt()));
            }
            if (parameters.has("notify")) {
                comeToMe = "come_to_me".equalsIgnoreCase(parameters.get("notify").getAsString().trim());
            }
        } catch (Throwable ignored) {
        }
        WatchManager.add(citizen, item, count, playerId, comeToMe);
        result.addProperty("success", true);
        result.addProperty("info", "Understood - you will keep an eye out for " + item + " while working, and the player "
                + "will be " + (comeToMe ? "personally informed by you" : "notified") + " as soon as you get "
                + (count > 1 ? "at least " + count : "some") + ". Confirm this eagerly." + Texts.SILENT);
        return result;
    }
}
