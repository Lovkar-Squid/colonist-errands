package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Lovkar's idea #10: change what the builders do FIRST, by voice. MineColonies
 * hands each builder `getOrderedList(...)` sorted by work-order priority, so
 * raising one order to the top genuinely moves it up the queue.
 * <p>
 * Deliveries are a different story and the tool says so: MineColonies fixes
 * delivery order by when the request was made, and no setting changes that.
 */
public class PrioritizeAction extends PlayerFunctionAction {

    private static final int TOP = 10;

    public PrioritizeAction() {
        super("prioritize",
                "The player wants to change the ORDER the builders work in, or asks what is being built "
                        + "('build the guard tower first', 'prioritise the hospital', \"what's in the build queue?\", "
                        + "'build the barracks after the one you're on now', 'do the warehouse after the hospital'). "
                        + "'what' names the building to move. Without 'after' it goes to the FRONT of the queue. "
                        + "With 'after' it is placed directly behind that other build order - pass 'after' as the "
                        + "other building's name, or as 'current' when the player means whatever is being built right "
                        + "now. With no 'what' at all, or 'list', it just reads the queue back. BUILDING orders only - "
                        + "for guard gear use arm_guards, for an item use request_craft.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("what", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                    put("after", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        String want = null;
        try {
            if (parameters != null && parameters.has("what")) {
                want = parameters.get("what").getAsString();
            }
        } catch (Throwable ignored) {
        }
        final String query = want == null ? "" : want.trim().toLowerCase();
        String afterRaw = null;
        try {
            if (parameters != null && parameters.has("after")) {
                afterRaw = parameters.get("after").getAsString();
            }
        } catch (Throwable ignored) {
        }
        final String after = afterRaw == null ? "" : afterRaw.trim().toLowerCase();

        String info;
        try {
            MinecraftServer server = citizen.getServer();
            if (server != null && !server.isSameThread()) {
                info = server.submit(() -> run(colony, citizen, query, after)).join();
            } else {
                info = run(colony, citizen, query, after);
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Priority] prioritize failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "I could not read the build orders right now.");
            return result;
        }
        result.addProperty("success", true);
        result.addProperty("info", info + Texts.SILENT);
        return result;
    }

    private static String run(IColony colony, AbstractEntityCitizen citizen, String query, String after) {
        List<IServerWorkOrder> orders = new ArrayList<>();
        try {
            orders.addAll(colony.getWorkManager().getWorkOrders().values());
        } catch (Throwable t) {
            return "I cannot see the build orders at all right now.";
        }
        if (orders.isEmpty()) {
            return "There is NOTHING in the build queue - the builders have no orders waiting.";
        }

        if (!query.isEmpty() && !query.equals("list") && !query.equals("queue")) {
            IServerWorkOrder match = find(orders, query);
            if (match == null) {
                return "Nothing called '" + query + "' is in the build queue. What IS queued: " + queue(orders)
                        + " Ask the player which of those they meant.";
            }

            // "build this AFTER the one you are on now" / "after the hospital"
            if (!after.isEmpty()) {
                IServerWorkOrder anchor = isCurrent(after) ? current(orders, citizen) : find(orders, after);
                if (anchor == null) {
                    return isCurrent(after)
                            ? ("Nobody is actually building anything right now, so there is no 'current job' to slot it "
                            + "behind. The queue is: " + queue(orders) + " Ask which one they mean.")
                            : ("Nothing called '" + after + "' is in the build queue. What IS queued: " + queue(orders)
                            + " Ask the player which of those they meant.");
                }
                if (anchor == match) {
                    return "That is the same build order twice - ask the player which one should come first.";
                }
                if (!reorderAfter(colony, orders, match, anchor)) {
                    return "I could not shuffle the build queue just now.";
                }
                ColonistErrands.LOGGER.info("[Priority] '{}' queued directly after '{}'", nameOf(match), nameOf(anchor));
                return "'" + nameOf(match) + "' now comes straight after '" + nameOf(anchor)
                        + "' in the builders' queue. Confirm it in your own words, and that the queue is now: "
                        + queue(orders);
            }
            int top = 0;
            for (IServerWorkOrder o : orders) {
                top = Math.max(top, o.getPriority());
            }
            try {
                match.setPriority(Math.max(TOP, top + 1));
                colony.getWorkManager().setDirty(true);
            } catch (Throwable t) {
                return "I could not move that order up the queue.";
            }
            ColonistErrands.LOGGER.info("[Priority] '{}' moved to the top of the build queue", nameOf(match));
            return "'" + nameOf(match) + "' is now FIRST in the builders' queue. A builder already in the middle of "
                    + "another job finishes that one first - say so, and that the queue is now: " + queue(orders);
        }
        return "The build queue, most urgent first: " + queue(orders)
                + " Read it back naturally; the player can name one to push it to the front.";
    }

