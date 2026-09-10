package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.AliasStore;
import me.lovkar.errands.Texts;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.UUID;

public class CallMeAction extends ErrandCommand {

    public CallMeAction() {
        super("call_me",
                "The player tells you their preferred name/nickname (e.g. 'call me Lovkar'). This saves it PERMANENTLY "
                        + "for the WHOLE colony: every citizen will address the player by this name from their next "
                        + "conversation on, without needing to spread the word. Pass the name exactly as the player said it.",
                params("name", string(true)), RankGuard.GROUP_CHAT);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("name")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'name'.");
            return result;
        }
        String name = parameters.get("name").getAsString().trim().replace("\"", "").replace("'", "");
        if (name.isBlank() || name.length() > 24) {
            result.addProperty("success", false);
            result.addProperty("error", "Please give a short name (up to 24 characters).");
            return result;
        }
        UUID playerId = player.getUUID();
        MinecraftServer server = citizen.getServer();
        
        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        String account = player.getGameProfile().getName();
        AliasStore.set(account, name);
        result.addProperty("success", true);
        result.addProperty("info", "Saved: the whole colony will now address the player as '" + name + "'. "
                + "Use that name yourself from now on, and confirm it warmly." + Texts.SILENT);
        return result;
    }
}
