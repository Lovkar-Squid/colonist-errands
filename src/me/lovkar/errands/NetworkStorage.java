package me.lovkar.errands;

import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Lovkar linked a MineColonies Compatibility "Common Network Storage" block to his
 * warehouse and the couriers started filling the chests behind it - the mod dumps
 * into linked storage BEFORE the racks, so from that moment most new stock never
 * touches a rack at all. Everything in this addon that read the warehouse was
 * reading racks only: check_stock would have said "we have 40" with 500 in the
 * chests, fetch errands would have come back empty-handed, and CraftWatch would
 * have sent a courier for materials that were already in the building.
 * <p>
 * This is the one place that knows about the network storage module, and it knows
 * about it only through reflection: MineColonies Compatibility is optional, and a
 * world without it must never load a class that is not there. Every call returns
 * the "no network storage" answer - zero, nothing, the stack untouched - when the
 * mod is absent or anything at all goes wrong.
 * <p>
 * The module's own contract (read from its code): {@code getExtractableBlocks()}
 * and {@code getInsertableBlocks()} stream the linked views whose access
 * direction allows it; a view's {@code getAllStacks()} is an aggregate refreshed
 * every five seconds, {@code extractItem(template, simulate)} takes up to the
 * template's count of that item, {@code insertItem(stack, simulate)} returns the
 * remainder.
 */
public final class NetworkStorage {

    private NetworkStorage() {
    }

    private static final String MODULES_CLASS =
            "steve_gall.minecolonies_compatibility.core.common.init.ModBuildingModules";

    private static boolean resolved = false;
    private static boolean present = false;
    private static BuildingEntry.ModuleProducer<?, ?> producer;
    private static Method mExtractable;
    private static Method mInsertable;
    private static Method mAllStacks;
    private static Method mExtract;
    private static Method mInsert;

    private static synchronized boolean resolve() {
        if (resolved) {
            return present;
        }
        resolved = true;
        try {
            Class<?> modules = Class.forName(MODULES_CLASS);
            Field f = modules.getField("NETWORK_STORAGE");
            producer = (BuildingEntry.ModuleProducer<?, ?>) f.get(null);
            Class<?> module = Class.forName(
                    "steve_gall.minecolonies_compatibility.core.common.building.module.NetworkStorageModule");
            mExtractable = module.getMethod("getExtractableBlocks");
            mInsertable = module.getMethod("getInsertableBlocks");
            Class<?> view = Class.forName(
                    "steve_gall.minecolonies_compatibility.api.common.building.module.INetworkStorageView");
            mAllStacks = view.getMethod("getAllStacks");
            mExtract = view.getMethod("extractItem", ItemStack.class, boolean.class);
            mInsert = view.getMethod("insertItem", ItemStack.class, boolean.class);
            present = true;
            ColonistErrands.LOGGER.info("[Stock] MineColonies Compatibility network storage found - "
                    + "linked storage counts as warehouse stock");
        } catch (ClassNotFoundException e) {
            present = false; // the mod is simply not installed
        } catch (Throwable t) {
            present = false;
            ColonistErrands.LOGGER.info("[Stock] Network storage present but not readable ({}) - racks only",
                    t.toString());
        }
        return present;
    }

    /** The building's NetworkStorageModule, or null (no mod, no module, not a warehouse). */
    private static Object moduleOf(IBuilding building) {
        if (building == null || !resolve()) {
            return null;
        }
        try {
            return building.getModule((BuildingEntry.ModuleProducer) producer);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> views(IBuilding building, boolean forExtract) {
        List<Object> out = new ArrayList<>();
        Object module = moduleOf(building);
        if (module == null) {
            return out;
        }
        try {
            Stream<Object> s = (Stream<Object>) (forExtract ? mExtractable : mInsertable).invoke(module);
            s.forEach(out::add);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** True if this building has at least one linked storage it can take from. */
    public static boolean canExtractFrom(IBuilding building) {
        return !views(building, true).isEmpty();
    }

    /** How many matching items sit in the linked storage of this building. */
    @SuppressWarnings("unchecked")
    public static int count(IBuilding building, Predicate<ItemStack> matches) {
        int total = 0;
        for (Object view : views(building, true)) {
            try {
                Stream<ItemStack> stacks = (Stream<ItemStack>) mAllStacks.invoke(view);
                for (ItemStack st : (Iterable<ItemStack>) stacks::iterator) {
                    if (st != null && !st.isEmpty() && matches.test(st)) {
                        total += st.getCount();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return total;
    }

    public static int count(IBuilding building, Item item) {
        return count(building, st -> st.getItem() == item);
    }

    /**
     * Take up to {@code wanted} of the item out of the linked storage and put it
     * into {@code into}. Whatever does not fit goes straight back where it came
     * from - nothing is ever left on the floor.
     *
     * @return how many actually landed in {@code into}
     */
    public static int extractInto(IBuilding building, Item item, int wanted, IItemHandler into) {
        int taken = 0;
        if (wanted <= 0 || into == null) {
            return 0;
        }
        for (Object view : views(building, true)) {
            while (taken < wanted) {
                int chunk = Math.min(wanted - taken, new ItemStack(item).getMaxStackSize());
                ItemStack got;
                try {
                    got = (ItemStack) mExtract.invoke(view, new ItemStack(item, chunk), false);
                } catch (Throwable t) {
                    got = ItemStack.EMPTY;
                }
                if (got == null || got.isEmpty()) {
                    break;
                }
                ItemStack rest = ItemHandlerHelper.insertItemStacked(into, got, false);
                int landed = got.getCount() - rest.getCount();
                taken += landed;
                if (!rest.isEmpty()) {
                    try {
                        ItemStack back = (ItemStack) mInsert.invoke(view, rest, false);
                        if (back != null && !back.isEmpty()) {
                            ColonistErrands.LOGGER.warn("[Stock] {} x{} could not go back into network storage",
                                    back.getHoverName().getString(), back.getCount());
                        }
                    } catch (Throwable ignored) {
                    }
                    return taken; // the bag is full - no point trying further views
                }
                if (landed < chunk) {
                    break; // this view ran dry
                }
            }
            if (taken >= wanted) {
                break;
            }
        }
        return taken;
    }

    /** Put a stack into the linked storage (insert-capable views only). Returns the remainder. */
    public static ItemStack insert(IBuilding building, ItemStack stack) {
        ItemStack rest = stack;
        for (Object view : views(building, false)) {
            if (rest == null || rest.isEmpty()) {
                break;
            }
            try {
                ItemStack r = (ItemStack) mInsert.invoke(view, rest, false);
                rest = r == null ? ItemStack.EMPTY : r;
            } catch (Throwable ignored) {
            }
        }
        return rest == null ? ItemStack.EMPTY : rest;
    }
}
