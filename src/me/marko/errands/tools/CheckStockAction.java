package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ItemFinder;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class CheckStockAction extends PlayerFunctionAction {

    public CheckStockAction() {
        super("check_stock",
                "Check how much of an item the colony's warehouse(s) currently hold. "
                        + "Pass the item exactly as the player named it (e.g. 'iron ingot', 'dark oak planks', 'eggplant'). "
                        + "Use when the player asks how much of something is in stock / available.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("item", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("item")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'item'.");
            return result;
        }
        String query = parameters.get("item").getAsString();
        Item item = ItemFinder.find(query);
        if (item == null) {
            result.addProperty("success", false);
            result.addProperty("error", "I don't know any item called '" + query + "'.");
            return result;
        }
        // Tool calls run on the Gemini websocket thread, but vanilla getBlockEntity
        // returns null off the server thread (that is why every count came back 0
        // while fetch_item - which runs in the server-tick engine - worked fine).
        // So the actual counting is executed ON the server thread.
        int total = 0;
        int warehouses = 0;
        try {
            MinecraftServer server = citizen.getServer();
            int[] counted;
            if (server != null && !server.isSameThread()) {
                counted = server.submit(() -> countStock(colony, citizen, item)).join();
            } else {
                counted = countStock(colony, citizen, item);
            }
            total = counted[0];
            warehouses = counted[1];
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("check_stock failed", t);
        }
        String itemName = item.getDescription().getString();
        result.addProperty("success", true);
        result.addProperty("item", itemName);
        result.addProperty("count", total);
        result.addProperty("info", warehouses == 0
                ? "The colony has no warehouse yet."
                : "The warehouse holds " + total + "x " + itemName + ". Report this naturally to the player.");
        return result;
    }

    /** Runs on the server thread. Returns {total, warehouseCount}. */
    public static int[] countStock(IColony colony, AbstractEntityCitizen citizen, Item item) {
        int total = 0;
        int warehouses = 0;
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (!b.getBuildingType().getRegistryName().getPath().equals("warehouse")) continue;
            warehouses++;
            for (BlockPos rackPos : b.getContainers()) {
                BlockEntity be = citizen.level().getBlockEntity(rackPos);
                if (be instanceof AbstractTileEntityRack rack) {
                    var handler = rack.getItemHandlerCap();
                    if (handler == null) continue;
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        ItemStack inSlot = handler.getStackInSlot(slot);
                        if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                            total += inSlot.getCount();
                        }
                    }
                }
            }
        }
        return new int[]{total, warehouses};
    }
}
