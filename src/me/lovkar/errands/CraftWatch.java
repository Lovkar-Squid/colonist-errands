package me.lovkar.errands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.token.IToken;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lovkar's question: "can they bring it to ME instead of the postbox?"
 * <p>
 * MineColonies has no concept of a player as a delivery target - every request
 * is delivered to a BUILDING. So request_craft still files a normal request (at
 * the postbox), and this watcher waits for the finished goods to land there,
 * then hands the last leg to our own courier engine: a courier walks to the
 * postbox, takes the order out of its inventory and carries it to the player,
 * exactly like fetch_item does from a warehouse.
 */
public final class CraftWatch {

    private CraftWatch() {
    }

    private static final class Pending {
        final int colonyId;
        final UUID playerId;
        final Item item;
        final int count;
        final BlockPos targetPos;
        final String targetName;
        final long createdMs;
        long lastLogMs = 0;
        /**
         * How many of the item the colony already had when the order was placed.
         * We wait for the count to RISE by the ordered amount, wherever it lands -
         * a pre-existing pile in the warehouse must never count as our order.
         */
        int baseline = -1;
        /** The MineColonies request we filed - the only exact answer to "is it done?". */
        IToken<?> token;

        Pending(int colonyId, UUID playerId, Item item, int count, BlockPos targetPos, String targetName) {
            this.colonyId = colonyId;
            this.playerId = playerId;
            this.item = item;
            this.count = count;
            this.targetPos = targetPos;
            this.targetName = targetName;
            this.createdMs = System.currentTimeMillis();
        }
    }

    private static final ConcurrentLinkedQueue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    /** Crafting a big order can genuinely take an hour of colony time. */
    private static final long EXPIRY_MS = 2 * 60 * 60_000L;
    /** After this long, bring whatever HAS arrived rather than waiting forever. */
    private static final long PARTIAL_AFTER_MS = 15 * 60_000L;
    private static final int PER_PLAYER_CAP = 6;
    private static int tickCounter = 0;

    /** Watch a crafted order and hand-deliver it when it lands. False when the player has too many pending. */
    public static boolean add(IColony colony, UUID playerId, Item item, int count, IBuilding target,
                              String targetName) {
        return add(colony, playerId, item, count, target, targetName, null);
    }

