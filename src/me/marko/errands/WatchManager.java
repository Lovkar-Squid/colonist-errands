package me.marko.errands;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * "Tell me when you find X": watches a citizen's inventory for gains of a
 * matching item and notifies the player - by chat, or by the citizen walking
 * over and reporting in person (with the find written into their mc_talking
 * memory, so they actually know what they came to say).
 */
public final class WatchManager {

    private static final class Watch {
        final AbstractEntityCitizen citizen;
        final String label;      // what the player called it
        final String norm;       // normalized match string
        final int threshold;
        final UUID playerId;
        final boolean comeToMe;
        int baseline;
        int age = 0;

        Watch(AbstractEntityCitizen citizen, String label, String norm, int threshold, UUID playerId, boolean comeToMe, int baseline) {
            this.citizen = citizen;
            this.label = label;
            this.norm = norm;
            this.threshold = threshold;
            this.playerId = playerId;
            this.comeToMe = comeToMe;
            this.baseline = baseline;
        }
    }

    private static final List<Watch> WATCHES = new ArrayList<>();
    private static final int EXPIRY_TICKS = 20 * 60 * 60; // 60 minutes
    private static int tickCounter = 0;

    private WatchManager() {
    }

    public static synchronized void add(AbstractEntityCitizen citizen, String itemQuery, int threshold,
                                        UUID playerId, boolean comeToMe) {
        String norm = itemQuery.trim().toLowerCase().replace(' ', '_');
        int baseline = countMatching(citizen, norm);
        WATCHES.add(new Watch(citizen, itemQuery.trim(), norm, Math.max(1, threshold), playerId, comeToMe, baseline));
        ColonistErrands.LOGGER.info("[Watch] {} now watching for '{}' (threshold {}, baseline {})",
                name(citizen), itemQuery, threshold, baseline);
    }

    public static synchronized int clearFor(AbstractEntityCitizen citizen) {
        int n = 0;
        Iterator<Watch> it = WATCHES.iterator();
        while (it.hasNext()) {
            if (it.next().citizen.getUUID().equals(citizen.getUUID())) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public static synchronized void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 40 != 0 || WATCHES.isEmpty()) {
            return;
        }
        Iterator<Watch> it = WATCHES.iterator();
        while (it.hasNext()) {
            Watch w = it.next();
            AbstractEntityCitizen c = w.citizen;
            if (c == null || !c.isAlive() || c.isRemoved()) {
                it.remove();
                continue;
            }
            w.age += 40;
            if (w.age > EXPIRY_TICKS) {
                ColonistErrands.LOGGER.info("[Watch] {}'s watch for '{}' expired", name(c), w.label);
                it.remove();
                continue;
            }
            int current = countMatching(c, w.norm);
            if (current < w.baseline) {
                w.baseline = current; // deposited some - measure future gains from the new low
                continue;
            }
            int gained = current - w.baseline;
            if (gained < w.threshold) {
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(w.playerId);
            String citizenName = name(c);
            ColonistErrands.LOGGER.info("[Watch] {} found {}x '{}' - notifying", citizenName, gained, w.label);
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "[Report] " + citizenName + " found " + gained + "x " + w.label
                                + (w.comeToMe ? " and is coming to tell you!" : "!")));
            }
            if (w.comeToMe && player != null) {
                try {
                    ((CitizenDataMemoryExtended) c.getCitizenData()).mc_talking$getOrInitializeMemory()
                            .addEvent("I just found " + gained + "x " + w.label + " while working and I am on my way "
                                    + "to personally report this great news to " + player.getGameProfile().getName() + "!");
                } catch (Throwable t) {
                    ColonistErrands.LOGGER.warn("[Watch] could not write memory event", t);
                }
                ErrandManager.enqueueContactPlayer(c, w.playerId);
            }
        }
    }

    private static int countMatching(AbstractEntityCitizen citizen, String norm) {
        int total = 0;
        try {
            IItemHandler inv = citizen.getInventoryCitizen();
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack s = inv.getStackInSlot(slot);
                if (s.isEmpty()) continue;
                String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                if (path.contains(norm) || norm.contains(path)) {
                    total += s.getCount();
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private static String name(AbstractEntityCitizen c) {
        try {
            return c.getCitizenData().getName();
        } catch (Throwable t) {
            return "citizen";
        }
    }

    public static synchronized void clearAll() {
        WATCHES.clear();
    }
}
