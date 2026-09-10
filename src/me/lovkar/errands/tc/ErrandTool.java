package me.lovkar.errands.tc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.RankGuard;
import me.sshcrack.mc_talking.api.tool.AiTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import me.sshcrack.mc_talking.api.tool.AiToolParameter;
import me.sshcrack.mc_talking.api.tool.AiToolPermission;
import me.sshcrack.mc_talking.api.tool.AiToolScope;
import net.minecraft.server.level.ServerPlayer;

/**
 * What every Errands tool has in common, on the Talking Colonists 2.0 tool contract.
 *
 * <p>Errands 2.x tools were mc_talking {@code PlayerFunctionAction}s pushed into a private map by
 * reflection; 2.0 has {@code AiToolRegistry.register(namespace, name, tool)} and two contracts,
 * a query (read-only, answered at once on the server thread) and a command (may change the world,
 * answers with a completion stage). This base keeps the 2.x shape the tools were written in -
 * {@code run(citizen, colony, parameters, player)} returning a {@code success}/{@code info} or
 * {@code success}/{@code error} object the model reads back to the player - and does the
 * plumbing: the rank gate (Lovkar's idea #30, which rank may order what), the player-only scope
 * every Errands tool has always had, and a catch-all so a broken tool answers with an error
 * instead of killing the conversation.</p>
 *
 * <p>The core derives the function name the model sees from the addon id; {@link ToolNames}
 * knows it, so descriptions can point at other tools by the name the model will actually see.</p>
 */
public abstract class ErrandTool implements AiTool {

    private final String name;
    private final String description;
    private final AiToolParameter parameters;
    private final String group;

    protected ErrandTool(final String name, final String description, final AiToolParameter parameters, final String group) {
        this.name = name;
        this.description = description;
        this.parameters = parameters == null ? AiToolParameter.object(Map.of()) : parameters;
        this.group = group;
    }

    protected ErrandTool(final String name, final String description, final String group) {
        this(name, description, null, group);
    }

    /** The tool's own name (the part after the namespace), e.g. {@code come_here}. */
    public final String name() {
        return name;
    }

    /**
     * The description, with every {@code {tool_name}} placeholder replaced by the name the model
     * sees for that Errands tool - resolved on each call, so it is right even though the tools
     * are constructed before the core has told us its naming scheme.
     */
    @Override
    public final String description() {
        return ToolNames.resolve(description);
    }

    @Override
    public final AiToolParameter parameters() {
        return parameters;
    }

    /** Every Errands tool is an order or a question from the player; nothing here runs for NPC sessions. */
    @Override
    public AiToolScope scope() {
        return AiToolScope.PLAYER_CONVERSATION;
    }

    /** Errands has its own rank gate (per group, configurable), so the core's colony-permission check is skipped. */
    @Override
    public AiToolPermission permission() {
        return AiToolPermission.NONE;
    }

    /**
     * The work. Called on the server thread with the authenticated player of the conversation
     * (never null: the scope guarantees one).
     */
    protected abstract JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player);

    /** Rank gate, then the tool; a throwing tool answers with an error the model can read. */
    protected final JsonObject runGuarded(final AiToolContext context, final JsonObject parameters) {
        final AbstractEntityCitizen citizen = context.citizen();
        final IColony colony = context.colony();
        final ServerPlayer player = context.player();
        try {
            if (group != null) {
                final JsonObject denied = RankGuard.check(citizen, colony, group, name, player);
                if (denied != null) {
                    return denied;
                }
            }
            final JsonObject result = run(citizen, colony, parameters == null ? new JsonObject() : parameters, player);
            return result == null ? ok("Done.") : result;
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.warn("[Errands] tool {} failed", name, t);
            return error("Something went wrong while doing that (" + t.getClass().getSimpleName() + "). Apologise briefly.");
        }
    }

    // ------------------------------------------------------------------ helpers for the tools

    public static JsonObject ok(final String info) {
        final JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("info", info);
        return result;
    }

    public static JsonObject error(final String message) {
        final JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", message);
        return result;
    }

    /** A string parameter, trimmed; null when absent or blank. */
    protected static String str(final JsonObject parameters, final String key) {
        try {
            if (parameters == null || !parameters.has(key) || parameters.get(key).isJsonNull()) {
                return null;
            }
            final String value = parameters.get(key).getAsString().trim();
            return value.isEmpty() ? null : value;
        } catch (final Throwable t) {
            return null;
        }
    }

    protected static int integer(final JsonObject parameters, final String key, final int fallback) {
        try {
            if (parameters == null || !parameters.has(key) || parameters.get(key).isJsonNull()) {
                return fallback;
            }
            return parameters.get(key).getAsInt();
        } catch (final Throwable t) {
            return fallback;
        }
    }

    protected static boolean bool(final JsonObject parameters, final String key, final boolean fallback) {
        try {
            if (parameters == null || !parameters.has(key) || parameters.get(key).isJsonNull()) {
                return fallback;
            }
            return parameters.get(key).getAsBoolean();
        } catch (final Throwable t) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ parameter schema shorthand

    /** {@code params("target", enumOf(TARGETS, true), "building_name", string(false))}. */
    protected static AiToolParameter params(final Object... keyValues) {
        final Map<String, AiToolParameter> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], (AiToolParameter) keyValues[i + 1]);
        }
        return AiToolParameter.object(map);
    }

    protected static AiToolParameter string(final boolean required) {
        return AiToolParameter.string(required);
    }

    protected static AiToolParameter integer(final boolean required) {
        return AiToolParameter.integer(required);
    }

    protected static AiToolParameter number(final boolean required) {
        return AiToolParameter.number(required);
    }

    protected static AiToolParameter bool(final boolean required) {
        return AiToolParameter.bool(required);
    }

    protected static AiToolParameter enumOf(final List<String> values, final boolean required) {
        return AiToolParameter.enumeration(values, required);
    }

}
