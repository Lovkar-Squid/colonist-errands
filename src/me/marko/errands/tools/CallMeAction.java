package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.marko.errands.AliasStore;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class CallMeAction extends PlayerFunctionAction {

    public CallMeAction() {
        super("call_me",
                "The player tells you their preferred name/nickname (e.g. 'call me Marko'). This saves it PERMANENTLY "
                        + "for the WHOLE colony: every citizen will address the player by this name from their next "
                        + "conversation on, without needing to spread the word. Pass the name exactly as the player said it.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
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
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        MinecraftServer server = citizen.getServer();
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);
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
