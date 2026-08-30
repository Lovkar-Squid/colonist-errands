package me.marko.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fuzzy citizen-by-name lookup shared by call_citizen / find_citizen / back_to_work. */
public final class Citizens {

    private Citizens() {
    }

    public static final class Match {
        public final ICitizenData data;
        public final int totalMatches;

        Match(ICitizenData data, int totalMatches) {
            this.data = data;
            this.totalMatches = totalMatches;
        }
    }

    /**
     * Finds a citizen by (part of) their name, excluding the speaker.
     * Exact full-name match wins, then first-name match, then contains;
     * among equal candidates the one nearest to the speaker wins.
     */
    public static Match findByName(IColony colony, AbstractEntityCitizen speaker, String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<ICitizenData> exact = new ArrayList<>();
        List<ICitizenData> first = new ArrayList<>();
        List<ICitizenData> contains = new ArrayList<>();
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd == null || cd.getName() == null) continue;
            if (speaker != null && cd.getEntity().isPresent()
                    && cd.getEntity().get().getUUID().equals(speaker.getUUID())) continue;
            String name = cd.getName().toLowerCase(Locale.ROOT);
            if (name.equals(q)) {
                exact.add(cd);
            } else if (name.startsWith(q + " ") || name.startsWith(q + ".")) {
                first.add(cd);
            } else if (name.contains(q)) {
                contains.add(cd);
            }
        }
        List<ICitizenData> pool = !exact.isEmpty() ? exact : !first.isEmpty() ? first : contains;
        if (pool.isEmpty()) {
            return null;
        }
        ICitizenData best = pool.get(0);
        if (speaker != null && pool.size() > 1) {
            double bestD = Double.MAX_VALUE;
            for (ICitizenData cd : pool) {
                if (cd.getEntity().isEmpty()) continue;
                double d = speaker.distanceToSqr(cd.getEntity().get());
                if (d < bestD) {
                    bestD = d;
                    best = cd;
                }
            }
        }
        return new Match(best, pool.size());
    }

    /** Compass direction + distance of 'to' as seen from 'from', e.g. "35 blocks to the northeast". */
    public static String directionFrom(net.minecraft.core.BlockPos from, net.minecraft.core.BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist < 8) {
            return "just " + dist + " blocks away";
        }
        return dist + " blocks to the " + RaidWatcher.dirName8(dx, dz);
    }
}
