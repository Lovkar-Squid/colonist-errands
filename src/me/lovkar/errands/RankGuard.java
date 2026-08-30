package me.lovkar.errands;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.permissions.ColonyPlayer;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.google.gson.JsonObject;
import me.sshcrack.mc_talking.ConversationManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Lovkar's idea #30: WHICH RANK MAY ORDER WHAT - configurable. Command tools are
 * grouped (chat / errands / military / jobs) and each group has a minimum
 * MineColonies colony rank, editable in
 * config/colonist_errands_permissions.properties (created with defaults +
 * comments on first run). Per-tool overrides: "tool.take_job=owner".
 * The colony OWNER is always allowed everything; HOSTILE players nothing.
 */
public final class RankGuard {

    private RankGuard() {
    }

    public static final String GROUP_CHAT = "chat";
    public static final String GROUP_ERRANDS = "errands";
    public static final String GROUP_MILITARY = "military";
    public static final String GROUP_JOBS = "jobs";

    // tiers: 4 owner, 3 officer, 2 friend, 1 neutral, 0 hostile/nobody
    private static final Path FILE = Path.of("config", "colonist_errands_permissions.properties");
    private static final Properties PROPS = new Properties();
    private static volatile boolean loaded = false;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                try (InputStream in = Files.newInputStream(FILE)) {
                    PROPS.load(in);
                }
                ColonistErrands.LOGGER.info("[RankGuard] Loaded permission config ({} entries)", PROPS.size());
            } else {
                PROPS.setProperty(GROUP_CHAT, "neutral");
                PROPS.setProperty(GROUP_ERRANDS, "friend");
                PROPS.setProperty(GROUP_MILITARY, "officer");
                PROPS.setProperty(GROUP_JOBS, "officer");
                Files.createDirectories(FILE.getParent());
                try (OutputStream out = Files.newOutputStream(FILE)) {
                    PROPS.store(out, """
                            Colonist Errands - which MineColonies colony rank may voice-command what.
                            Values: owner | officer | friend | neutral | nobody  (minimum rank; owner always may everything, hostile never anything)
                            Groups: chat = questions, reports, promises | errands = fetch/send/call/deliver/messenger | military = defense, alerts, patrols, guards | jobs = job assignments
                            Per-tool override example:  tool.take_job=owner
                            Edit while the game is CLOSED; the file is read once at startup.""");
                }
                ColonistErrands.LOGGER.info("[RankGuard] Wrote default permission config");
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[RankGuard] config failed - using defaults", t);
        }
    }

    private static int wordToTier(String word, int fallback) {
        if (word == null) return fallback;
        return switch (word.trim().toLowerCase()) {
            case "owner" -> 4;
            case "officer" -> 3;
            case "friend" -> 2;
            case "neutral", "anyone", "all" -> 1;
            case "nobody", "none" -> 99;
            default -> fallback;
        };
    }

    private static int defaultTier(String group) {
        return switch (group) {
            case GROUP_MILITARY, GROUP_JOBS -> 3;
            case GROUP_ERRANDS -> 2;
            default -> 1;
        };
    }

    /** Minimum tier required for this tool (per-tool override first, then its group). */
    public static int requiredTier(String group, String toolName) {
        load();
        String override = toolName == null ? null : PROPS.getProperty("tool." + toolName);
        if (override != null) {
            return wordToTier(override, defaultTier(group));
        }
        return wordToTier(PROPS.getProperty(group), defaultTier(group));
    }

    /** This player's tier in the colony: 4 owner, 3 officer/manager, 2 friend, 1 neutral/unknown, 0 hostile. */
    public static int tierOf(IColony colony, UUID player) {
        try {
            IPermissions perms = colony.getPermissions();
            if (player.equals(perms.getOwner())) {
                return 4;
            }
            ColonyPlayer cp = perms.getPlayers().get(player);
            Rank rank = cp == null ? null : cp.getRank();
            if (rank == null) {
                return 1;
            }
            if (rank.isHostile()) return 0;
            if (rank.getId() == IPermissions.OWNER_RANK_ID) return 4;
            if (rank.isColonyManager() || rank.getId() == IPermissions.OFFICER_RANK_ID) return 3;
            if (rank.getId() == IPermissions.FRIEND_RANK_ID) return 2;
            return 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    private static String tierWord(int tier) {
        return switch (tier) {
            case 4 -> "Owner";
            case 3 -> "an Officer";
            case 2 -> "a Friend of the colony";
            default -> "anyone";
        };
    }

    /**
     * Gate for a command tool. @return null when allowed, else a polite refusal
     * JsonObject the model reads back to the player.
     */
    public static JsonObject check(AbstractEntityCitizen citizen, IColony colony, String group, String toolName) {
        try {
            UUID playerId = ConversationManager.getPlayerForEntity(citizen.getUUID());
            if (playerId == null || colony == null) {
                return null; // not a player conversation - nothing to gate
            }
            int tier = tierOf(colony, playerId);
            int needed = requiredTier(group, toolName);
            if (tier >= needed && tier > 0) {
                return null;
            }
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            String who;
            try {
                who = AliasStore.display(citizen.getServer().getPlayerList().getPlayer(playerId).getGameProfile().getName());
            } catch (Throwable t) {
                who = "This player";
            }
            if (tier <= 0) {
                result.addProperty("error", who + " is HOSTILE to this colony - refuse firmly (but stay in "
                        + "character, no insults). You take no orders from enemies of the colony.");
            } else {
                result.addProperty("error", who + "'s colony rank does not allow this: " + group
                        + " commands require " + (needed >= 99 ? "the Owner's direct blessing (disabled in settings)"
                        : tierWord(needed) + " or higher")
                        + ". Politely decline in one sentence and suggest they ask the colony Owner or an Officer. "
                        + "Stay friendly - rules are rules, nothing personal.");
            }
            ColonistErrands.LOGGER.info("[RankGuard] {} denied '{}' for player tier {} (needs {})",
                    citizen.getCitizenData() != null ? citizen.getCitizenData().getName() : "citizen",
                    toolName, tier, needed);
            return result;
        } catch (Throwable t) {
            return null; // never break tools over the gate
        }
    }

    /** One prompt line telling the citizen what the current speaker's tier may command. */
    public static String promptSummary(int tier) {
        load();
        StringBuilder may = new StringBuilder();
        StringBuilder not = new StringBuilder();
        String[][] groups = {
                {GROUP_CHAT, "questions, reports & promises"},
                {GROUP_ERRANDS, "everyday errands (fetch, send, call, deliver)"},
                {GROUP_MILITARY, "military orders (defense, alerts, patrols, guards)"},
                {GROUP_JOBS, "job assignments"}};
        for (String[] g : groups) {
            boolean ok = tier > 0 && tier >= requiredTier(g[0], null);
            StringBuilder target = ok ? may : not;
            if (target.length() > 0) {
                target.append("; ");
            }
            target.append(g[1]);
        }
        StringBuilder sb = new StringBuilder();
        if (may.length() > 0) {
            sb.append("\n- Commands this person's rank MAY give you: ").append(may).append(".");
        }
        if (not.length() > 0) {
            sb.append("\n- NOT allowed from their rank: ").append(not)
                    .append(" - politely decline those and point them to the Owner or an Officer.");
        }
        return sb.toString();
    }
}
