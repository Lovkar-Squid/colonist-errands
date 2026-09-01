package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.ICraftingBuildingModule;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.CraftWatch;
import me.lovkar.errands.ErrandBuildings;
import me.lovkar.errands.ItemFinder;
import me.lovkar.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Lovkar's idea #32: order a CRAFT by voice, from ANY colonist - they pass it to
 * the colony, they do not have to be the crafter themselves. Files a real
 * request in the MineColonies request system (exactly what a postbox does), so
 * a crafter makes the item and a courier delivers it.
 * <p>
 * A whole SHOPPING LIST can go in one breath ("a hammer, 20 iron ingots and
 * three saplings") via the 'items' parameter - each entry is ordered and
 * reported on separately.
 * <p>
 * The honesty rule that matters: MineColonies never fails an impossible
 * request, it silently parks it on the player resolver forever. So before
 * ordering we check whether any STAFFED crafting building in the colony holds a
 * recipe, and say so plainly when nobody does (crafters do not know vanilla
 * recipes implicitly - they must be taught, or the recipe ships with the
 * building).
 */
public class RequestCraftAction extends PlayerFunctionAction {

    private static final int MAX_ENTRIES = 8;

    public RequestCraftAction() {
        super("request_craft",
                "The player asks to have something MADE or ordered from the colony's crafters "
                        + "('forge me an assistant hammer', 'craft me a diamond pickaxe', 'order 20 iron ingots'). "
                        + "ANY colonist can take the order - you pass it to the colony's request system, you do not "
                        + "have to be the crafter - so accept it even if it is nothing to do with your own job. "
                        + "A crafter makes it and a courier delivers it to the postbox, exactly like ordering by hand. "
                        + "For ONE thing use 'item' (+ optional 'count'). For SEVERAL things in one breath use 'items' "
                        + "as a comma-separated list where each entry may carry its own amount, e.g. "
                        + "'assistant hammer, 20 iron ingots, 3 oak saplings'. Pass 'to' with a building (type or custom name) "
                        + "when the player wants the order STOCKED SOMEWHERE - 'make 64 veggie soup and put it in the restaurant' "
                        + "- or 'to' set to 'me' when they want it CARRIED TO THEM once it is finished ('make me a pickaxe and bring it over') "
                        + "-> the request is filed on that building, so the courier delivers it straight there; without 'to' it "
                        + "goes to the postbox. Answers honestly when nobody in the colony knows a recipe. For something that "
                        + "only needs carrying from the warehouse use fetch_item instead.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("item", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                    put("items", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                    put("to", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                }}));
    }

    /** One parsed shopping-list entry. */
    private record Entry(Item item, int count, String spoken) {
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        if (parameters == null) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'item' or 'items'.");
            return result;
        }

        List<String> raw = new ArrayList<>();
        try {
            if (parameters.has("items")) {
                String list = parameters.get("items").getAsString();
                if (list != null) {
                    for (String part : list.split("[,;\\n]|\\band\\b")) {
                        if (part != null && !part.isBlank()) raw.add(part.trim());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (raw.isEmpty()) {
            try {
                if (parameters.has("item")) {
                    String one = parameters.get("item").getAsString();
                    int count = 1;
                    try {
                        if (parameters.has("count")) count = parameters.get("count").getAsInt();
                    } catch (Throwable ignored) {
                    }
                    raw.add(Math.max(1, count) + " " + one);
                }
            } catch (Throwable ignored) {
            }
        }
        if (raw.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'item' or 'items'.");
            return result;
        }
        if (raw.size() > MAX_ENTRIES) {
            raw = raw.subList(0, MAX_ENTRIES);
        }

        List<Entry> entries = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String part : raw) {
            int count = 1;
            String name = part.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d{1,4})\\s*[xX]?\\s+(.+)$").matcher(name);
            if (m.matches()) {
                try {
                    count = Integer.parseInt(m.group(1));
                } catch (Throwable ignored) {
                }
                name = m.group(2).trim();
            }
            Item item = ItemFinder.find(name);
            if (item == null) {
                unknown.add(name);
                continue;
            }
            entries.add(new Entry(item, Math.max(1, Math.min(512, count)), name));
        }

        if (entries.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "I don't know any item called '" + String.join("', '", unknown) + "'.");
            return result;
        }

        String toQuery = null;
        try {
            if (parameters.has("to")) {
                toQuery = parameters.get("to").getAsString();
            }
        } catch (Throwable ignored) {
        }

        final List<Entry> todo = entries;
        final String dest = toQuery;
        final UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server != null && !server.isSameThread()) {
                info = server.submit(() -> orderAll(colony, citizen, todo, dest, playerId)).join();
            } else {
                info = orderAll(colony, citizen, todo, dest, playerId);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Craft] request_craft failed", t);
            info = "Something went wrong filing that order.";
        }
        if (!unknown.isEmpty()) {
            info = info + " I could not place '" + String.join("', '", unknown)
                    + "' - no such item exists, ask the player what they meant.";
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }

    /** Runs on the server thread: one recipe lookup + request per entry. */
    private static String orderAll(IColony colony, AbstractEntityCitizen citizen, List<Entry> entries,
                                   String toQuery, UUID playerId) {
        String want = toQuery == null ? "" : toQuery.trim().toLowerCase();
        boolean toPlayer = want.equals("me") || want.equals("player") || want.equals("here")
                || want.equals("to me") || want.equals("the player") || want.equals("us");
        boolean named = !want.isEmpty() && !toPlayer;
        if (toPlayer && playerId == null) {
            return "I cannot tell who to carry it to - nothing was ordered, ask the player to come closer.";
        }
        IBuilding target;
        if (named) {
            target = ErrandBuildings.resolve(colony, citizen, toQuery.trim().toLowerCase(), null);
            if (target == null) {
                return "There is no building called '" + toQuery + "' in this colony, so I placed NOTHING - "
                        + "ask the player which building they mean.";
            }
        } else {
            target = deliveryTarget(colony);
        }
        List<String> ordered = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        for (Entry e : entries) {
            String itemName = e.item().getDescription().getString();
            ItemStack one = new ItemStack(e.item(), 1);

            String crafterName = null;
            String crafterBuilding = null;
            String unstaffedBuilding = null;
            try {
                for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    if (b == null || b.getBuildingLevel() <= 0) continue;
                    List<ICraftingBuildingModule> modules;
                    try {
                        modules = b.getModules(ICraftingBuildingModule.class);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (modules.isEmpty()) continue;
                    boolean knows = false;
                    for (ICraftingBuildingModule m : modules) {
                        try {
                            if (m.getFirstRecipe(one) != null) {
                                knows = true;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!knows) continue;
                    String name = ErrandBuildings.bestName(b);
                    String worker = null;
                    try {
                        for (ICitizenData cd : b.getAllAssignedCitizen()) {
                            worker = cd.getName();
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (worker == null) {
                        if (unstaffedBuilding == null) unstaffedBuilding = name;
                        continue;
                    }
                    crafterName = worker;
                    crafterBuilding = name;
                    break;
                }
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("[Craft] recipe scan failed", t);
            }

            if (crafterName == null) {
                int stock = 0;
                try {
                    stock = CheckStockAction.countStock(colony, citizen, e.item())[0];
                } catch (Throwable ignored) {
                }
                String stockNote = stock > 0
                        ? " (the warehouse does hold " + stock + " though - offer fetch_item)"
                        : "";
                if (unstaffedBuilding != null) {
                    refused.add(itemName + ": the " + unstaffedBuilding
                            + " has the recipe but NOBODY WORKS THERE, so nothing would ever be made" + stockNote);
                } else {
                    refused.add(itemName + ": nobody in this colony knows that recipe" + stockNote);
                }
                continue;
            }
            if (target == null) {
                refused.add(itemName + ": we have no postbox and no town hall to deliver an order to");
                continue;
            }
            com.minecolonies.api.colony.requestsystem.token.IToken<?> token = null;
            try {
                token = target.createRequest(new Stack(one, e.count(), e.count()), false);
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("[Craft] createRequest failed", t);
                refused.add(itemName + ": the request system would not take it");
                continue;
            }
            ColonistErrands.LOGGER.info("[Craft] Ordered {}x {} (crafter: {} at {})",
                    e.count(), itemName, crafterName, crafterBuilding);
            if (toPlayer) {
                CraftWatch.add(colony, playerId, e.item(), e.count(), target, ErrandBuildings.bestName(target), token);
            }
            ordered.add(e.count() + "x " + itemName + " - " + crafterName + " at the " + crafterBuilding);
        }

        StringBuilder sb = new StringBuilder();
        if (!ordered.isEmpty()) {
            sb.append("ORDERED: ").append(String.join("; ", ordered)).append(". ");
            if (target != null && toPlayer) {
                sb.append("It gets built and dropped at the ").append(ErrandBuildings.bestName(target))
                        .append(", and the moment it lands there a courier CARRIES IT TO THE PLAYER - promise them "
                                + "that plainly, they do not have to fetch it or wait around for it. ");
            } else if (target != null) {
                sb.append(named ? "A courier stocks it straight into the " : "A courier drops it all at the ")
                        .append(ErrandBuildings.bestName(target))
                        .append(" when it is done, and the player can watch it on their clipboard. ");
            }
        }
        if (!refused.isEmpty()) {
            sb.append("NOT POSSIBLE: ").append(String.join("; ", refused))
                    .append(". Our crafters only make recipes they have been taught in their building's crafting GUI "
                            + "(or that come with the building itself) - say that honestly. ");
        }
        if (ordered.isEmpty() && refused.isEmpty()) {
            return "Nothing could be ordered.";
        }
        sb.append("Report it in your own words, briefly; mention a crafter may still need materials.");
        return sb.toString();
    }

    /** Postbox first (that is what a player expects), else the town hall. */
    private static IBuilding deliveryTarget(IColony colony) {
        IBuilding townHall = null;
        try {
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (b == null) continue;
                String path;
                try {
                    path = b.getBuildingType().getRegistryName().getPath();
                } catch (Throwable t) {
                    continue;
                }
                if ("postbox".equals(path)) {
                    return b;
                }
                if ("townhall".equals(path) && b.getBuildingLevel() > 0) {
                    townHall = b;
                }
            }
        } catch (Throwable ignored) {
        }
        return townHall;
    }
}
