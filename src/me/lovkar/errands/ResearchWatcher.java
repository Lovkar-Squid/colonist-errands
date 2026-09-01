package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.ILocalResearch;
import com.minecolonies.core.colony.jobs.JobResearch;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's idea: colonists should CARE about research. The university's work is
 * the colony's biggest shared story - months of a researcher's life, and every
 * trade feels the result - but until now nobody ever mentioned it unless the
 * player asked outright.
 * <p>
 * Two halves: the moment a research completes the whole colony hears about it
 * (chat + a memory each, so they bring it up themselves afterwards), and every
 * colonist's prompt carries what is on the benches right now and what was
 * finished recently, so "what are they working on up there?" gets a real answer
 * from anyone, not just the researcher.
 */
public final class ResearchWatcher {

    private ResearchWatcher() {
    }

    /** colonyId -> research ids we have already seen completed. */
    private static final Map<Integer, Set<String>> KNOWN = new ConcurrentHashMap<>();
    /** colonyId -> the last few completed research names, newest first. */
    private static final Map<Integer, LinkedList<String>> RECENT = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COLONY_OF_NAME = new ConcurrentHashMap<>();
    private static final int RECENT_CAP = 3;

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0) {
            return;
        }
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                int id = colony.getID();
                List<ResourceLocation> done;
                try {
                    done = colony.getResearchManager().getResearchTree().getCompletedList();
                } catch (Throwable t) {
                    continue;
                }
                if (done == null) continue;

                Set<String> known = KNOWN.get(id);
                if (known == null) {
                    // First pass of the session: everything already finished is old
                    // news, so record it silently instead of announcing a hundred
                    // researches the colony did months ago.
                    Set<String> seed = new HashSet<>();
                    for (ResourceLocation rl : done) {
                        seed.add(rl.toString());
                    }
                    KNOWN.put(id, seed);
                    continue;
                }
                for (ResourceLocation rl : done) {
                    String key = rl.toString();
                    if (!known.add(key)) continue;
                    announce(server, colony, describe(rl));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void announce(MinecraftServer server, IColony colony, String name) {
        int id = colony.getID();
        RECENT.computeIfAbsent(id, k -> new LinkedList<>()).addFirst(name);
        LinkedList<String> recent = RECENT.get(id);
        while (recent.size() > RECENT_CAP) {
            recent.removeLast();
        }
        ColonistErrands.LOGGER.info("[Research] Colony {} finished {}", id, name);
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal("[Research] " + colonyName(colony)
                        + " has finished researching " + name + " - the whole colony is talking about it."));
            }
        } catch (Throwable ignored) {
        }
        // Everyone gets the news, so anyone can bring it up in conversation later.
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                try {
                    boolean researcher = cd.getJob() instanceof JobResearch;
                    ((CitizenDataMemoryExtended) cd).mc_talking$getOrInitializeMemory().addEvent(
                            researcher
                                    ? "I FINISHED the research " + name + " at the university. Months of work - I am "
                                    + "quietly very proud of it."
                                    : "Word went round the colony: the university finished researching " + name
                                    + "'. Everyone is talking about what it will change.");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Appended to every colonist's prompt: the benches now, and the last wins. */
    public static String promptBlock(String citizenName) {
        try {
            Integer colonyId = colonyOf(citizenName);
            if (colonyId == null) return "";
            IColony colony = byId(colonyId);
            if (colony == null) return "";

            List<String> running = new ArrayList<>();
            try {
                for (ILocalResearch r : colony.getResearchManager().getResearchTree().getResearchInProgress()) {
                    String name = describe(r.getId());
                    int pct = 0;
                    try {
                        double max = 72.0 * Math.pow(2, Math.max(0, r.getDepth() - 1));
                        pct = (int) Math.min(100, Math.round(r.getProgress() * 100.0 / max));
                    } catch (Throwable ignored) {
                    }
                    running.add(name + " - about " + pct + "% done");
                }
            } catch (Throwable ignored) {
            }
            LinkedList<String> recent = RECENT.get(colonyId);

            if (running.isEmpty() && (recent == null || recent.isEmpty())) {
                return "";
            }
            StringBuilder sb = new StringBuilder("\n\nCOLONY RESEARCH: ");
            if (running.isEmpty()) {
                sb.append("nothing is on the university benches right now. ");
            } else {
                sb.append("the university is working on ").append(String.join(" and ", running)).append(". ");
            }
            if (recent != null && !recent.isEmpty()) {
                sb.append("Recently finished: ").append(String.join(", ", recent)).append(". ");
            }
            sb.append("The words in brackets are what each one actually DOES - that is the part people care about, "
                    + "so talk about the effect (\"we will be able to build a combat academy\"), not just the title. "
                    + "This is the colony's own progress and you are part of it - bring it up the way people talk "
                    + "about big shared news, especially when it touches your own trade, and be glad or impatient "
                    + "about it as fits your mood. Only ever mention what is listed here; never invent a research.");
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Lovkar: "'Improved Swords' is the NAME, but what it actually did was unlock
     * the Combat Academy." Research names are marketing; the effects are the
     * meaning. MineColonies keeps the plain-English effect text right there on
     * the research ("Unlocks Combat Academy"), so we hand colonists that instead
     * of a title they would have to guess at.
     */
    private static String effectOf(ResourceLocation id) {
        try {
            var research = IGlobalResearchTree.getInstance().getResearch(id);
            if (research == null) return "";
            List<String> parts = new ArrayList<>();
            for (var effect : research.getEffects()) {
                try {
                    String text = MutableComponent.create((ComponentContents) effect.getName()).getString();
                    if (text != null && !text.isBlank() && !text.contains("com.minecolonies")
                            && !parts.contains(text)) {
                        parts.add(text);
                    }
                } catch (Throwable ignored) {
                }
                if (parts.size() >= 3) break;
            }
            return String.join("; ", parts);
        } catch (Throwable t) {
            return "";
        }
    }

    /** "'Improved Swords' (Unlocks Combat Academy)" - name plus what it really does. */
    private static String describe(ResourceLocation id) {
        String name = nameOf(id);
        String effect = effectOf(id);
        return effect.isEmpty() ? "'" + name + "'" : "'" + name + "' (" + effect + ")";
    }

    private static String nameOf(ResourceLocation id) {
        try {
            return MutableComponent.create((ComponentContents) IGlobalResearchTree.getInstance()
                    .getResearch(id).getName()).getString();
        } catch (Throwable t) {
            return id.getPath().replace('_', ' ');
        }
    }

    private static String colonyName(IColony colony) {
        try {
            String n = colony.getName();
            return n == null || n.isBlank() ? "the colony" : n;
        } catch (Throwable t) {
            return "the colony";
        }
    }

    private static IColony byId(int id) {
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                if (colony.getID() == id) return colony;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer colonyOf(String citizenName) {
        if (citizenName == null || citizenName.isBlank()) return null;
        Integer cached = COLONY_OF_NAME.get(citizenName);
        if (cached != null) return cached;
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                    if (citizenName.equals(cd.getName())) {
                        COLONY_OF_NAME.put(citizenName, colony.getID());
                        return colony.getID();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void clearAll() {
        KNOWN.clear();
        RECENT.clear();
        COLONY_OF_NAME.clear();
    }
}
