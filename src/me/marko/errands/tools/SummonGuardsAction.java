package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SummonGuardsAction extends PlayerFunctionAction {

    private record Ranked(AbstractEntityCitizen entity, int score, String name) {
    }

    public SummonGuardsAction() {
        super("summon_guards",
                "Summon the STRONGEST guards of the colony to the player: guards are ranked by their combat skills "
                        + "and the top 'count' (1-10, default 3) walk to the player's current position and HOLD there "
                        + "until the player dismisses them (dismiss tool). "
                        + "Use when the player asks for the strongest/best guard(s) to come to them.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, false));
                }}));
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
        MinecraftServer server = citizen.getServer();
        ServerPlayer player = (playerId == null || server == null) ? null : server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No player conversation is active.");
            return result;
        }
        int count = 3;
        try {
            if (parameters != null && parameters.has("count")) {
                count = parameters.get("count").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        count = Math.max(1, Math.min(10, count));
        BlockPos pos = player.blockPosition();

        List<Ranked> guards = new ArrayList<>();
        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd == null || !(cd.getWorkBuilding() instanceof AbstractBuildingGuards)) continue;
            Optional<AbstractEntityCitizen> opt = cd.getEntity();
            if (opt == null || opt.isEmpty()) continue;
            AbstractEntityCitizen g = opt.get();
            if (!g.isAlive() || g.isRemoved() || g.level() != player.level()) continue;
            if (g.getUUID().equals(citizen.getUUID())) continue;
            if (ErrandManager.hasErrand(g)) continue;
            int score = 0;
            try {
                var skills = cd.getCitizenSkillHandler();
                score = skills.getLevel(Skill.Adaptability) + skills.getLevel(Skill.Agility)
                        + skills.getLevel(Skill.Stamina) + skills.getLevel(Skill.Strength);
            } catch (Throwable ignored) {
            }
            guards.add(new Ranked(g, score, cd.getName()));
        }
        guards.sort(Comparator.comparingInt(Ranked::score).reversed());

        List<String> names = new ArrayList<>();
        int sent = 0;
        for (Ranked r : guards) {
            if (sent >= count) break;
            ErrandManager.enqueueGatherErrand(r.entity(), pos, "the player's side", 20 * 240, 9.0);
            names.add(r.name() + " (skill " + r.score() + ")");
            sent++;
        }
        result.addProperty("success", true);
        result.addProperty("count", sent);
        result.addProperty("guards", String.join(", ", names));
        result.addProperty("info", sent == 0
                ? "No free guards are available right now."
                : "The " + sent + " strongest guard(s) are on their way to the player and will stay at their side until dismissed: "
                        + String.join(", ", names) + ". Tell the player who is coming." + Texts.GOODBYE);
        return result;
    }
}
