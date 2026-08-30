package me.marko.errands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingGraveyard;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenBornEvent;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenDiedEvent;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenGrownUpEvent;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marko's idea #26: the colony REACTS to death. Watches every colony's event
 * log for CitizenDiedEvent and every graveyard for newly filled graves:
 *  - nearby (and mourning) citizens get a memory of the loss, so they talk
 *    about it in conversations on their own
 *  - two of them start a mourning chat right away (the chaperone keeps them
 *    together like any other c2c conversation)
 *  - a new grave at the graveyard = "laid to rest" memories near the graveyard
 */
public final class DeathWatcher {

    private DeathWatcher() {
    }

    private static final Map<Integer, Integer> LAST_EVENT_COUNT = new HashMap<>();
    private static final Map<BlockPos, Integer> LAST_GRAVE_COUNT = new HashMap<>();
    private static long lastMourningChatMs = 0;
    private static final long MOURNING_CHAT_COOLDOWN_MS = 120_000;

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                checkDeaths(server, colony);
                checkGraves(colony);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void checkDeaths(MinecraftServer server, IColony colony) {
        try {
            List<IColonyEventDescription> events = colony.getEventDescriptionManager().getEventDescriptions();
            Integer last = LAST_EVENT_COUNT.get(colony.getID());
            LAST_EVENT_COUNT.put(colony.getID(), events.size());
            if (last == null || events.size() <= last) {
                return; // first sight of this colony (don't replay history) or nothing new
            }
            for (int i = last; i < events.size(); i++) {
                IColonyEventDescription raw = events.get(i);
                if (raw instanceof CitizenDiedEvent died) {
                    String name = died.getCitizenName();
                    String cause = died.getDeathCause();
                    BlockPos where = died.getEventPos();
                    ColonistErrands.LOGGER.info("[Mourning] {} died ({}) at {} - colony reacts", name, cause,
                            where == null ? "?" : where.toShortString());
                    List<AbstractEntityCitizen> witnesses = pickCitizens(colony, where, 64.0, 6);
                    for (AbstractEntityCitizen c : witnesses) {
                        addMemory(c, "Terrible news: your fellow colonist " + name + " just DIED ("
                                + cause + "). The whole colony is shaken - you may bring it up, mourn together "
                                + "or worry about safety.");
                    }
                    startMourningChat(server, colony, witnesses);
                } else if (raw instanceof CitizenBornEvent born) {
                    onBirth(server, colony, born);
                } else if (raw instanceof CitizenGrownUpEvent grown) {
                    onGrownUp(colony, grown.getCitizenName());
                } else if (raw instanceof com.minecolonies.core.colony.eventhooks.buildingEvents.BuildingBuiltEvent
                        || raw instanceof com.minecolonies.core.colony.eventhooks.buildingEvents.BuildingUpgradedEvent) {
                    // Marko's "built the promised guard tower but nothing registered":
                    // finished buildings are checked against open promise texts.
                    PromiseWatcher.onBuildingEvent(server, colony,
                            (com.minecolonies.core.colony.eventhooks.buildingEvents.AbstractBuildingEvent) raw);
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("death check failed", t);
        }
    }

    /** Marko's idea #28b: the colony CELEBRATES a birth - parents beam, neighbors congratulate. */
    private static void onBirth(MinecraftServer server, IColony colony, CitizenBornEvent born) {
        try {
            String babyName = born.getCitizenName();
            BlockPos where = born.getEventPos();
            ColonistErrands.LOGGER.info("[Family] A baby was born: {} - colony celebrates", babyName);

            // Find the newborn to learn the parents' names.
            String p1 = null, p2 = null;
            try {
                for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                    if (cd != null && babyName.equals(cd.getName()) && cd.isChild()) {
                        var parents = cd.getParents();
                        if (parents != null) {
                            p1 = parents.getA();
                            p2 = parents.getB();
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            String parentsLabel = (p1 != null && !p1.isBlank() ? p1 : "a colonist")
                    + (p2 != null && !p2.isBlank() ? " and " + p2 : "");

            List<AbstractEntityCitizen> parentEntities = new ArrayList<>(2);
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd == null || cd.getName() == null) continue;
                boolean isParent = cd.getName().equals(p1) || cd.getName().equals(p2);
                Optional<AbstractEntityCitizen> opt = cd.getEntity();
                if (opt == null || opt.isEmpty()) continue;
                if (isParent) {
                    parentEntities.add(opt.get());
                    addMemory(opt.get(), "WONDERFUL news: your baby " + babyName + " was just born! You are "
                            + "overjoyed - share the happy news with everyone you talk to.");
                }
            }
            for (AbstractEntityCitizen c : pickCitizens(colony, where, 64.0, 6)) {
                if (parentEntities.contains(c)) continue;
                addMemory(c, "Happy news: " + parentsLabel + " just had a baby, little " + babyName
                        + "! Congratulate them warmly when you talk.");
            }
            // The proud parents share a moment together if both are free.
            if (parentEntities.size() == 2) {
                AbstractEntityCitizen a = parentEntities.get(0);
                AbstractEntityCitizen b = parentEntities.get(1);
                if (ConversationManager.canCitizenSpeak(a) && ConversationManager.canCitizenSpeak(b)
                        && !ConversationManager.isCitizenBusy(a) && !ConversationManager.isCitizenBusy(b)) {
                    new CitizenConversation(server, List.of(a, b)).performConversation();
                    ColonistErrands.LOGGER.info("[Family] Proud parents {} start a celebration chat", parentsLabel);
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("birth reaction failed", t);
        }
    }

    private static void onGrownUp(IColony colony, String name) {
        try {
            ColonistErrands.LOGGER.info("[Family] {} grew up", name);
            // Everyone who lists the grown-up among their children gets a proud note.
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd == null || cd.getEntity() == null || cd.getEntity().isEmpty()) continue;
                boolean isParent = false;
                try {
                    for (Integer childId : cd.getChildren()) {
                        Object ch = colony.getCitizenManager().getCivilian(childId);
                        if (ch instanceof ICitizenData c && name.equals(c.getName())) {
                            isParent = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (isParent) {
                    addMemory(cd.getEntity().get(), "Your child " + name + " just GREW UP and is ready to work! "
                            + "You are a proud parent - mention it when you talk.");
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("grown-up reaction failed", t);
        }
    }

    private static void checkGraves(IColony colony) {
        try {
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (!(b instanceof BuildingGraveyard graveyard)) {
                    continue;
                }
                int count = graveyard.getGravePositions().size();
                Integer last = LAST_GRAVE_COUNT.get(graveyard.getPosition());
                LAST_GRAVE_COUNT.put(graveyard.getPosition(), count);
                if (last == null || count <= last) {
                    continue;
                }
                ColonistErrands.LOGGER.info("[Mourning] New grave at the graveyard ({} -> {})", last, count);
                for (AbstractEntityCitizen c : pickCitizens(colony, graveyard.getPosition(), 48.0, 5)) {
                    addMemory(c, "A fallen colonist was just laid to rest at the graveyard. You might pay "
                            + "your respects or say a few words about them when you talk.");
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("grave check failed", t);
        }
    }

    /** Loaded, living citizens - mourning ones first, then nearest to 'near'. */
    private static List<AbstractEntityCitizen> pickCitizens(IColony colony, BlockPos near, double radius, int max) {
        List<AbstractEntityCitizen> mourning = new ArrayList<>();
        List<AbstractEntityCitizen> others = new ArrayList<>();
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd == null) continue;
                Optional<AbstractEntityCitizen> opt = cd.getEntity();
                if (opt == null || opt.isEmpty()) continue;
                AbstractEntityCitizen c = opt.get();
                if (!c.isAlive() || c.isRemoved()) continue;
                if (near != null && c.blockPosition().distSqr(near) > radius * radius) continue;
                boolean isMourning = false;
                try {
                    isMourning = cd.getCitizenMournHandler() != null && !cd.getCitizenMournHandler().getDeceasedCitizens().isEmpty();
                } catch (Throwable ignored) {
                }
                (isMourning ? mourning : others).add(c);
            }
        } catch (Throwable ignored) {
        }
        List<AbstractEntityCitizen> out = new ArrayList<>(mourning);
        for (AbstractEntityCitizen c : others) {
            if (out.size() >= max) break;
            out.add(c);
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static void addMemory(AbstractEntityCitizen c, String event) {
        try {
            ((CitizenDataMemoryExtended) c.getCitizenData()).mc_talking$getOrInitializeMemory().addEvent(event);
        } catch (Throwable ignored) {
        }
    }

    /** Two free witnesses immediately share a mourning conversation. */
    private static void startMourningChat(MinecraftServer server, IColony colony, List<AbstractEntityCitizen> witnesses) {
        try {
            if (System.currentTimeMillis() - lastMourningChatMs < MOURNING_CHAT_COOLDOWN_MS) {
                return;
            }
            if (colony.getRaiderManager().isRaided()) {
                return; // survive first, grieve later
            }
            List<AbstractEntityCitizen> free = new ArrayList<>(2);
            for (AbstractEntityCitizen c : witnesses) {
                if (ConversationManager.canCitizenSpeak(c) && !ConversationManager.isCitizenBusy(c)) {
                    free.add(c);
                    if (free.size() == 2) break;
                }
            }
            if (free.size() < 2) {
                return;
            }
            lastMourningChatMs = System.currentTimeMillis();
            CitizenConversation conversation = new CitizenConversation(server, List.of(free.get(0), free.get(1)));
            conversation.performConversation();
            ColonistErrands.LOGGER.info("[Mourning] {} and {} started a mourning conversation",
                    safeName(free.get(0)), safeName(free.get(1)));
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("mourning chat failed", t);
        }
    }

    private static String safeName(AbstractEntityCitizen c) {
        try {
            return c.getCitizenData().getName();
        } catch (Throwable t) {
            return "citizen";
        }
    }

    public static void clearAll() {
        LAST_EVENT_COUNT.clear();
        LAST_GRAVE_COUNT.clear();
    }
}
