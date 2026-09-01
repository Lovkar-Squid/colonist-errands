package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar: "Prudence is stuck again - she is not eating, not building and not
 * walking. She has a restart scheduled but it takes forever."
 * <p>
 * It is not slow. It is waiting for something that never happens:
 * <pre>
 *   // AbstractEntityAIBasic
 *   private boolean shouldRestart() {
 *       return worker.getCitizenData().shouldRestart() &amp;&amp; this.isPaused();
 *   }
 *   // CitizenData
 *   public void scheduleRestart(ServerPlayer p) { originPlayerRestart = p; restartScheduled = true; }
 * </pre>
 * The Restart button only raises a flag. The restart itself is a STATE_BLOCKING
 * event that fires only while the worker is ALSO PAUSED - so a restart on its own
 * sits there indefinitely. Pausing the worker for a moment is what actually
 * performs it, which is why pause-then-unpause has always "fixed" stuck
 * colonists.
 * <p>
 * So we finish the job the player already asked for: a restart that has been
 * pending for a while gets a pause, and once MineColonies has cleared the flag we
 * hand the worker straight back. We only ever un-pause workers WE paused - a
 * worker the player parked stays parked.
 */
public final class RestartNudge {

    private RestartNudge() {
    }

    /** citizen id -> when we first saw a restart pending. */
    private static final Map<Integer, Long> PENDING_SINCE = new ConcurrentHashMap<>();
    /** Workers we paused ourselves, and may therefore un-pause. */
    private static final Set<Integer> OURS = ConcurrentHashMap.newKeySet();
    /** Give MineColonies a moment first - the player may pause them themselves. */
    private static final long GRACE_MS = 20_000L;
    /** Never leave a worker parked because of us. */
    private static final long GIVE_UP_MS = 3 * 60_000L;

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
                for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
                    try {
                        int id = data.getId();
                        if (!data.shouldRestart()) {
                            PENDING_SINCE.remove(id);
                            if (OURS.remove(id)) {
                                setPaused(data, false);
                                broadcast(server, "[Restart] " + data.getName()
                                        + " has been restarted and is back at work.");
                                ColonistErrands.LOGGER.info("[Restart] {} restarted, un-paused", data.getName());
                            }
                            continue;
                        }
                        Long since = PENDING_SINCE.putIfAbsent(id, now);
                        if (since == null) {
                            continue;
                        }
                        if (OURS.contains(id)) {
                            // We paused them and the flag is still up - do not park
                            // them forever if something else is wrong.
                            if (now - since > GIVE_UP_MS) {
                                OURS.remove(id);
                                PENDING_SINCE.remove(id);
                                setPaused(data, false);
                                broadcast(server, "[Restart] " + data.getName() + " would not restart even while "
                                        + "paused - I have un-paused them again. Something else is holding their AI.");
                                ColonistErrands.LOGGER.warn("[Restart] {} did not restart while paused", data.getName());
                            }
                            continue;
                        }
                        if (isPaused(data) || now - since < GRACE_MS) {
                            continue; // already paused: MineColonies will do it by itself
                        }
                        if (setPaused(data, true)) {
                            OURS.add(id);
                            broadcast(server, "[Restart] " + data.getName() + " had a restart waiting. MineColonies "
                                    + "only performs a restart while the worker is PAUSED, so a scheduled restart "
                                    + "on its own waits forever - pausing them for a moment now to let it through.");
                            ColonistErrands.LOGGER.info("[Restart] Paused {} to let their scheduled restart run",
                                    data.getName());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** {@code setPaused} lives on the implementation, not on ICitizenData. */
    private static boolean setPaused(ICitizenData data, boolean paused) {
        try {
            if (data instanceof com.minecolonies.core.colony.CitizenData cd) {
                cd.setPaused(paused);
                data.markDirty(0);
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = data.getClass().getMethod("setPaused", boolean.class);
            m.invoke(data, paused);
            data.markDirty(0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPaused(ICitizenData data) {
        try {
            if (data instanceof com.minecolonies.core.colony.CitizenData cd) {
                return cd.isPaused();
            }
            java.lang.reflect.Method m = data.getClass().getMethod("isPaused");
            return Boolean.TRUE.equals(m.invoke(data));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void broadcast(MinecraftServer server, String msg) {
        try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal(msg));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearAll() {
        PENDING_SINCE.clear();
        OURS.clear();
    }
}
