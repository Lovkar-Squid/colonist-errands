package me.lovkar.errands.tools;

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

public class WhyUnhappyAction extends PlayerFunctionAction {

    public WhyUnhappyAction() {
        super("why_unhappy",
                "The player asks how happy YOU are or why you are unhappy ('are you happy here?', 'why are you "
                        + "sad?', 'what is bothering you?'). Returns your REAL happiness score and the actual factors dragging "
                        + "it down (housing, food, security, health, job...). Voice them as your own honest feelings - "
                        + "complain about the bad ones, appreciate the good - and do NOT read raw factor names or numbers aloud.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();
        if (data == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No citizen data.");
            return result;
        }
        try {
            ICitizenHappinessHandler h = data.getCitizenHappinessHandler();
            double total = h.getHappiness(colony, data);
            StringBuilder bad = new StringBuilder();
            StringBuilder good = new StringBuilder();
            for (String id : h.getModifiers()) {
                try {
                    IHappinessModifier m = h.getModifier(id);
                    if (m == null) continue;
                    double factor = m.getFactor(data);
                    if (factor < 0.99) {
                        if (bad.length() > 0) bad.append(", ");
                        bad.append(id).append(" (").append(Math.round(factor * 100)).append("% of normal)");
                    } else if (factor > 1.01) {
                        if (good.length() > 0) good.append(", ");
                        good.append(id);
                    }
                } catch (Throwable ignored) {
                }
            }
            StringBuilder sb = new StringBuilder("Your happiness is ").append(String.format("%.1f", total))
                    .append("/10. ");
            if (bad.length() > 0) {
                sb.append("What drags you down: ").append(bad).append(". ");
            } else {
                sb.append("Nothing is really bothering you. ");
            }
            if (good.length() > 0) {
                sb.append("What lifts you up: ").append(good).append(".");
            }
            try {
                for (me.lovkar.errands.BedCheck.Problem bp : me.lovkar.errands.BedCheck.scan(colony)) {
                    if (bp.citizen.equals(data.getName())) {
                        sb.append(" AND you cannot even get into a bed: ").append(bp.reason)
                                .append(" - ").append(bp.fix);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            ColonistErrands.LOGGER.info("[Report] why_unhappy for {}: {}", data.getName(), sb);
            result.addProperty("success", true);
            result.addProperty("info", sb.toString());
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("why_unhappy failed", t);
            result.addProperty("success", false);
            result.addProperty("error", "Could not read your happiness right now.");
        }
        return result;
    }
}
