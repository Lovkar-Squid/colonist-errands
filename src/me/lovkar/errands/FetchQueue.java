package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.tools.CheckStockAction;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lovkar's idea: ask a courier who is MID-DELIVERY for items and instead of
 * dropping their cargo (or refusing), they put the order on the couriers'
 * shared board - "I'll pass it on, the first of us to finish brings it."
 * A dispatcher watches the board and hands each order to the first courier in
 * that colony who is truly free (no MineColonies delivery task, no addon
 * errand, not talking, awake). The chosen courier gets a memory of the order
 * (so they KNOW what they're bringing and for whom) and the standard fetch /
 * deliver errand chain runs - stock honesty, multi-warehouse rounds and the
 * bags-full logic all included.
 */
public final class FetchQueue {

    private FetchQueue() {
    }

    private static final class Order {
        final int colonyId;
        final UUID playerId;
        final String playerName;
        final Item item;
        final int count;
        final BlockPos destPos;   // deliver_item orders: target building; null = bring to the player
        final String destName;
        final long createdMs;
        long lastWaitLogMs = 0;

        Order(int colonyId, UUID playerId, String playerName, Item item, int count,
              BlockPos destPos, String destName) {
            this.colonyId = colonyId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.item = item;
            this.count = count;
            this.destPos = destPos;
            this.destName = destName;
            this.createdMs = System.currentTimeMillis();
        }
    }

    private static final ConcurrentLinkedQueue<Order> QUEUE = new ConcurrentLinkedQueue<>();
    private static final long EXPIRY_MS = 30 * 60_000L;
    private static final int PER_COLONY_CAP = 8;
    private static int tickCounter = 0;

    /** Queue an order for the first free courier. False when the board is full. */
    public static boolean add(IColony colony, UUID playerId, String playerName, Item item, int count,
                              IBuilding dest, String destName) {
        try {
            int inColony = 0;
            for (Order o : QUEUE) {
                if (o.colonyId == colony.getID()) inColony++;
            }
            if (inColony >= PER_COLONY_CAP) {
                return false;
            }
            QUEUE.add(new Order(colony.getID(), playerId, playerName, item, count,
                    dest == null ? null : dest.getPosition(), destName));
            ColonistErrands.LOGGER.info("[Courier] Order put on the couriers' board: {}x {}{} (for {})",
                    count, item.getDescription().getString(),
                    destName == null ? "" : " -> " + destName, playerName);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void tick(MinecraftServer server) {
        if (++tickCounter % 40 != 0 || QUEUE.isEmpty()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Iterator<Order> it = QUEUE.iterator();
            while (it.hasNext()) {
                Order o = it.next();
                String itemName = o.item.getDescription().getString();
                if (now - o.createdMs > EXPIRY_MS) {
                    chat(server, o, "[Courier] Nobody got free in time for your order of "
                            + o.count + "x " + itemName + " - it expired, ask again.");
                    it.remove();
                    continue;
                }
                IColony colony = colonyById(server, o.colonyId);
                if (colony == null) {
                    it.remove();
                    continue;
                }
                boolean escalated = false;
                AbstractEntityCitizen courier = freeCourier(colony);
                if (courier == null && now - o.createdMs > 60_000L) {
                    // Lovkar's report: in a living colony courier queues are never
                    // empty, so a strictly idle courier may never exist - after a
                    // minute the LEAST BUSY courier squeezes the order in between
                    // their deliveries (our errand pauses their queue, it resumes
                    // right after - same as ordering a busy courier directly).
                    courier = leastBusyCourier(colony);
                    escalated = true;
                }
                if (courier == null) {
                    if (now - o.lastWaitLogMs > 60_000L) {
                        o.lastWaitLogMs = now;
                        ColonistErrands.LOGGER.info("[Courier] Board: order {}x {} still waiting - no dispatchable courier in colony {}",
                                o.count, itemName, o.colonyId);
                    }
                    continue; // try again in 2 s
                }
                IBuilding warehouse = ErrandBuildings.nearestOfType(colony, courier, "warehouse");
                if (warehouse == null) {
                    chat(server, o, "[Courier] Your order of " + o.count + "x " + itemName
                            + " was dropped - the colony has no warehouse anymore.");
                    it.remove();
                    continue;
                }
                int available = 0;
                try {
                    available = CheckStockAction.countStock(colony, courier, o.item)[0];
                } catch (Throwable ignored) {
                }
                if (available <= 0) {
                    chat(server, o, "[Courier] The warehouse ran OUT of " + itemName
                            + " before a courier got free - your order was dropped.");
                    it.remove();
                    continue;
                }
                int count = Math.min(o.count, available);
                IBuilding dest = null;
                if (o.destPos != null) {
                    try {
                        dest = colony.getServerBuildingManager().getBuilding(o.destPos);
                    } catch (Throwable ignored) {
                    }
                    if (dest == null) {
                        chat(server, o, "[Courier] Your order of " + o.count + "x " + itemName
                                + " for the " + o.destName + " was dropped - that building is gone.");
                        it.remove();
                        continue;
                    }
                }
                try {
                    ((CitizenDataMemoryExtended) courier.getCitizenData()).mc_talking$getOrInitializeMemory()
                            .addEvent("A queued courier order from " + o.playerName + " just landed on me: bring "
                                    + count + "x " + itemName
                                    + (dest != null ? " to the " + o.destName : " to " + o.playerName)
                                    + ". A colleague was mid-delivery and passed it on - I am on it now.");
                } catch (Throwable ignored) {
                }
                if (dest != null) {
                    ErrandManager.startDeliverErrand(courier, warehouse, dest, o.destName, o.playerId, o.item, count);
                } else {
                    ErrandManager.startFetchErrand(courier, warehouse, o.playerId, o.item, count);
                }
                chat(server, o, "[Courier] " + courier.getCitizenData().getName()
                        + (escalated ? " squeezed your queued order in between deliveries - "
                                : " finished their deliveries and took your queued order - ")
                        + (dest != null ? "stocking the " + o.destName + " with " : "bringing you ")
                        + count + "x " + itemName + ".");
                ColonistErrands.LOGGER.info("[Courier] Queued order dispatched to {} ({}x {})",
                        courier.getCitizenData().getName(), count, itemName);
                it.remove();
            }
        } catch (Throwable ignored) {
        }
    }

    /** First courier in the colony with truly nothing on their hands. */
    private static AbstractEntityCitizen freeCourier(IColony colony) {
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (!(cd.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman dman)) {
                    continue;
                }
                try {
                    if (dman.getCurrentTask() != null || !dman.getTaskQueue().isEmpty()) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                AbstractEntityCitizen entity = cd.getEntity().orElse(null);
                if (entity == null || !entity.isAlive() || entity.isSleeping()) {
                    continue;
                }
                if (ErrandManager.hasErrand(entity) || ConversationManager.isCitizenBusy(entity)) {
                    continue;
                }
                return entity;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Least-busy courier (smallest delivery load) who is awake, alive, not on
     *  one of our errands and not mid-conversation - their MineColonies queue
     *  pauses during our errand and resumes afterwards. */
    private static AbstractEntityCitizen leastBusyCourier(IColony colony) {
        AbstractEntityCitizen best = null;
        int bestLoad = Integer.MAX_VALUE;
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (!(cd.getJob() instanceof com.minecolonies.core.colony.jobs.JobDeliveryman dman)) {
                    continue;
                }
                AbstractEntityCitizen entity = cd.getEntity().orElse(null);
                if (entity == null || !entity.isAlive() || entity.isSleeping()) {
                    continue;
                }
                if (ErrandManager.hasErrand(entity) || ConversationManager.isCitizenBusy(entity)) {
                    continue;
                }
                int load = 0;
                try {
                    load = dman.getTaskQueue().size() + (dman.getCurrentTask() != null ? 1 : 0);
                } catch (Throwable ignored) {
                }
                if (load < bestLoad) {
                    bestLoad = load;
                    best = entity;
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    private static IColony colonyById(MinecraftServer server, int id) {
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

    private static void chat(MinecraftServer server, Order o, String msg) {
        try {
            ServerPlayer pl = server.getPlayerList().getPlayer(o.playerId);
            if (pl != null) {
                pl.sendSystemMessage(Component.literal(msg));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        QUEUE.clear();
    }
}