    /** Watch a crafted order by its request token - the exact, consumption-proof signal. */
    public static boolean add(IColony colony, UUID playerId, Item item, int count, IBuilding target,
                              String targetName, IToken<?> token) {
        try {
            int mine = 0;
            for (Pending p : PENDING) {
                if (p.playerId.equals(playerId)) mine++;
            }
            if (mine >= PER_PLAYER_CAP) {
                return false;
            }
            Pending pending = new Pending(colony.getID(), playerId, item, count, target.getPosition(), targetName);
            pending.token = token;   // <- was dropped on the floor in beta.30, so every
                                     //    order logged "request untracked" and fell back
                                     //    to the stock arithmetic we had just replaced.
            PENDING.add(pending);
            ColonistErrands.LOGGER.info("[Craft] Watching {}x {} at the {} - to be carried to the player when ready "
                            + "({})",
                    count, item.getDescription().getString(), targetName,
                    token == null ? "NO request token - falling back to stock counting" : "tracking request " + token);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void tick(MinecraftServer server) {
        if (++tickCounter % 40 != 0 || PENDING.isEmpty()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Iterator<Pending> it = PENDING.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                String itemName = p.item.getDescription().getString();

                IColony colony = colonyById(p.colonyId);
                if (colony == null) {
                    it.remove();
                    continue;
                }
                IBuilding target = null;
                try {
                    target = colony.getServerBuildingManager().getBuilding(p.targetPos);
                } catch (Throwable ignored) {
                }
                if (target == null) {
                    chat(server, p, "[Craft] Your order of " + p.count + "x " + itemName
                            + " lost its drop-off point - the " + p.targetName + " is gone.");
                    it.remove();
                    continue;
                }

                // Lovkar's 64 bowls WERE crafted and delivered (the delivery was
                // tracked as "Bowl x64"), yet we sat there reporting "0 at the
                // postbox" - because a finished craft does not necessarily come to
                // rest in the building the request was filed against. So look in
                // the drop-off AND the warehouses, and measure the RISE since the
                // order was placed rather than the raw count.
                // The inventory count of the colony is a bad proxy: bowls get eaten
                // by the restaurant, so Lovkar's 64-bowl order once "delivered" 3
                // because the pile happened to rise by 3 and then fall below the
                // baseline again. The request itself is the only exact answer.
                IRequest<?> req = null;
                try {
                    if (p.token != null) {
                        req = colony.getRequestManager().getRequestForToken(p.token);
                    }
                } catch (Throwable ignored) {
                }
                int delivered = 0;
                RequestState state = null;
                if (req != null) {
                    try {
                        state = req.getState();
                        // getDeliveries() returns a guava ImmutableList, which is not
                        // on our compile classpath - iterate it as a plain List.
                        Object raw = IRequest.class.getMethod("getDeliveries").invoke(req);
                        if (raw instanceof java.util.List<?> list) {
                            for (Object o : list) {
                                if (o instanceof ItemStack st && !st.isEmpty() && st.getItem() == p.item) {
                                    delivered += st.getCount();
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (state == RequestState.FAILED || state == RequestState.CANCELLED
                            || state == RequestState.OVERRULED) {
                        chat(server, p, "[Craft] Your order of " + p.count + "x " + itemName
                                + " FAILED in the colony's request system - nobody could make it. The crafter is "
                                + "most likely short of materials; check the clipboard.");
                        ColonistErrands.LOGGER.info("[Craft] Order {}x {} ended as {}", p.count, itemName, state);
                        it.remove();
                        continue;
                    }
                }

                IBuilding source = target;
                int inTarget = countIn(colony.getWorld(), target, p.item);
                int total = inTarget;
                int best = inTarget;
                for (IBuilding wh : warehouses(colony)) {
                    if (wh == target) continue;
                    int n = countIn(colony.getWorld(), wh, p.item);
                    total += n;
                    if (n > best) {
                        best = n;
                        source = wh;
                    }
                }
                if (p.baseline < 0) {
                    p.baseline = total;
                    ColonistErrands.LOGGER.info("[Craft] Baseline for {}x {}: colony already holds {} ({} at the {})",
                            p.count, itemName, total, inTarget, p.targetName);
                }
                // With a token we trust the request. Without one (older orders) we
                // fall back to the rise in colony stock.
                int made = req != null ? delivered : Math.max(0, total - p.baseline);
                int have = Math.min(made, Math.max(best, inTarget));
                long age = now - p.createdMs;
                boolean ready = have > 0 && (made >= p.count || age > PARTIAL_AFTER_MS);

                if (!ready) {
                    if (age > EXPIRY_MS) {
                        chat(server, p, "[Craft] Your " + p.count + "x " + itemName
                                + " never turned up at the " + p.targetName
                                + " - I stopped waiting. Check the clipboard, the crafter may still be short of materials.");
                        it.remove();
                    } else if (now - p.lastLogMs > 300_000L) {
                        p.lastLogMs = now;
                        ColonistErrands.LOGGER.info(
                                "[Craft] Still waiting: {}x {} (request {}, {} delivered; {} at the {}, {} in the "
                                        + "colony)",
                                p.count, itemName, state == null ? "untracked" : state, made, inTarget,
                                p.targetName, total);
                    }
                    continue;
                }

                AbstractEntityCitizen courier = FetchQueue.dispatchableCourier(colony);
                if (courier == null) {
                    if (now - p.lastLogMs > 60_000L) {
                        p.lastLogMs = now;
                        ColonistErrands.LOGGER.info("[Craft] {}x {} is ready but no courier is dispatchable",
                                have, itemName);
                    }
                    continue;
                }
                int take = Math.min(p.count, have);
                final String sourceName = source == target ? p.targetName : ErrandBuildings.bestName(source);
                try {
                    ((CitizenDataMemoryExtended) courier.getCitizenData()).mc_talking$getOrInitializeMemory()
                            .addEvent("A crafted order just landed at the " + sourceName + ": " + take + "x "
                                    + itemName + ". I am carrying it to the player who ordered it.");
                } catch (Throwable ignored) {
                }
                ErrandManager.startFetchErrand(courier, source, p.playerId, p.item, take);
                chat(server, p, "[Craft] Your " + take + "x " + itemName + " is finished and waiting at the "
                        + sourceName + " - " + courier.getCitizenData().getName() + " is bringing it over"
                        + (take < p.count ? " (only " + take + " of " + p.count + " made it so far)" : "") + ".");
                ColonistErrands.LOGGER.info("[Craft] {} is carrying {}x {} from the {} to the player",
                        courier.getCitizenData().getName(), take, itemName, sourceName);
                it.remove();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Every warehouse in the colony - a finished craft often comes to rest there. */
    private static List<IBuilding> warehouses(IColony colony) {
        List<IBuilding> out = new ArrayList<>();
        try {
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                try {
                    if ("warehouse".equals(b.getSchematicName())) {
                        out.add(b);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** How much of the item sits in that building right now (its hut block counts as a rack). */
    private static int countIn(Level level, IBuilding building, Item item) {
        int total = 0;
        try {
            for (BlockPos pos : building.getContainers()) {
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof AbstractTileEntityRack rack)) continue;
                IItemHandler handler = rack.getItemHandlerCap();
                if (handler == null) continue;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack inSlot = handler.getStackInSlot(slot);
                    if (!inSlot.isEmpty() && inSlot.getItem() == item) {
                        total += inSlot.getCount();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private static IColony colonyById(int id) {
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getID() == id) {
                    return colony;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void chat(MinecraftServer server, Pending p, String msg) {
        try {
            ServerPlayer pl = server.getPlayerList().getPlayer(p.playerId);
            if (pl != null) {
                pl.sendSystemMessage(Component.literal(msg));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        PENDING.clear();
    }
}
