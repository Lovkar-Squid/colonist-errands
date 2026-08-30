package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's report: citizens walked up to HIM to complain about hunger even
 * though they carried edible food in their own inventory, or the restaurant
 * had food ready to serve. Hunger they can resolve on their own (the vanilla
 * MineColonies EatTask will feed them) is not worth pestering the player -
 * mirror EatTask's own checks (FoodUtils.getBestFoodForCitizen for the
 * inventory, FoodUtils.checkForFoodInBuilding for the restaurant racks) and
 * mute the "food" urgency component while either would succeed.
 *
 * A citizen whose food is genuinely inedible FOR THEM (home level demands
 * better quality) or whose colony has nothing to serve still complains -
 * that feedback is real and the player should hear it.
 */
public final class FoodCheck {

    private FoodCheck() {
    }

    /** citizen name -> {expiry millis, mode 0=no/1=inventory/2=restaurant}. Short TTL so
     *  a player HANDING food over mutes the begging within seconds. */
    private static final Map<String, long[]> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 10_000L;

    /** True when this citizen's hunger will resolve without the player: edible
     *  food in their own inventory, or servable food in the nearest restaurant. */
    public static boolean canResolveHungerAlone(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null || data.getColony() == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            long[] cached = CACHE.get(data.getName());
            if (cached != null && cached[0] > now) {
                return cached[1] != 0L;
            }
            long mode = compute(citizen, data);
            CACHE.put(data.getName(), new long[] {now + TTL_MS, mode});
            return mode != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long compute(AbstractEntityCitizen citizen, ICitizenData data) {
        // Nearest restaurant (same lookup EatTask uses) + its menu, if any.
        BuildingCook cook = null;
        Set<ItemStorage> menu = null;
        try {
            BlockPos anchor = data.getWorkBuilding() != null ? data.getWorkBuilding().getPosition() : citizen.blockPosition();
            BlockPos restaurantPos = data.getColony().getServerBuildingManager().getBestBuilding(anchor, BuildingCook.class);
            if (restaurantPos != null) {
                cook = data.getColony().getServerBuildingManager().getBuilding(restaurantPos, BuildingCook.class);
                if (cook != null) {
                    RestaurantMenuModule menuModule = cook.getModule(BuildingModules.RESTAURANT_MENU);
                    if (menuModule != null) {
                        menu = menuModule.getMenu();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // (a) Edible food in their own inventory - EatTask tries the menu first,
        //     then anything they are allowed to eat.
        try {
            if (FoodUtils.getBestFoodForCitizen(citizen.getInventoryCitizen(), data, menu) >= 0) {
                return 1L;
            }
            if (menu != null && FoodUtils.getBestFoodForCitizen(citizen.getInventoryCitizen(), data, null) >= 0) {
                return 1L;
            }
        } catch (Throwable ignored) {
        }
        // (b) The restaurant can serve them something they may eat.
        try {
            if (cook != null && FoodUtils.checkForFoodInBuilding(data, menu, (IBuilding) cook) != null) {
                return 2L;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    /**
     * Prompt line for this citizen's conversations. Reads ONLY the cache (the
     * urgency assessor keeps it warm from the server thread) - prompt building
     * happens on worker threads where rack scanning is not allowed.
     */
    public static String promptLine(String citizenName, double saturation) {
        if (saturation >= 8.0) {
            return "";
        }
        long[] cached = citizenName == null ? null : CACHE.get(citizenName);
        long mode = (cached != null && cached[0] > System.currentTimeMillis()) ? cached[1] : -1L;
        StringBuilder sb = new StringBuilder("\n\nFOOD RULES: ");
        if (mode == 1L) {
            sb.append("You are carrying food you can eat RIGHT NOW - NEVER ask players for food, you will eat from your pockets at your next break. ");
        } else if (mode == 2L) {
            sb.append("The restaurant has food ready for you - when hungry you go eat THERE; NEVER ask players for food. ");
        }
        sb.append("If a player hands you food, that SOLVES it: thank them once and never mention hunger again in this conversation.");
        return sb.toString();
    }

    public static void clearAll() {
        CACHE.clear();
    }
}
