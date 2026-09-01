package me.lovkar.errands.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenHappinessHandler;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import me.lovkar.errands.ColonistErrands;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CitizenReportAction extends PlayerFunctionAction {

    public CitizenReportAction() {
        super("citizen_report",
                "Get your own real status numbers to report honestly to the player: happiness (0-10), what exactly "
                        + "makes you unhappy or happy, health, job and home. Use when the player asks how you are doing, "
                        + "what you need, or for a status report. Summarize the returned data naturally in your own words.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No citizen data available.");
            return result;
        }
        try {
            ICitizenHappinessHandler hh = data.getCitizenHappinessHandler();
            double happiness = hh.getHappiness(colony, data);
            result.addProperty("happiness_0_to_10", Math.round(happiness * 10.0) / 10.0);

            JsonArray unhappyAbout = new JsonArray();
            JsonArray happyAbout = new JsonArray();
            for (String name : hh.getModifiers()) {
                try {
                    IHappinessModifier m = hh.getModifier(name);
                    if (m == null) continue;
                    double f = m.getFactor(data);
                    if (f < 0.99) {
                        unhappyAbout.add(name + " (factor " + Math.round(f * 100.0) / 100.0 + ")");
                    } else if (f > 1.01) {
                        happyAbout.add(name);
                    }
                } catch (Throwable ignored) {
                }
            }
            result.add("unhappy_about", unhappyAbout);
            result.add("happy_about", happyAbout);

            result.addProperty("health", Math.round(citizen.getHealth() * 10.0) / 10.0 + "/"
                    + Math.round(citizen.getMaxHealth() * 10.0) / 10.0);
            result.addProperty("job", data.getWorkBuilding() != null
                    ? data.getWorkBuilding().getBuildingType().getRegistryName().getPath()
                    : "unemployed");
            result.addProperty("has_home", data.getHomeBuilding() != null);
            result.addProperty("success", true);
            result.addProperty("info", "Report the interesting parts of this honestly and naturally in your own words; "
                    + "modifier names are internal ids (e.g. food, housing, damage) - translate them for the player.");
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("citizen_report failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Could not read your status right now.");
        }
        return result;
    }
}
