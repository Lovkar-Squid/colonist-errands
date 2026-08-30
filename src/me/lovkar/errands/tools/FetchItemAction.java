package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandBuildings;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.ItemFinder;
import me.lovkar.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class FetchItemAction extends PlayerFunctionAction {

    public FetchItemAction() {
        super("fetch_item",
                "COURIERS ONLY: fetch items from the warehouse and bring them to the player. You walk to the "
                        + "warehouse, take up to 'count' (default 16, max 256) of the item, walk back to the player "
                        + "and hand everything over. Fails politely if you are not a courier. "
                        + "Pass the item exactly as the player named it. "
                        + "The run starts when this conversation ends.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("item", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        IBuilding wb = data == null ? null : data.getWorkBuilding();
        boolean isCourier = wb != null && wb.getBuildingType().getRegistryName().getPath().equals("deliveryman");
        if (!isCourier) {
            result.addProperty("success", false);
            result.addProperty("error", "You are not a courier - only couriers fetch items from the warehouse. "
                    + "Suggest the player asks a courier.");
            return result;
        }
        if (parameters == null || !parameters.has("item")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'item'.");
            return result;
        }
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        String query = parameters.get("item").getAsString();
        Item item = ItemFinder.find(query);
        if (item == null) {
            result.addProperty("success", false);
            result.addProperty("error", "I don't know any item called '" + query + "'.");
            return result;
        }
        int count = 16;
        try {
            if (parameters.has("count")) {
                count = parameters.get("count").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        count = Math.max(1, Math.min(256, count));

        IBuilding warehouse = ErrandBuildings.nearestOfType(colony, citizen, "warehouse");
        if (warehouse == null) {
            result.addProperty("success", false);
            result.addProperty("error", "This colony has no warehouse.");
            return result;
        }
        String itemName = item.getDescription().getString();
        // Check the actual stock UP FRONT so the player hears about shortages
        // immediately ("you asked for 30, we only have 25").
        int available = -1;
        try {
            net.minecraft.server.MinecraftServer server = citizen.getServer();
            available = (server != null && !server.isSameThread()
                    ? server.submit(() -> CheckStockAction.countStock(colony, citizen, item)).join()
                    : CheckStockAction.countStock(colony, citizen, item))[0];
        } catch (Throwable ignored) {
        }
        String stockNote = "";
        if (available == 0) {
            result.addProperty("success", false);
            result.addProperty("error", "The warehouse has NO " + itemName + " at all right now - tell the player honestly.");
            return result;
        }
        if (available > 0 && available < count) {
            stockNote = " NOTE: the warehouse only has " + available + " (the player asked for " + count
                    + ") - you will bring ALL " + available + "; tell the player about the shortage.";
            count = available;
        }
        // Lovkar's idea: a courier MID-DELIVERY does not abandon their cargo and
        // does not refuse - they put the order on the couriers' shared board and
        // the first courier to come free brings it (FetchQueue dispatcher).
        boolean midDelivery = me.lovkar.errands.ErrandManager.hasErrand(citizen);
        try {
            if (!midDelivery && data.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman dman) {
                midDelivery = dman.getCurrentTask() != null || !dman.getTaskQueue().isEmpty();
            }
        } catch (Throwable ignored) {
        }
        if (midDelivery) {
            String playerName = me.lovkar.errands.PlayerIdentityBlock.conversingPlayerName(citizen);
            boolean queued = me.lovkar.errands.FetchQueue.add(colony, playerId,
                    playerName == null ? "the player" : playerName, item, count, null, null);
            if (!queued) {
                result.addProperty("success", false);
                result.addProperty("error", "You are mid-delivery AND the couriers' order board is full - "
                        + "apologize and ask the player to try again in a few minutes.");
                return result;
            }
            result.addProperty("success", true);
            result.addProperty("info", "You are MID-DELIVERY, so you will NOT go yourself. You put the order on "
                    + "the couriers' shared board: promise the player that THE FIRST COURIER TO FINISH their "
                    + "deliveries will bring them the " + count + "x " + itemName
                    + " (they will get a chat notice when someone takes it)." + stockNote + Texts.GOODBYE);
            return result;
        }
        ErrandManager.startFetchErrand(citizen, warehouse, playerId, item, count);
        result.addProperty("success", true);
        result.addProperty("info", "You will fetch up to " + count + "x " + itemName
                + " from the warehouse and bring it to the player, starting when this conversation ends."
                + stockNote + Texts.GOODBYE);
        return result;
    }
}
