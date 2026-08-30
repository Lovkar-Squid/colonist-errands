package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.ai.statemachine.AIOneTimeEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.core.colony.buildingextensions.FarmField;
import com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.entity.ai.workers.AbstractAISkeleton;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ItemFinder;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class FarmerPlantAction extends PlayerFunctionAction {

    public FarmerPlantAction() {
        super("farmer_plant",
                "FARMERS ONLY: the player tells you which crop to plant (e.g. 'plant the eggplants'). "
                        + "If one of your fields is ALREADY assigned to that crop you go work it; otherwise the crop is "
                        + "assigned to an EMPTY field. If every field is taken, nothing is changed - report the current "
                        + "assignments and ask the player; only call again with replace=true if the player confirms "
                        + "replacing one. Pass the crop exactly as the player named it. Fails politely if you are not a farmer.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("crop", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                    put("replace", new PrimitiveProperty(PrimitiveProperty.Type.BOOLEAN, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        IBuilding wb = data == null ? null : data.getWorkBuilding();
        boolean isFarmer = wb != null && wb.getBuildingType().getRegistryName().getPath().equals("farmer");
        if (!isFarmer) {
            result.addProperty("success", false);
            result.addProperty("error", "You are not a farmer - only farmers manage fields. Suggest the player asks a farmer.");
            return result;
        }
        if (parameters == null || !parameters.has("crop")) {
            result.addProperty("success", false);
            result.addProperty("error", "Missing 'crop'.");
            return result;
        }
        String query = parameters.get("crop").getAsString();
        Item seed = ItemFinder.findSeedFor(query);
        if (seed == null) {
            result.addProperty("success", false);
            result.addProperty("error", "I don't know any crop called '" + query + "'.");
            return result;
        }

        List<FarmField> fields = new ArrayList<>();
        try {
            colony.getServerBuildingManager().getMatchingBuildingExtension(ext -> {
                if (ext instanceof FarmField ff) {
                    fields.add(ff);
                }
                return false; // collect all, match none
            });
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("farmer_plant field scan failed", t);
        }
        if (fields.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "There are no fields in this colony yet.");
            return result;
        }
        boolean replace = false;
        try {
            if (parameters.has("replace")) {
                replace = parameters.get("replace").getAsBoolean();
            }
        } catch (Throwable ignored) {
        }
        String seedName = seed.getDescription().getString();
        BlockPos from = citizen.blockPosition();

        // 1) A field already assigned to this crop? Make it the farmer's CURRENT field
        //    and kick their work AI - they go check it and plant every empty spot.
        FarmField existing = nearest(fields, from, f -> sameItem(f, seed));
        if (existing != null) {
            focusFarmerOnField(citizen, existing);
            ColonistErrands.LOGGER.info("[Farmer] Field at {} already set to {} - farmer sent to work it now",
                    existing.getPosition().toShortString(), seedName);
            result.addProperty("success", true);
            result.addProperty("info", "Your field at " + existing.getPosition().toShortString() + " is ALREADY assigned to "
                    + seedName + ". You go check it RIGHT NOW as your next task: plant every empty spot and harvest "
                    + "anything that is ready." + Texts.GOODBYE);
            return result;
        }

        // 2) Otherwise use an EMPTY field (never silently overwrite another crop).
        FarmField target = nearest(fields, from, f -> {
            try {
                return f.getSeed().isEmpty();
            } catch (Throwable t) {
                return false;
            }
        });
        String verb = "assigned to your empty field";
        if (target == null) {
            if (!replace) {
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (FarmField f : fields) {
                    if (shown++ >= 6) break;
                    String cur;
                    try {
                        cur = f.getSeed().isEmpty() ? "empty" : f.getSeed().getHoverName().getString();
                    } catch (Throwable t) {
                        cur = "unknown";
                    }
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(cur).append(" at ").append(f.getPosition().toShortString());
                }
                result.addProperty("success", false);
                result.addProperty("error", "All your fields already have crops assigned: " + sb
                        + ". Tell the player and ask which crop to replace; if they confirm, call farmer_plant again "
                        + "with replace=true.");
                return result;
            }
            target = nearest(fields, from, f -> true);
            verb = "assigned (replacing the previous crop) to your field";
        }

        try {
            target.setSeed(new ItemStack(seed));
            try {
                colony.getServerBuildingManager().markBuildingExtensionsDirty();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("farmer_plant setSeed failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Could not assign the seed to the field.");
            return result;
        }
        focusFarmerOnField(citizen, target);
        ColonistErrands.LOGGER.info("[Farmer] Field at {} now set to {}", target.getPosition().toShortString(), seedName);
        result.addProperty("success", true);
        result.addProperty("info", seedName + " is now " + verb + " at " + target.getPosition().toShortString()
                + ". You go work that field RIGHT NOW as your next task and plant it "
                + "(the seeds must be available in the colony)." + Texts.GOODBYE);
        return result;
    }

    /**
     * Makes the given field the farmer's CURRENT field (module state, private id set
     * via reflection) and kicks the farmer's work AI so they head out immediately -
     * the vanilla farmer AI then hoes/plants every empty block and harvests ripe ones.
     */
    private static void focusFarmerOnField(AbstractEntityCitizen citizen, FarmField field) {
        try {
            ICitizenData data = citizen.getCitizenData();
            IBuilding wb = data == null ? null : data.getWorkBuilding();
            if (wb != null) {
                BuildingExtensionsModule module = wb.getModule(BuildingModules.FARMER_FIELDS);
                if (module != null) {
                    module.resetCurrentExtension();
                    try {
                        java.lang.reflect.Field f = BuildingExtensionsModule.class.getDeclaredField("currentExtensionId");
                        f.setAccessible(true);
                        f.set(module, field.getId());
                    } catch (Throwable t) {
                        ColonistErrands.LOGGER.warn("[Farmer] could not pin current field (AI will pick soon on its own)", t);
                    }
                    try {
                        module.markDirty();
                    } catch (Throwable ignored) {
                    }
                }
            }
            IJob<?> job = data == null ? null : data.getJob();
            if (job != null && job.getWorkerAI() instanceof AbstractAISkeleton<?> ai) {
                ai.registerTarget(new AIOneTimeEventTarget(AIWorkerState.START_WORKING));
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("focusFarmerOnField failed", t);
        }
    }

    private static FarmField nearest(List<FarmField> fields, BlockPos from, java.util.function.Predicate<FarmField> filter) {
        FarmField best = null;
        double bestD = Double.MAX_VALUE;
        for (FarmField f : fields) {
            if (!filter.test(f)) continue;
            double d = from.distSqr(f.getPosition());
            if (d < bestD) {
                bestD = d;
                best = f;
            }
        }
        return best;
    }

    private static boolean sameItem(FarmField f, net.minecraft.world.item.Item seed) {
        try {
            return !f.getSeed().isEmpty() && f.getSeed().getItem() == seed;
        } catch (Throwable t) {
            return false;
        }
    }
}
