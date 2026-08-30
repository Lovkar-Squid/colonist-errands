package me.marko.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marko's idea #31: citizens REACT when a player grabs the assistant hammer
 * and personally helps at a construction site. The builder whose work order it
 * is gets a grateful memory + a real rapport boost (RelationStore), and a
 * couple of bystanders notice too - "the boss lays blocks with us!".
 * Hooked via ItemAssistantHammerMixin on the server-side placeBlock call.
 */
public final class BuilderAssist {

    private BuilderAssist() {
    }

    /** (builder citizen id | player) -> last thanks, so hammer spam thanks once per few minutes. */
    private static final Map<String, Long> LAST_THANKS = new ConcurrentHashMap<>();
    private static final long THANKS_COOLDOWN_MS = 4 * 60_000;

    public static void onHammerUsed(Player player, IColony colony, IWorkOrder workOrder) {
        try {
            if (!(player instanceof ServerPlayer sp) || colony == null || workOrder == null || !workOrder.isClaimed()) {
                return;
            }
            IBuilding hut = colony.getServerBuildingManager().getBuilding(workOrder.getClaimedBy());
            if (hut == null) {
                return;
            }
            ICitizenData builder = null;
            for (ICitizenData cd : hut.getAllAssignedCitizen()) {
                if (cd != null) {
                    builder = cd;
                    break;
                }
            }
            if (builder == null) {
                return;
            }
            String account = sp.getGameProfile().getName();
            String key = builder.getId() + "|" + account;
            long now = System.currentTimeMillis();
            Long last = LAST_THANKS.get(key);
            if (last != null && now - last < THANKS_COOLDOWN_MS) {
                return;
            }
            LAST_THANKS.put(key, now);
            String shown = AliasStore.display(account);
            String site;
            try {
                site = workOrder.getDisplayName().getString();
            } catch (Throwable t) {
                site = "the construction site";
            }
            addMemory(builder, shown + " personally grabbed an assistant hammer and helped YOU build " + site
                    + " - placing blocks with their own hands! You are impressed and grateful; mention it when you talk.");
            RelationStore.note(builder.getName(), account, true,
                    "helped you build with the assistant hammer", PromiseStore.currentDay());
            ColonistErrands.LOGGER.info("[Builder] {} helped {} build ({}) - builder is grateful",
                    shown, builder.getName(), site);

            // A couple of bystanders notice the boss working - word gets around.
            int noticed = 0;
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (noticed >= 2 || cd == null || cd == builder) continue;
                Optional<AbstractEntityCitizen> opt = cd.getEntity();
                if (opt == null || opt.isEmpty()) continue;
                AbstractEntityCitizen c = opt.get();
                if (!c.isAlive() || c.level() != sp.level() || c.distanceToSqr(sp) > 24 * 24) continue;
                addMemory(cd, "You saw " + shown + " personally helping the builders with an assistant hammer, "
                        + "laying blocks alongside them. People speak of it with respect.");
                noticed++;
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("builder assist reaction failed", t);
        }
    }

    private static void addMemory(ICitizenData cd, String event) {
        try {
            ((CitizenDataMemoryExtended) cd).mc_talking$getOrInitializeMemory().addEvent(event);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        LAST_THANKS.clear();
    }
}
