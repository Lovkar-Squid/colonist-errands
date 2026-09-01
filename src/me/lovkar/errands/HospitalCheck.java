package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingHospital;
import com.minecolonies.core.entity.ai.workers.util.Patient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lovkar's report, second round: a sick colonist STILL sometimes walked out of
 * the hospital to complain to him.
 * <p>
 * The first fix keyed on {@code sleepsAtHospital()}, which only turns true once
 * the patient is actually tucked into a bed - so everyone on the way there, or
 * waiting for the healer, was still fair game for an urgent walk halfway across
 * the colony. The hospital itself knows better: it keeps a PATIENT FILE
 * ({@link BuildingHospital#getPatients()}) from the moment it takes someone on.
 * That file is the honest "they are being looked after" signal.
 */
public final class HospitalCheck {

    private HospitalCheck() {
    }

    private record Cached(boolean patient, long until) {
    }

    private static final Map<Integer, Cached> CACHE = new ConcurrentHashMap<>();
    private static final long TTL_MS = 10_000L;

    /** Being looked after: in a hospital bed, or on the hospital's patient list. */
    public static boolean underCare(AbstractEntityCitizen citizen) {
        try {
            ICitizenData data = citizen.getCitizenData();
            if (data == null) return false;
            try {
                var dh = data.getCitizenDiseaseHandler();
                if (dh != null && dh.sleepsAtHospital()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return isPatient(data);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does a hospital in their colony hold a patient file for them? */
    public static boolean isPatient(ICitizenData data) {
        try {
            int id = data.getId();
            Cached c = CACHE.get(id);
            long now = System.currentTimeMillis();
            if (c != null && c.until() > now) {
                return c.patient();
            }
            boolean found = false;
            IColony colony = data.getColony();
            if (colony != null) {
                outer:
                for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    if (!(b instanceof BuildingHospital hospital)) continue;
                    for (Patient p : hospital.getPatients()) {
                        if (p != null && p.getId() == id) {
                            found = true;
                            break outer;
                        }
                    }
                }
            }
            CACHE.put(id, new Cached(found, now + TTL_MS));
            return found;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearAll() {
        CACHE.clear();
    }
}
