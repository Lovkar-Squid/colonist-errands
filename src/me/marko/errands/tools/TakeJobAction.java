package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ErrandBuildings;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class TakeJobAction extends PlayerFunctionAction {

    public TakeJobAction() {
        super("take_job",
                "The player offers YOU a job or tells you to SWITCH jobs ('become a farmer', 'go work at the "
                        + "bakery instead', 'postani kmet'). Works for the unemployed AND for employed citizens - "
                        + "an employed citizen quits their current job and takes the new one, IF the new position "
                        + "is free (otherwise honestly report it is taken and nothing changes). Pass the workplace "
                        + "as the player named it - PROFESSION words work too (carpenter=sawmill, courier, chef, "
                        + "smelter, researcher, healer, undertaker, planter, quarrier...), building types and custom "
                        + "building names as well. "
                        + "On success you are hired on the spot and walk to your new workplace.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("building", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No citizen data.");
            return result;
        }
        if (data.isChild()) {
            result.addProperty("success", false);
            result.addProperty("error", "You are a child - children go to school, they don't take jobs.");
            return result;
        }
        if (parameters == null || !parameters.has("building")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'building'.");
            return result;
        }
        String query = parameters.get("building").getAsString().trim().toLowerCase();
        IBuilding b = ErrandBuildings.resolve(colony, citizen, query, null);
        if (b == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No building of type or name '" + query + "' exists in this colony.");
            return result;
        }
        try {
            IBuilding oldWb = data.getWorkBuilding();
            if (oldWb != null && oldWb.getPosition().equals(b.getPosition())) {
                result.addProperty("success", false);
                result.addProperty("error", "You ALREADY work at the " + ErrandBuildings.bestName(b) + ".");
                return result;
            }
            // NOTE: getModuleMatching THROWS when nothing matches (found via Marko's
            // guard-tower crash) - use the safe helper instead.
            WorkerBuildingModule module = ErrandBuildings.firstFreeWorkerModule(b);
            if (module == null) {
                // The nearest building of that type is full - maybe ANOTHER one of
                // the same type has an open position (several guard towers etc.).
                int[] typeCount = new int[1];
                IBuilding alt = ErrandBuildings.nearestOfTypeWithFreeSlot(colony, citizen, query, b, typeCount);
                if (alt != null && oldWb != null && alt.getPosition().equals(oldWb.getPosition())) {
                    alt = null; // don't "switch" the citizen into their own current building
                }
                if (alt != null) {
                    module = ErrandBuildings.firstFreeWorkerModule(alt);
                    if (module != null) {
                        ColonistErrands.LOGGER.info("[Job] nearest {} is full - using the free one at {}",
                                query, alt.getPosition().toShortString());
                        b = alt;
                    }
                }
                if (module == null) {
                    if (!ErrandBuildings.hiresWorkers(b)) {
                        result.addProperty("success", false);
                        result.addProperty("error", "The " + ErrandBuildings.bestName(b)
                                + " is not a workplace anyone can be hired at - nobody works there directly. "
                                + "Ask the player which WORK building they mean.");
                        return result;
                    }
                    result.addProperty("success", false);
                    result.addProperty("error", "The " + ErrandBuildings.bestName(b) + " has no free position right now - "
                            + "the job is taken"
                            + (typeCount[0] > 1 ? " (checked all " + typeCount[0] + " of them - every position is filled)" : "")
                            + ". Tell the player honestly; nothing changed"
                            + (data.getJob() != null ? " (you keep your current job)" : "")
                            + ". Maybe upgrading the building adds slots.");
                    return result;
                }
            }
            String jobName = module.getJobDisplayName();

            // Marko's job switching: quit the old position first - but only now,
            // AFTER we know the new one is free.
            String oldNote = "";
            WorkerBuildingModule oldModule = null;
            if (data.getJob() != null && oldWb != null) {
                String oldName = ErrandBuildings.bestName(oldWb);
                try {
                    for (WorkerBuildingModule m : oldWb.getModules(WorkerBuildingModule.class)) {
                        if (m.getAssignedCitizen().contains(data)) {
                            oldModule = m;
                            break;
                        }
                    }
                    if (oldModule != null) {
                        oldModule.removeCitizen(data);
                        try {
                            oldWb.markDirty();
                        } catch (Throwable ignored) {
                        }
                        oldNote = " You quit your old job at the " + oldName + " -";
                        ColonistErrands.LOGGER.info("[Job] {} quit {} to switch jobs", data.getName(), oldName);
                    }
                } catch (Throwable t) {
                    ColonistErrands.LOGGER.warn("take_job: releasing old job failed", t);
                }
            }

            try {
                module.setHiringMode(HiringMode.MANUAL);
            } catch (Throwable ignored) {
            }
            boolean ok = module.assignCitizen(data);
            if (!ok) {
                // Roll back to the old position so the citizen isn't left jobless.
                if (oldModule != null) {
                    try {
                        oldModule.assignCitizen(data);
                    } catch (Throwable ignored) {
                    }
                }
                result.addProperty("success", false);
                result.addProperty("error", "The hiring didn't go through (the building refused the assignment)"
                        + (oldModule != null ? " - you went back to your old job" : "") + ".");
                return result;
            }
            try {
                b.markDirty();
            } catch (Throwable ignored) {
            }
            BackToWorkAction.kickWorkAI(data);
            ErrandManager.startBuildingErrand(citizen, b.getPosition(), ErrandBuildings.bestName(b));
            ColonistErrands.LOGGER.info("[Job] {} hired as {} at {}", data.getName(), jobName,
                    ErrandBuildings.bestName(b));
            result.addProperty("success", true);
            result.addProperty("info", oldNote + " You are HIRED as " + jobName + " at the "
                    + ErrandBuildings.bestName(b) + " (position set to manual hiring so it sticks). You walk over "
                    + "and start right after this conversation ends." + Texts.GOODBYE);
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("take_job failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Something went wrong with the hiring.");
        }
        return result;
    }
}
