package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.FetchQueue;
import me.lovkar.errands.ItemFinder;
import me.lovkar.errands.Texts;
import me.lovkar.errands.RankGuard;
import me.lovkar.errands.tc.ErrandQuery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.UUID;

/**
 * The couriers' order board, out loud: what is queued, how long it has waited,
 * and cancelling your own orders. Reading and cancelling touch only our own
 * queue, so no server-thread hop is needed.
 */
public class CourierBoardAction extends ErrandQuery {

    public CourierBoardAction() {
        super("courier_board",
                "The player asks what is on the COURIERS' ORDER BOARD - the queue of item orders waiting for a free "
                        + "courier ('what's on the board?', 'is my order still waiting?', 'what did I order?'), "
                        + "or asks to CANCEL a queued order ('cancel my order', 'forget the iron ingots'). "
                        + "Pass 'cancel' with the item name to drop that order, or 'cancel' set to 'all' to drop "
                        + "every order the player has waiting. Without 'cancel' it just reports the board.",
                params("cancel", string(false)), RankGuard.GROUP_ERRANDS);
    }
    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        JsonObject result = new JsonObject();
        int colonyId = colony.getID();

        String cancel = null;
        try {
            if (parameters != null && parameters.has("cancel")) {
                cancel = parameters.get("cancel").getAsString();
            }
        } catch (Throwable ignored) {
        }

        if (cancel == null || cancel.isBlank()) {
            result.addProperty("success", true);
            result.addProperty("info", FetchQueue.describe(colonyId) + Texts.SILENT);
            return result;
        }

        UUID playerId = player.getUUID();
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        String want = cancel.trim().toLowerCase();
        Item item = null;
        if (!want.equals("all") && !want.equals("everything") && !want.equals("mine")) {
            item = ItemFinder.find(want);
            if (item == null) {
                result.addProperty("success", false);
                result.addProperty("error", "I don't know any item called '" + cancel + "'.");
                return result;
            }
        }
        int removed = FetchQueue.cancel(colonyId, playerId, item);
        String what = item == null ? "orders" : item.getDescription().getString();
        result.addProperty("success", true);
        result.addProperty("info", removed == 0
                ? ("Nothing of the player's was waiting on the board"
                + (item == null ? "" : " for " + what) + " - nothing to cancel."
                + Texts.SILENT)
                : ("Took " + removed + (removed == 1 ? " order" : " orders")
                + (item == null ? "" : " for " + what) + " off the couriers' board. "
                + "Confirm it briefly." + Texts.SILENT));
        return result;
    }
}
