package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.colony.buildings.modules.ExpeditionLogModule;
import com.minecolonies.core.colony.buildings.modules.expedition.ExpeditionLog;
import com.minecolonies.api.util.Tuple;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soft bridge to the Voyager mod (the End-explorer profession, job {@code voyager:voyager}).
 * Everything goes through registry keys, class names and reflection, so Colonist Errands
 * loads and runs exactly as before when Voyager is not installed. The expedition log is
 * MineColonies' own {@link ExpeditionLogModule}, which Voyager reuses - that part is read
 * directly.
 */
public final class VoyagerCompat {

    private VoyagerCompat() {
    }

    public static final String MOD_ID = "voyager";
    public static final String JOB_KEY = "voyager:voyager";
    private static final String JOB_CLASS = "me.lovkar.voyager.colony.JobVoyager";
    private static final String BUILDING_CLASS = "me.lovkar.voyager.colony.BuildingVoyager";

    private static volatile Boolean loaded;
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Method MISSING;

    static {
        Method m = null;
        try {
            m = Object.class.getMethod("hashCode");
        } catch (Throwable ignored) {
        }
        MISSING = m;
    }

    public static boolean isLoaded() {
        Boolean l = loaded;
        if (l == null) {
            try {
                l = ModList.get().isLoaded(MOD_ID);
            } catch (Throwable t) {
                l = false;
            }
            loaded = l;
        }
        return l;
    }

    /** Is this citizen a Voyager (by job registry key, class name as a fallback)? */
    public static boolean isVoyager(ICitizenData data) {
        if (data == null || !isLoaded()) {
            return false;
        }
        try {
            IJob<?> job = data.getJob();
            if (job == null) {
                return false;
            }
            try {
                if (JOB_KEY.equals(job.getJobRegistryEntry().getKey().toString())) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return JOB_CLASS.equals(job.getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is this building a Departure Point (Voyager's hut, either look)? */
    public static boolean isDeparturePoint(IBuilding building) {
        return building != null && isLoaded() && BUILDING_CLASS.equals(building.getClass().getName());
    }

    // ------------------------------------------------------------------ job state (reflection)

    /** JobVoyager.Status name: IDLE, PACKING, WAITING_SUPPLIES, WAITING_TOOLS, WAITING_PLAN,
     *  WAITING_WINDOW, WAITING_ROCKET, BOARDING, AWAY, RETURNING - or "" when unknown. */
    public static String status(ICitizenData data) {
        Object status = call(jobOf(data), "getStatus");
        return status == null ? "" : String.valueOf(status);
    }

    /** The last status line the Voyager AI logged ("waiting for expedition supplies ..."), or "". */
    public static String statusLine(ICitizenData data) {
        Object line = call(jobOf(data), "getStatusLine");
        return line == null ? "" : String.valueOf(line);
    }

    public static boolean isAway(ICitizenData data) {
        return Boolean.TRUE.equals(call(jobOf(data), "isAway"));
    }

    private static Object jobOf(ICitizenData data) {
        try {
            return data == null ? null : data.getJob();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ building state (reflection)

    public static boolean isEndGate(IBuilding building) {
        return Boolean.TRUE.equals(call(building, "isEndGate"));
    }

    /** "End Gate" or "Launchpad". */
    public static String lookName(IBuilding building) {
        return isEndGate(building) ? "End Gate" : "Launchpad";
    }

    /** Days between launch windows (3 / 2 / 1 by level), 0 when unknown. */
    public static int periodDays(IBuilding building) {
        Object days = call(building, "getPeriodDays");
        return days instanceof Number n ? n.intValue() : 0;
    }

    /** Launchpad only: the rocket is out (or landing) with the other crew. */
    public static boolean isRocketAway(IBuilding building) {
        return Boolean.TRUE.equals(call(building, "isRocketAway"));
    }

    private static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            String key = target.getClass().getName() + "#" + method;
            Method m = METHODS.get(key);
            if (m == null) {
                try {
                    m = target.getClass().getMethod(method);
                } catch (NoSuchMethodException e) {
                    m = MISSING;
                }
                METHODS.put(key, m);
            }
            if (m == MISSING) {
                return null;
            }
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ expedition log (MineColonies API)

    /** What the Departure Point's log says about the last expedition, or null when it is empty. */
    public static Expedition lastExpedition(IBuilding building) {
        if (building == null) {
            return null;
        }
        try {
            List<ExpeditionLogModule> modules = building.getModules(ExpeditionLogModule.class);
            if (modules == null || modules.isEmpty()) {
                return null;
            }
            ExpeditionLog log = modules.get(0).getLog();
            if (log == null || log.getStatus() == null || log.getStatus() == ExpeditionLog.Status.NONE) {
                return null;
            }
            Expedition e = new Expedition();
            e.status = log.getStatus().name();
            e.voyagerName = log.getName() == null ? "" : log.getName();
            e.health = log.getStat(ExpeditionLog.StatType.HEALTH);
            for (Tuple<EntityType<?>, Integer> mob : log.getMobs()) {
                try {
                    String name = EntityType.getKey(mob.getA()).getPath().replace('_', ' ');
                    e.mobs.add(plural(name, mob.getB()));
                } catch (Throwable ignored) {
                }
            }
            int shown = 0;
            for (ItemStorage storage : log.getLoot()) {
                try {
                    ItemStack stack = storage.getItemStack();
                    String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
                    e.loot.add(storage.getAmount() + " " + name);
                    if (++shown >= 6) {
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            e.lootKinds = log.getLoot().size();
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String plural(String name, int count) {
        if (count == 1) {
            return "1 " + name;
        }
        if (name.endsWith("man")) {
            return count + " " + name.substring(0, name.length() - 3) + "men";
        }
        if (name.endsWith("s") || name.endsWith("x") || name.endsWith("sh")) {
            return count + " " + name + "es";
        }
        return count + " " + name + "s";
    }

    /** A plain-words snapshot of an expedition log entry. */
    public static final class Expedition {
        public String status = "";
        public String voyagerName = "";
        public double health;
        public final List<String> mobs = new ArrayList<>();
        public final List<String> loot = new ArrayList<>();
        public int lootKinds;

        public boolean isOngoing() {
            return "STARTING".equals(status) || "IN_PROGRESS".equals(status) || "RETURNING_HOME".equals(status);
        }

        public boolean isKilled() {
            return "KILLED".equals(status);
        }

        /** "fought 3 endermen and 1 shulker and brought home 24 end stone, 6 chorus fruit" */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!mobs.isEmpty()) {
                sb.append("fought ").append(join(mobs));
            }
            if (!loot.isEmpty()) {
                sb.append(sb.length() > 0 ? " and " : "").append(isKilled() ? "had found " : "brought home ")
                        .append(join(loot));
                if (lootKinds > loot.size()) {
                    sb.append(" and more");
                }
            }
            if (sb.length() == 0) {
                sb.append(isKilled() ? "met their end before finding anything" : "found nothing worth mentioning");
            }
            return sb.toString();
        }

        private static String join(List<String> parts) {
            if (parts.size() == 1) {
                return parts.get(0);
            }
            return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        }
    }
}
