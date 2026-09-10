package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.Texts;
import me.lovkar.errands.TradePost;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandQuery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

/**
 * MC Trade Post economy report: treasury, marketplaces, shopkeepers, sales and
 * whether trade coins can be minted. Answers from the live colony instead of
 * guesswork - Lovkar kept hitting "this marketplace cannot mint coins" with no
 * explanation of why.
 */
public class TradeStatusAction extends ErrandQuery {

    public TradeStatusAction() {
        super("trade_status",
                "The player asks about the colony's TRADE/MARKET economy from the MC Trade Post addon: "
                        + "'how is the marketplace doing?', 'how much money does the colony have?', "
                        + "'why can't we mint trade coins?', 'how are sales going?'. Reports the treasury, "
                        + "each marketplace with its level and shopkeeper, items sold and earned, coins minted, "
                        + "and whether minting is possible. Report it conversationally, like a shopkeeper "
                        + "talking shop - not as a table.", RankGuard.GROUP_CHAT);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server != null && !server.isSameThread()) {
                info = server.submit(() -> TradePost.statusText(colony)).join();
            } else {
                info = TradePost.statusText(colony);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[TradePost] status failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "I could not read the market books right now.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }
}
