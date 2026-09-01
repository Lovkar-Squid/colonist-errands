package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.Texts;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class DefendHereAction extends PlayerFunctionAction {

    public DefendHereAction() {
        super("defend_here",
                "DEFENSIVE FORMATION: every guard tower/barracks tower switches to Guard mode with a post along a "
                        + "defensive line, and guards MARCH there and hold it (they may need a couple of minutes to arrive). "
                        + "direction='here' (default): the line goes through the player's current position, facing away "
                        + "from the colony center. Cardinal AND diagonal borders are single directions: 'south-west border' "
                        + "is ONE call with direction='southwest' (never two calls). direction='raid': the line forms "
                        + "facing the direction the CURRENT raid is coming from - use when the player says 'toward the "
                        + "raid' / 'where the raid is coming from'. Ends with the dismiss tool ('stand down') or "
                        + "automatically after 30 minutes.",
                (Property) new ObjectProperty(new HashMap<String, Property>() {{
                    put("direction", new EnumProperty(List.of("here", "north", "south", "east", "west",
                            "northeast", "northwest", "southeast", "southwest", "raid"), false));
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
        if (ErrandManager.hasActiveDefense()) {
            ErrandManager.standDownDefense(colony.getID());
        }

        String direction = "here";
        try {
            if (parameters != null && parameters.has("direction")) {
                direction = parameters.get("direction").getAsString().trim().toLowerCase();
            }
        } catch (Throwable ignored) {
        }

        List<AbstractBuildingGuards> towers = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            BlockPos p = b.getPosition();
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            if (b instanceof AbstractBuildingGuards g) {
                towers.add(g);
            }
        }
        if (towers.isEmpty()) {
            result.addProperty("success", false);
            result.addProperty("error", "This colony has no guard towers.");
            return result;
        }

        int margin = 8;
        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;

        // Outward unit vector for the chosen direction (null = 'here' mode).
        double[] out = switch (direction) {
            case "north" -> new double[]{0, -1};
            case "south" -> new double[]{0, 1};
            case "east" -> new double[]{1, 0};
            case "west" -> new double[]{-1, 0};
            case "northeast" -> new double[]{0.7071, -0.7071};
            case "northwest" -> new double[]{-0.7071, -0.7071};
            case "southeast" -> new double[]{0.7071, 0.7071};
            case "southwest" -> new double[]{-0.7071, 0.7071};
            case "raid" -> me.lovkar.errands.RaidWatcher.raidVector(colony);
            default -> null;
        };
        if (direction.equals("raid") && out == null) {
            result.addProperty("success", false);
            result.addProperty("error", "There is no active raid right now, so there is no raid direction to face. "
                    + "Ask the player for a border direction instead.");
            return result;
        }

        BlockPos anchor;
        double px, pz; // unit vector ALONG the defensive line
        String where;
        if (out != null) {
            int ax = out[0] > 0.3 ? maxX + margin : out[0] < -0.3 ? minX - margin : cx;
            int az = out[1] > 0.3 ? maxZ + margin : out[1] < -0.3 ? minZ - margin : cz;
            anchor = new BlockPos(ax, player.blockPosition().getY(), az);
            px = -out[1];
            pz = out[0];
            where = direction.equals("raid")
                    ? "toward the raid (coming from the " + me.lovkar.errands.RaidWatcher.raidDirName(colony) + ")"
                    : "the " + me.lovkar.errands.RaidWatcher.dirName8(out[0], out[1]) + " border";
        } else {
            anchor = player.blockPosition();
            BlockPos center = colony.getCenter();
            double dx = anchor.getX() - center.getX();
            double dz = anchor.getZ() - center.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0) {
                dx = 0;
                dz = -1;
                len = 1;
            }
            px = -dz / len;
            pz = dx / len;
            where = "the player's position";
        }
        int spacing = 4;

        int placed = 0;
        int skippedWet = 0;
        for (AbstractBuildingGuards tower : towers) {
            try {
                // offsets: 0, +1, -1, +2, -2, ...
                int k = (placed + 1) / 2 * ((placed % 2 == 0) ? 1 : -1);
                int postX = anchor.getX() + (int) Math.round(px * spacing * k);
                int postZ = anchor.getZ() + (int) Math.round(pz * spacing * k);
                // Never post a guard in water - Lovkar watched them drown walking
                // to a line that crossed the sea during a pirate raid.
                BlockPos post = ErrandManager.safePost(citizen.level(), postX, postZ);
                if (post == null) {
                    skippedWet++;
                    continue;
                }
                GuardTaskSetting s = tower.getSetting(AbstractBuildingGuards.GUARD_TASK);
                if (s == null) continue;
                String prev = me.lovkar.errands.GuardSettings.value(s, GuardTaskSetting.PATROL);
                if (!me.lovkar.errands.GuardSettings.set(s, GuardTaskSetting.GUARD)) continue;
                tower.setGuardPos(post);
                ErrandManager.rallyTo(tower, post, citizen.level());
                try {
                    tower.markDirty();
                } catch (Throwable ignored) {
                }
                ErrandManager.kickGuardAI(tower); // march NOW instead of on the next lazy AI cycle
                ErrandManager.registerDefense(tower, prev);
                placed++;
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("defend_here failed for a tower", t);
            }
        }
        ColonistErrands.LOGGER.info("[Defense] Defensive line at {} ({}) - {} tower(s) repositioned",
                anchor.toShortString(), where, placed);
        result.addProperty("success", placed > 0);
        result.addProperty("towers", placed);
        String wetNote = skippedWet > 0
                ? " " + skippedWet + " tower(s) were left on their normal task because their stretch of the line "
                + "falls on WATER - say so, guards drown out there."
                : "";
        result.addProperty("info", placed == 0
                ? ("Could not switch any guard tower to the defensive line."
                + (skippedWet > 0 ? " Every post on that line lands in water - pick a spot further inland." : ""))
                : placed + " guard tower(s) switched to Guard mode along the defensive line at " + where + " ("
                        + anchor.toShortString() + "). Guards are MARCHING to their posts - give them a couple of "
                        + "minutes - and will hold the line until the player says stand down / dismiss. "
                        + wetNote
                        + " Confirm the order briefly like a soldier and tell them where the line is forming." + Texts.GOODBYE);
        return result;
    }
}
