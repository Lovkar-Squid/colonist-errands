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

public class DeliverItemAction extends PlayerFunctionAction {

    public DeliverItemAction() {
        super("deliver_item",
                "COURIERS ONLY: carry items from the warehouse INTO another building's storage ('take 32 planks "
                        + "to the builder', 'deliver bread to the restaurant'). You pick the items up at the "
                        + "warehouse, walk to the target building and stock its racks. For bringing items to the "
                        + "PLAYER use fetch_item instead. Pass the item and the building as the player named them "
                        + "(building type or custom name). Fails politely if you are not a courier.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("item", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                    put("building", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
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
            result.addProperty("error", "You are not a courier - only couriers run deliveries. Suggest the player asks a courier.");
            return result;
        }
        if (parameters == null || !parameters.has("item") || !parameters.has("building")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'item' or 'building'.");
            return result;
        }
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        if (playerId == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        Item item = ItemFinder.find(parameters.get("item").getAsString());
        if (item == null) {
            result.addProperty("success", false);
            result.addProperty("error", "I don't know any item called '" + parameters.get("item").getAsString() + "'.");
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

        String bQuery = parameters.get("building").getAsString().trim().toLowerCase();
        IBuilding dest = ErrandBuildings.resolve(colony, citizen, bQuery, null);
        if (dest == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No building of type or name '" + bQuery + "' exists in this colony.");
            return result;
        }
        IBuilding warehouse = ErrandBuildings.nearestOfType(colony, citizen, "warehouse");
        if (warehouse == null) {
            result.addProperty("success", false);
            result.addProperty("error", "This colony has no warehouse.");
            return result;
        }
        if (warehouse.getPosition().equals(dest.getPosition())) {
            result.addProperty("success", false);
            result.addProperty("error", "That IS the warehouse - the items are already there.");
            return result;
        }
        String destName = ErrandBuildings.bestName(dest);
        String itemName = item.getDescription().getString();
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
                    + ") - you will deliver ALL " + available + "; tell the player about the shortage.";
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
                    playerName == null ? "the player" : playerName, item, count, dest, destName);
            if (!queued) {
                result.addProperty("success", false);
                result.addProperty("error", "You are mid-delivery AND the couriers' order board is full - "
                        + "apologize and ask the player to try again in a few minutes.");
                return result;
            }
            result.addProperty("success", true);
            result.addProperty("info", "You are MID-DELIVERY, so you will NOT go yourself. You put the order on "
                    + "the couriers' shared board: promise the player that THE FIRST COURIER TO FINISH their "
                    + "deliveries will stock the " + destName + " with the " + count + "x " + itemName
                    + " (they will get a chat notice when someone takes it)." + stockNote + Texts.GOODBYE);
            return result;
        }
        ErrandManager.startDeliverErrand(citizen, warehouse, dest, destName, playerId, item, count);
        result.addProperty("success", true);
        result.addProperty("info", "You will take up to " + count + "x " + itemName
                + " from the warehouse and stock the " + destName + " with it, starting when this conversation ends."
                + stockNote + Texts.GOODBYE);
        return result;
    }
}
