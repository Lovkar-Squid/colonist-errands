package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.Texts;
import me.lovkar.errands.TradePost;
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

/**
 * Mints MC Trade Post trade coins out of the colony treasury and hands them to
 * the player. Spends colony money, so it sits in the "jobs" permission group
 * (officer and up by default).
 */
public class MintCoinsAction extends PlayerFunctionAction {

    public MintCoinsAction() {
        super("mint_coins",
                "The player asks to have TRADE COINS minted or withdrawn from the colony treasury "
                        + "('mint me some trade coins', 'turn our money into coins', 'give me 5 coins'). "
                        + "Spends the colony's earned value at the marketplace and hands the coins over. "
                        + "Explains honestly when the marketplace is too low a level or the treasury is short. "
                        + "For just ASKING about the economy use trade_status instead.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        int count = 1;
        try {
            if (parameters != null && parameters.has("count")) {
                count = parameters.get("count").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        final int want = Math.max(1, Math.min(512, count));

        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server == null) {
                result.addProperty("success", false);
                result.addProperty("error", "The colony books are not reachable right now.");
                return result;
            }
            if (!server.isSameThread()) {
                info = server.submit(() -> TradePost.mint(colony, server.getPlayerList().getPlayer(playerId), want)).join();
            } else {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                info = TradePost.mint(colony, player, want);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[TradePost] mint tool failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "The mint would not cooperate just now.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }
}
