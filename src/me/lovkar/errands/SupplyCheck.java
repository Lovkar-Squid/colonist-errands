package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's report: the forester (and others) walk up to pester the player for
 * TOOLS while the request system is already handling it - a crafter is making
 * the tool or the courier is on the way. A worker whose open request is being
 * actively worked has nothing to report; the request pipeline will deliver.
 *
 * A request stuck with NO resolver (CREATED/REPORTED/ASSIGNING forever - no
 * stock, nobody can craft it) keeps the complaint alive: that IS worth telling
 * the player.
 */
public final class SupplyCheck {

    private SupplyCheck() {
    }

    /** Request states that mean "someone is actively producing/delivering this". */
    private static final Set<RequestState> UNDERWAY = EnumSet.of(
            RequestState.ASSIGNED, RequestState.IN_PROGRESS, RequestState.RESOLVED,
            RequestState.FOLLOWUP_IN_PROGRESS, RequestState.FINALIZING);

    /** citizen name -> {expiry millis, 0/1}. */
    private static final Map<String, long[]> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 10_000L;

    /** True when this worker has at least one open request that the request
     *  system is actively fulfilling (crafting underway / courier delivering). */
    public static boolean requestsUnderway(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            long[] cached = CACHE.get(data.getName());
            if (cached != null && cached[0] > now) {
                return cached[1] == 1L;
            }
            boolean result = compute(data);
            CACHE.put(data.getName(), new long[] {now + TTL_MS, result ? 1L : 0L});
            return result;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean compute(ICitizenData data) {
        try {
            IBuilding work = data.getWorkBuilding();
            if (work == null) {
                return false;
            }
            Collection<IRequest<?>> open = work.getOpenRequests(data.getId());
            if (open == null || open.isEmpty()) {
                return false;
            }
            for (IRequest<?> request : open) {
                if (request != null && UNDERWAY.contains(request.getState())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Prompt line - cache-only read, safe from prompt worker threads. */
    public static String promptLine(String citizenName) {
        long[] cached = citizenName == null ? null : CACHE.get(citizenName);
        if (cached == null || cached[0] <= System.currentTimeMillis() || cached[1] != 1L) {
            return "";
        }
        return "\n\nSUPPLY RULES: The tools/materials you are missing are already ORDERED and being handled - "
                + "a crafter is making them or the courier is on the way. Do NOT ask players to bring you tools "
                + "or materials; mention the wait only if someone asks about your work.";
    }

    public static void clearAll() {
        CACHE.clear();
    }
}
