package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import me.marko.errands.ErrandBuildings;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class GatherAtAction extends PlayerFunctionAction {

    private static final int MAX_GATHER = 10;
    private static final List<String> GATHER_TARGETS = buildTargets();

    private static List<String> buildTargets() {
        List<String> t = new ArrayList<>();
        t.add("here");
        t.addAll(ErrandBuildings.BUILDING_TYPES);
        return List.copyOf(t);
    }

    public GatherAtAction() {
        super("gather_at",
                "Call an assembly: up to 10 free colonists walk to a gathering point. "
                        + "'here' (the default) means the spot where the player is standing right now; "
                        + "or choose a building type (e.g. 'townhall') and the nearest such building becomes the gathering point. "
                        + "who='anyone' (default) gathers free civilians and leaves guards on duty; "
                        + "who='guards' is a military muster - ONLY guards come (they leave their posts and resume duty afterwards). "
                        + "count: how many to send (1-10; default 10) - use it when the player names a number "
                        + "(e.g. 'five guards to the gatehouse'). "
                        + "Everyone HOLDS at the gathering point until the player dismisses them (dismiss tool) or ~10 minutes pass. "
                        + "Citizens already busy are skipped. "
                        + "If the player names the gathering place (e.g. 'the gatehouse'), also pass it as building_name - "
                        + "renamed buildings are found by name and take priority. "
                        + "Use when the player asks to gather everyone, call a meeting, or muster the guards somewhere.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("target", new EnumProperty(GATHER_TARGETS, false));
                    put("building_name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                    put("who", new EnumProperty(List.of("anyone", "guards"), false));
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        MinecraftServer server = citizen.getServer();
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }

        String query = "here";
        try {
            if (parameters != null && parameters.has("target")) {
                query = parameters.get("target").getAsString().trim().toLowerCase();
            }
        } catch (Throwable ignored) {
        }
        String nameQuery = null;
        boolean guardsOnly = false;
        int limit = MAX_GATHER;
        try {
            if (parameters != null && parameters.has("building_name")) {
                nameQuery = parameters.get("building_name").getAsString();
            }
            if (parameters != null && parameters.has("who")) {
                guardsOnly = "guards".equalsIgnoreCase(parameters.get("who").getAsString().trim());
            }
            if (parameters != null && parameters.has("count")) {
                limit = Math.max(1, Math.min(MAX_GATHER, parameters.get("count").getAsInt()));
            }
        } catch (Throwable ignored) {
        }

        BlockPos pos;
        String label;
        double arriveDistSq;
        IBuilding named = ErrandBuildings.byCustomName(colony, citizen, nameQuery);
        if (named == null && !query.isEmpty() && !query.equals("here")) {
            // The player's word may have landed in target (e.g. "gatehouse" is not a real type).
            IBuilding viaType = ErrandBuildings.resolve(colony, citizen, query, null);
            if (viaType != null && ErrandBuildings.nearestOfType(colony, citizen, query) == null) {
                named = viaType;
            }
        }
        if (named != null) {
            pos = named.getPosition();
            label = ErrandBuildings.bestName(named);
            arriveDistSq = 25.0;
        } else if (query.isEmpty() || query.equals("here")) {
            pos = player.blockPosition();
            label = "the player's position";
            arriveDistSq = 16.0;
        } else {
            IBuilding b = ErrandBuildings.nearestOfType(colony, citizen, query);
            if (b == null) {
                result.addProperty("success", false);
                result.addProperty("error", "No building of type '" + query + "' exists in this colony.");
                return result;
            }
            pos = b.getPosition();
            label = "the " + query;
            arriveDistSq = 25.0;
        }

        List<AbstractEntityCitizen> candidates = new ArrayList<>();
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd == null) continue;
            Optional<AbstractEntityCitizen> opt = cd.getEntity();
            if (opt == null || opt.isEmpty()) continue;
            AbstractEntityCitizen c = opt.get();
            if (!c.isAlive() || c.isRemoved()) continue;
            if (c.getUUID().equals(citizen.getUUID())) continue;           // the speaker stays with the player
            if (c.level() != player.level()) continue;
            boolean isGuard = cd.getWorkBuilding() instanceof AbstractBuildingGuards;
            if (guardsOnly != isGuard) continue; // default: guards keep guarding; muster: ONLY guards
            if (ConversationManager.isCitizenBusy(c)) continue;            // talking or already on an errand
            if (ErrandManager.hasErrand(c)) continue;
            candidates.add(c);
        }
        candidates.sort(Comparator.comparingDouble(c -> c.blockPosition().distSqr(pos)));

        int count = 0;
        for (AbstractEntityCitizen c : candidates) {
            if (count >= limit) break;
            ErrandManager.enqueueGatherErrand(c, pos, "gathering point", 20 * 240, arriveDistSq);
            count++;
        }

        result.addProperty("success", true);
        result.addProperty("count", count);
        String kind = guardsOnly ? "guard(s)" : "colonist(s)";
        result.addProperty("info", count == 0
                ? (guardsOnly ? "No free guards are available to muster right now." : "No free colonists are available to gather right now.")
                : count + " " + kind + " are on their way to " + label + " (arriving one after another) and will HOLD "
                        + "at the gathering point until the player dismisses them (or ~10 minutes). "
                        + "Tell the player where the gathering point is." + Texts.GOODBYE);
        return result;
    }
}
