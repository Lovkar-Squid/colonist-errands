package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's idea #28: family members TALK to each other AS family. Every couple
 * of minutes one nearby family pair (partners, parent & child, siblings) that
 * is free starts a chat - with a memory note telling both WHO they are to each
 * other, so the dialogue is familial ("how was your day at the mine, son?").
 * The chaperone keeps them together like any other c2c conversation.
 */
public final class FamilyChats {

    private FamilyChats() {
    }

    private static final long GLOBAL_COOLDOWN_MS = 5 * 60_000;   // one family chat per ~5 min
    private static final long PAIR_COOLDOWN_MS = 30 * 60_000;    // same pair at most every 30 min
    private static final double NEAR_DIST_SQR = 20.0 * 20.0;
    private static long lastChatMs = 0;
    private static final Map<Long, Long> PAIR_LAST = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 2400 != 0) { // every ~2 minutes
            return;
        }
        if (System.currentTimeMillis() - lastChatMs < GLOBAL_COOLDOWN_MS) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getRaiderManager().isRaided()) continue;
                if (tryStartFamilyChat(server, colony)) {
                    return; // at most one per tick across all colonies
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean tryStartFamilyChat(MinecraftServer server, IColony colony) {
        List<Object[]> candidates = new ArrayList<>(); // {a, b, relationForA, relationForB}
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd == null) continue;
                // partner pair
                try {
                    ICitizenData partner = cd.getPartner();
                    if (partner != null && cd.getId() < partner.getId()) {
                        addCandidate(candidates, cd, partner,
                                partner.isFemale() ? "wife" : "husband",
                                cd.isFemale() ? "wife" : "husband");
                    }
                } catch (Throwable ignored) {
                }
                // parent -> child pairs
                try {
                    for (Integer childId : cd.getChildren()) {
                        Object childData = colony.getCitizenManager().getCivilian(childId);
                        if (childData instanceof ICitizenData child) {
                            addCandidate(candidates, cd, child,
                                    child.isFemale() ? "daughter" : "son",
                                    cd.isFemale() ? "mother" : "father");
                        }
                    }
                } catch (Throwable ignored) {
                }
                // sibling pairs
                try {
                    for (Integer sibId : cd.getSiblings()) {
                        if (cd.getId() < sibId) {
                            Object sibData = colony.getCitizenManager().getCivilian(sibId);
                            if (sibData instanceof ICitizenData sib) {
                                addCandidate(candidates, cd, sib,
                                        sib.isFemale() ? "sister" : "brother",
                                        cd.isFemale() ? "sister" : "brother");
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (candidates.isEmpty()) {
            return false;
        }
        Object[] pick = candidates.get((int) (Math.random() * candidates.size()));
        ICitizenData a = (ICitizenData) pick[0];
        ICitizenData b = (ICitizenData) pick[1];
        String bIsToA = (String) pick[2]; // what B is to A
        String aIsToB = (String) pick[3]; // what A is to B
        AbstractEntityCitizen ea = a.getEntity().get();
        AbstractEntityCitizen eb = b.getEntity().get();

        long pairKey = ((long) Math.min(a.getId(), b.getId()) << 32) | Math.max(a.getId(), b.getId());
        PAIR_LAST.put(pairKey, System.currentTimeMillis());
        lastChatMs = System.currentTimeMillis();

        addMemory(ea, "You run into your " + bIsToA + " " + b.getName()
                + " - have a warm FAMILY chat: ask about their day, their work, how they feel. You are family.");
        addMemory(eb, "You run into your " + aIsToB + " " + a.getName()
                + " - have a warm FAMILY chat: ask about their day, their work, how they feel. You are family.");
        CitizenConversation conversation = new CitizenConversation(server, List.of(ea, eb));
        conversation.performConversation();
        ColonistErrands.LOGGER.info("[Family] {} ({}) and {} ({}) start a family chat",
                a.getName(), aIsToB, b.getName(), bIsToA);
        return true;
    }

    private static void addCandidate(List<Object[]> out, ICitizenData a, ICitizenData b,
                                     String bIsToA, String aIsToB) {
        try {
            long pairKey = ((long) Math.min(a.getId(), b.getId()) << 32) | Math.max(a.getId(), b.getId());
            Long last = PAIR_LAST.get(pairKey);
            if (last != null && System.currentTimeMillis() - last < PAIR_COOLDOWN_MS) return;
            Optional<AbstractEntityCitizen> oa = a.getEntity();
            Optional<AbstractEntityCitizen> ob = b.getEntity();
            if (oa == null || oa.isEmpty() || ob == null || ob.isEmpty()) return;
            AbstractEntityCitizen ea = oa.get();
            AbstractEntityCitizen eb = ob.get();
            if (!ea.isAlive() || !eb.isAlive() || ea.level() != eb.level()) return;
            if (ea.distanceToSqr(eb) > NEAR_DIST_SQR) return;
            if (!ConversationManager.canCitizenSpeak(ea) || !ConversationManager.canCitizenSpeak(eb)) return;
            if (ConversationManager.isCitizenBusy(ea) || ConversationManager.isCitizenBusy(eb)) return;
            if (!C2cAudioFollower.isFreeToChat(ea) || !C2cAudioFollower.isFreeToChat(eb)) return;
            out.add(new Object[]{a, b, bIsToA, aIsToB});
        } catch (Throwable ignored) {
        }
    }

    private static void addMemory(AbstractEntityCitizen c, String event) {
        try {
            ((CitizenDataMemoryExtended) c.getCitizenData()).mc_talking$getOrInitializeMemory().addEvent(event);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        PAIR_LAST.clear();
        lastChatMs = 0;
    }
}
