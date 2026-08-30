package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenDiseaseHandler;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingHospital;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ErrandBuildings;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SendToBuildingAction extends PlayerFunctionAction {

    private static final List<String> TARGETS = buildTargets();

    private static List<String> buildTargets() {
        List<String> t = new ArrayList<>();
        t.add("home");
        t.add("own_building");
        t.addAll(ErrandBuildings.BUILDING_TYPES);
        return List.copyOf(t);
    }

    public SendToBuildingAction() {
        super("send_to_building",
                "Physically WALK to a building in the colony because the player asked you to go there. "
                        + "'home' is your own house, 'own_building' is your workplace; otherwise the nearest building of that type is chosen. "
                        + "Sending a SICK citizen to 'hospital' registers them as a patient (they stay and get treated); "
                        + "sending a hungry one to 'cook' (the restaurant) makes them actually eat there. "
                        + "If the player calls the building by a NAME (e.g. 'the gatehouse'), also pass it as building_name - "
                        + "renamed buildings are found by name and take priority. "
                        + "You will start walking as soon as the current conversation ends, so say a short goodbye and call leave_conversation right after calling this.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("target", new EnumProperty(TARGETS, true));
                    put("building_name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        if (parameters == null || !parameters.has("target")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'target' parameter.");
            return result;
        }
        String query = parameters.get("target").getAsString().trim().toLowerCase();

        BlockPos pos = null;
        String label = query;

        try {
            if (parameters.has("building_name")) {
                IBuilding named = ErrandBuildings.byCustomName(colony, citizen, parameters.get("building_name").getAsString());
                if (named != null) {
                    String namedLabel = ErrandBuildings.bestName(named);
                    ErrandManager.startBuildingErrand(citizen, named.getPosition(), namedLabel);
                    result.addProperty("success", true);
                    result.addProperty("info", "You will walk to " + namedLabel + " ("
                            + named.getPosition().toShortString() + ") as soon as this conversation ends." + Texts.GOODBYE);
                    return result;
                }
            }
        } catch (Throwable ignored) {
        }

        ICitizenData data = citizen.getCitizenData();
        if (query.equals("home")) {
            if (data != null && data.getHomeBuilding() != null) {
                pos = data.getHomeBuilding().getPosition();
                label = "home";
            } else {
                result.addProperty("success", false);
                result.addProperty("error", "You have no home assigned.");
                return result;
            }
        } else if (query.equals("own_building")) {
            if (data != null && data.getWorkBuilding() != null) {
                pos = data.getWorkBuilding().getPosition();
                label = "workplace";
            } else {
                result.addProperty("success", false);
                result.addProperty("error", "You are not assigned to any workplace.");
                return result;
            }
        } else {
            IBuilding b = ErrandBuildings.resolve(colony, citizen, query, null);
            if (b == null) {
                result.addProperty("success", false);
                result.addProperty("error", "No building of type or name '" + query + "' exists in this colony.");
                return result;
            }
            pos = b.getPosition();
            label = ErrandBuildings.bestName(b);

            // Literal intent: hospital = actually get treated, restaurant = actually eat.
            String extra = "";
            if (query.equals("hospital") && data != null) {
                try {
                    ICitizenDiseaseHandler dh = data.getCitizenDiseaseHandler();
                    if (dh != null && dh.isSick()) {
                        if (b instanceof BuildingHospital bh) {
                            bh.checkOrCreatePatientFile(data.getId());
                        }
                        dh.setSleepsAtHospital(true);
                        extra = " You ARE sick, so you are now registered as a patient: you will stay at the hospital "
                                + "and rest there until the doctor treats you.";
                        try {
                            ((me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended) data)
                                    .mc_talking$getOrInitializeMemory()
                                    .addEvent("You are admitted at the HOSPITAL and being treated - the healer will "
                                            + "cure you. You feel taken care of: REST, do not chase anyone around to "
                                            + "complain about being sick.");
                        } catch (Throwable ignored) {
                        }
                        ColonistErrands.LOGGER.info("[Errand] {} registered as hospital patient", data.getName());
                    } else {
                        extra = " Honest note: you are not actually sick, so this will just be a visit.";
                    }
                } catch (Throwable t) {
                    ColonistErrands.LOGGER.warn("hospital intent failed", t);
                }
            } else if ((query.equals("cook") || query.equals("tavern")) && data != null) {
                try {
                    double sat = data.getSaturation();
                    if (sat < 16.0) {
                        // Actually EAT there: on arrival we serve real food from the racks.
                        java.util.UUID pid = me.sshcrack.mc_talking.ConversationManager.getPlayerForEntity(citizen.getUUID());
                        ErrandManager.startEatErrand(citizen, b, pid);
                        result.addProperty("success", true);
                        result.addProperty("info", "You are hungry (saturation " + Math.round(sat) + "/20). You will walk "
                                + "to the " + label + " and EAT there until you are full, starting when this conversation ends."
                                + Texts.GOODBYE);
                        return result;
                    }
                    extra = " Honest note: you are not really hungry (saturation " + Math.round(sat) + "/20), but you will go.";
                } catch (Throwable ignored) {
                }
            }

            ErrandManager.startBuildingErrand(citizen, pos, label);
            result.addProperty("success", true);
            result.addProperty("info", "You will walk to the " + label + " (" + pos.toShortString()
                    + ") as soon as this conversation ends." + extra + Texts.GOODBYE);
            return result;
        }

        ErrandManager.startBuildingErrand(citizen, pos, label);
        result.addProperty("success", true);
        result.addProperty("info", "You will walk to the " + label + " (" + pos.toShortString()
                + ") as soon as this conversation ends." + Texts.GOODBYE);
        return result;
    }
}
