package me.marko.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.happiness.ExpirationBasedHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier;
import com.minecolonies.core.colony.eventhooks.buildingEvents.AbstractBuildingEvent;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Marko's report: "I promised a guard tower, then built it - and nothing
 * registered". Promises used to close ONLY when the player TOLD the citizen
 * (model calls resolve_promise). This watcher makes the colony NOTICE on its
 * own that a promise came true:
 *  - a building whose name appears in the promise text gets BUILT/upgraded
 *    (BuildingBuiltEvent from the colony event log, fed via DeathWatcher's scan)
 *  - a housing/work/health promise's need is first SEEN unmet, then met
 *    (homeless -> housed, jobless -> employed, sick -> healthy)
 * On detection: promise auto-marked KEPT, citizen gets a delighted memory +
 * the real mood boost + rapport, and the promiser sees a chat line, so
 * fulfillment is visibly registered.
 */
public final class PromiseWatcher {

    private PromiseWatcher() {
    }

    /** citizenName|promiseText of promises whose NEED we've already seen unmet. */
    private static final Set<String> NEED_SEEN = new HashSet<>();

    // ------------------------------------------------------------------
    // Building built/upgraded (called from DeathWatcher's incremental scan)
    // ------------------------------------------------------------------

    public static void onBuildingEvent(MinecraftServer server, IColony colony, AbstractBuildingEvent event) {
        try {
            String buildingName = event.getBuildingName();
            if (buildingName == null || buildingName.isBlank()) {
                return;
            }
            for (PromiseStore.Promise p : PromiseStore.openPromises()) {
                if (!textMentionsBuilding(p.text, buildingName)) {
                    continue;
                }
                ICitizenData cd = findCitizen(colony, p.citizenName);
                if (cd == null) {
                    continue; // promise belongs to another colony's citizen
                }
                if (PromiseStore.resolve(p, true)) {
                    applyKept(server, colony, cd, p, "the " + buildingName + " now stands finished");
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("promise building check failed", t);
        }
    }

    /** "He will build a guard tower next to my house" mentions building "Guard Tower". */
    static boolean textMentionsBuilding(String promiseText, String buildingName) {
        if (promiseText == null) {
            return false;
        }
        String squashedPromise = squash(promiseText);
        String squashedBuilding = squash(buildingName);
        if (squashedBuilding.length() >= 5 && squashedPromise.contains(squashedBuilding)) {
            return true;
        }
        // Token match: words of the building name (>=4 chars) looked up in the
        // promise text - a single-word building matches on its word, multi-word
        // buildings need BOTH words ("guard tower" promise <- "Guard Tower").
        int hits = 0;
        int considered = 0;
        for (String word : buildingName.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() < 4) continue;
            considered++;
            if (promiseText.toLowerCase(Locale.ROOT).contains(word)) {
                hits++;
            }
        }
        if (considered == 0) {
            return false;
        }
        return hits >= 2 || (hits == 1 && considered == 1);
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    // ------------------------------------------------------------------
    // Need-based promises: housing / work / health
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0) { // every 10 s
            return;
        }
        try {
            var open = PromiseStore.openPromises();
            if (open.isEmpty()) {
                return;
            }
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (PromiseStore.Promise p : open) {
                    String about = p.about == null ? "general" : p.about;
                    if (!"housing".equals(about) && !"work".equals(about) && !"health".equals(about)) {
                        continue;
                    }
                    ICitizenData cd = findCitizen(colony, p.citizenName);
                    if (cd == null) {
                        continue;
                    }
                    boolean unmet;
                    String detail;
                    switch (about) {
                        case "housing" -> {
                            unmet = cd.getHomeBuilding() == null;
                            detail = "you finally have a HOME of your own";
                        }
                        case "work" -> {
                            unmet = cd.getWorkBuilding() == null || cd.getJob() == null;
                            detail = "you finally have a JOB";
                        }
                        default -> {
                            boolean sick = false;
                            try {
                                sick = cd.getCitizenDiseaseHandler() != null && cd.getCitizenDiseaseHandler().isSick();
                            } catch (Throwable ignored) {
                            }
                            unmet = sick;
                            detail = "you are HEALTHY again";
                        }
                    }
                    String key = p.citizenName + "|" + p.text;
                    if (unmet) {
                        // The promised need is (still) unmet - remember that, so
                        // fulfillment is only detected after a real change. This
                        // protects "upgrade" promises from instant false keeps.
                        NEED_SEEN.add(key);
                    } else if (NEED_SEEN.remove(key)) {
                        if (PromiseStore.resolve(p, true)) {
                            applyKept(server, colony, cd, p, detail);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("promise need check failed", t);
        }
    }

    // ------------------------------------------------------------------

    private static ICitizenData findCitizen(IColony colony, String name) {
        try {
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd != null && name.equals(cd.getName())) {
                    return cd;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Full "promise kept" effects: mood boost, memory, rapport, chat feedback to the promiser. */
    private static void applyKept(MinecraftServer server, IColony colony, ICitizenData cd,
                                  PromiseStore.Promise p, String detail) {
        String maker = PromiseStore.makerLabel(p);
        try {
            cd.getCitizenHappinessHandler().addModifier(new ExpirationBasedHappinessModifier(
                    "promise", 2.0, new StaticHappinessSupplier(2.0), 3));
        } catch (Throwable ignored) {
        }
        try {
            ((CitizenDataMemoryExtended) cd).mc_talking$getOrInitializeMemory()
                    .addEvent("You just realized " + maker + "'s promise to you (\"" + p.text + "\") came TRUE - "
                            + detail + "! You are delighted; thank " + maker + " warmly the next time you talk.");
        } catch (Throwable ignored) {
        }
        try {
            if (p.byPlayer != null && !p.byPlayer.isBlank()) {
                RelationStore.promiseResolved(cd.getName(), p.byPlayer, true, PromiseStore.currentDay());
            }
        } catch (Throwable ignored) {
        }
        try {
            ServerPlayer promiser = null;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (p.byPlayer != null && p.byPlayer.equals(sp.getGameProfile().getName())) {
                    promiser = sp;
                    break;
                }
            }
            String msg = "[Promises] " + cd.getName() + " noticed you KEPT your promise (\"" + p.text + "\") - "
                    + "they are delighted!";
            if (promiser != null) {
                promiser.sendSystemMessage(Component.literal(msg));
            } else {
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    sp.sendSystemMessage(Component.literal(msg));
                }
            }
        } catch (Throwable ignored) {
        }
        // The delighted citizen would love to say thanks in person if both are free.
        try {
            Optional<AbstractEntityCitizen> opt = cd.getEntity();
            if (opt != null && opt.isPresent()) {
                ColonistErrands.LOGGER.info("[Promises] {} realized the promise '{}' was kept ({})",
                        cd.getName(), p.text, detail);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        NEED_SEEN.clear();
    }
}
