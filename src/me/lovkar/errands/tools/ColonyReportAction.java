package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import me.lovkar.errands.ColonistErrands;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ColonyReportAction extends PlayerFunctionAction {

    public ColonyReportAction() {
        super("colony_report",
                "The player asks how the COLONY as a whole is doing ('how is the colony?', 'status report', "
                        + "'how are things around here?'). Returns real numbers: population, jobs, sick/hungry/homeless, "
                        + "guards, raids, research and construction. Summarize it naturally in your own voice - "
                        + "lead with what matters most (dangers and problems first), don't read every number.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        try {
            int total = 0, unemployed = 0, children = 0, sick = 0, hungry = 0, homeless = 0, guards = 0;
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd == null) continue;
                total++;
                try {
                    if (cd.isChild()) children++;
                    else if (cd.getJob() == null) unemployed++;
                    if (cd.getWorkBuilding() instanceof AbstractBuildingGuards) guards++;
                    if (cd.getCitizenDiseaseHandler() != null && cd.getCitizenDiseaseHandler().isSick()) sick++;
                    if (cd.getSaturation() < 10.0) hungry++;
                    if (cd.getHomeBuilding() == null) homeless++;
                } catch (Throwable ignored) {
                }
            }
            StringBuilder sb = new StringBuilder("Colony status: ")
                    .append(total).append(" citizens (").append(children).append(" children, ")
                    .append(guards).append(" guards, ").append(unemployed).append(" unemployed adults). ");
            sb.append(sick > 0 ? sick + " are SICK. " : "Nobody is sick. ");
            sb.append(hungry > 0 ? hungry + " are hungry (saturation under 10/20). " : "Everyone is fed. ");
            if (homeless > 0) {
                sb.append(homeless).append(" are HOMELESS. ");
            }
            try {
                if (colony.getRaiderManager().isRaided()) {
                    sb.append("A RAID IS HAPPENING RIGHT NOW! ");
                } else if (colony.getRaiderManager().willRaidTonight()) {
                    sb.append("Scouts expect a raid TONIGHT. ");
                } else {
                    sb.append("No raid in sight. ");
                }
            } catch (Throwable ignored) {
            }
            try {
                var inProgress = colony.getResearchManager().getResearchTree().getResearchInProgress();
                sb.append(inProgress.isEmpty() ? "No research is running. "
                        : inProgress.size() + " research project(s) are running at the university. ");
            } catch (Throwable ignored) {
            }
            try {
                int orders = colony.getWorkManager().getWorkOrders().size();
                sb.append(orders > 0 ? orders + " construction work order(s) are queued or underway."
                        : "The builders have no open work orders.");
            } catch (Throwable ignored) {
            }
            ColonistErrands.LOGGER.info("[Report] colony_report served ({} citizens)", total);
            result.addProperty("success", true);
            result.addProperty("info", sb.toString());
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("colony_report failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Could not gather the colony numbers right now.");
        }
        return result;
    }
}