    private static IServerWorkOrder find(List<IServerWorkOrder> orders, String query) {
        for (IServerWorkOrder o : orders) {
            String name = nameOf(o).toLowerCase();
            if (name.contains(query) || query.contains(name)) {
                return o;
            }
        }
        return null;
    }

    private static boolean isCurrent(String s) {
        return s.equals("current") || s.equals("now") || s.contains("current")
                || s.contains("building now") || s.contains("working on") || s.contains("this one");
    }

    /** The order the speaking builder is on; failing that, the only claimed order. */
    private static IServerWorkOrder current(List<IServerWorkOrder> orders, AbstractEntityCitizen citizen) {
        try {
            var data = citizen.getCitizenData();
            if (data != null && data.getWorkBuilding() != null) {
                var pos = data.getWorkBuilding().getPosition();
                for (IServerWorkOrder o : orders) {
                    if (o.isClaimed() && pos.equals(o.getClaimedBy())) {
                        return o;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        IServerWorkOrder only = null;
        try {
            for (IServerWorkOrder o : orders) {
                if (!o.isClaimed()) continue;
                if (only != null) return null; // several builders are busy - too ambiguous to guess
                only = o;
            }
        } catch (Throwable ignored) {
        }
        return only;
    }

    /**
     * Places one order directly behind another. Priorities are only a sort key,
     * so the honest way to guarantee a sequence is to lay the whole queue out
     * again in the order we want it.
     */
    private static boolean reorderAfter(IColony colony, List<IServerWorkOrder> orders,
                                        IServerWorkOrder move, IServerWorkOrder anchor) {
        try {
            List<IServerWorkOrder> seq = new ArrayList<>(orders);
            seq.sort(Comparator.comparingInt(IServerWorkOrder::getPriority).reversed());
            seq.remove(move);
            int at = seq.indexOf(anchor);
            if (at < 0) return false;
            seq.add(at + 1, move);
            int p = seq.size();
            for (IServerWorkOrder o : seq) {
                o.setPriority(Math.max(1, p--));
            }
            colony.getWorkManager().setDirty(true);
            return true;
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[Priority] reorder failed", t);
            return false;
        }
    }

    private static String queue(List<IServerWorkOrder> orders) {
        List<IServerWorkOrder> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparingInt(IServerWorkOrder::getPriority).reversed());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (IServerWorkOrder o : sorted) {
            if (n >= 6) {
                sb.append("and ").append(sorted.size() - n).append(" more. ");
                break;
            }
            sb.append(n + 1).append(") ").append(nameOf(o));
            try {
                if (o.isClaimed()) sb.append(" (a builder is on it)");
            } catch (Throwable ignored) {
            }
            sb.append("; ");
            n++;
        }
        return sb.toString();
    }

    private static String nameOf(IServerWorkOrder o) {
        try {
            return o.getDisplayName().getString();
        } catch (Throwable t) {
            return "a build order";
        }
    }
}
