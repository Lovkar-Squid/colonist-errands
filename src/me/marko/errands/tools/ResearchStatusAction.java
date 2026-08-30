package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.research.IGlobalResearchTree;
import com.minecolonies.api.research.ILocalResearch;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingUniversity;
import com.minecolonies.core.colony.jobs.JobResearch;
import me.marko.errands.ColonistErrands;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ResearchStatusAction extends PlayerFunctionAction {

    public ResearchStatusAction() {
        super("research_status",
                "The player asks about RESEARCH ('how is the research going?', 'kako kaže z raziskavami?'). "
                        + "Returns the university level and every research in progress with its approximate "
                        + "completion percentage. Summarize naturally; mention that a level 3+ university lets "
                        + "researchers leverage time while the world is closed.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        try {
            int uniLevel = 0;
            int researchers = 0;
            int totalMana = 0;
            for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (b instanceof BuildingUniversity uni) {
                    uniLevel = Math.max(uniLevel, uni.getBuildingLevel());
                    try {
                        for (ICitizenData cd : uni.getAllAssignedCitizen()) {
                            if (cd != null && cd.getJob() instanceof JobResearch jr) {
                                researchers++;
                                totalMana += jr.getCurrentMana();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            if (uniLevel == 0) {
                sb.append("The colony has no university yet, so no research can run. ");
            } else {
                sb.append("The university is level ").append(uniLevel).append(" with ").append(researchers)
                        .append(" researcher(s). ");
                if (uniLevel >= 3) {
                    sb.append("(Level 3+: researchers bank extra progress for time the player is away");
                    if (totalMana > 0) {
                        sb.append(" - they currently hold ").append(totalMana).append(" stored work credit");
                    }
                    sb.append(".) ");
                }
            }
            List<ILocalResearch> inProgress = colony.getResearchManager().getResearchTree().getResearchInProgress();
            if (inProgress.isEmpty()) {
                sb.append("No research is in progress right now.");
            } else {
                sb.append("In progress: ");
                boolean first = true;
                for (ILocalResearch r : inProgress) {
                    String name = "a research";
                    try {
                        name = MutableComponent.create((ComponentContents) IGlobalResearchTree.getInstance()
                                .getResearch(r.getBranch(), r.getId()).getName()).getString();
                    } catch (Throwable ignored) {
                    }
                    int pct = 0;
                    try {
                        double max = 72.0 * Math.pow(2, Math.max(0, r.getDepth() - 1));
                        pct = (int) Math.min(100, Math.round(r.getProgress() * 100.0 / max));
                    } catch (Throwable ignored) {
                    }
                    if (!first) sb.append("; ");
                    sb.append("'").append(name).append("' at roughly ").append(pct).append("%");
                    first = false;
                }
                sb.append(".");
            }
            ColonistErrands.LOGGER.info("[Report] research_status: uni L{}, {} running", uniLevel, inProgress.size());
            result.addProperty("success", true);
            result.addProperty("info", sb.toString());
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("research_status failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Could not read the research state right now.");
        }
        return result;
    }
}
