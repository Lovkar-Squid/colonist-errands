package me.lovkar.errands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Objects;

/**
 * {@code /errands} - the addon's server commands. Operators only.
 *
 * <p>{@code /errands reloadtalking} re-reads Talking Colonists' config
 * ({@code config/yacl-mc_talking.json5}) from disk without a restart. Talking Colonists reads that
 * file once, when the mod loads, and every Gemini connection takes the API key out of the loaded
 * config at the moment it is opened - so a key changed on disk only reaches the game after a
 * restart, which on a server means throwing everyone out. This command does the re-read; every
 * conversation started after it uses the new key, conversations already running keep the
 * connection they have. The key itself is never printed, only whether one is set.</p>
 */
public final class ErrandsCommands {

    private ErrandsCommands() {
    }

    public static void register(final RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("errands")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reloadtalking").executes(ErrandsCommands::reloadTalking)));
    }

    /**
     * Talking Colonists' config class is not part of its addon API, so it is reached by reflection
     * here - the one place Errands still touches the mod's internals, and it fails soft: if a
     * future version moves or renames it, the command reports that and nothing else breaks.
     */
    private static final class TalkingConfig {
        final Object handler;   // the YACL ConfigClassHandler
        final Class<?> type;    // McTalkingConfig

        TalkingConfig() throws ReflectiveOperationException {
            type = Class.forName("me.sshcrack.mc_talking.config.McTalkingConfig");
            handler = type.getField("INSTANCE").get(null);
        }

        Object instance() throws ReflectiveOperationException {
            return handler.getClass().getMethod("instance").invoke(handler);
        }

        boolean load() throws ReflectiveOperationException {
            return (Boolean) handler.getClass().getMethod("load").invoke(handler);
        }

        String apiKey() throws ReflectiveOperationException {
            return (String) type.getField("geminiApiKey").get(instance());
        }

        boolean hasApiKey() throws ReflectiveOperationException {
            return (Boolean) type.getMethod("hasGeminiApiKey").invoke(null);
        }

        String model() throws ReflectiveOperationException {
            return String.valueOf(type.getField("currentAiModel").get(instance()));
        }
    }

    private static int reloadTalking(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String oldKey;
        final boolean loaded;
        final TalkingConfig config;
        try {
            config = new TalkingConfig();
            oldKey = config.apiKey();
            loaded = config.load();
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.error("[ColonistErrands] /errands reloadtalking failed", t);
            source.sendFailure(Component.literal("[Colonist Errands] Could not reload the Talking Colonists config: "
                    + t.getClass().getSimpleName() + " - see the server log."));
            return 0;
        }
        if (!loaded) {
            source.sendFailure(Component.literal("[Colonist Errands] config/yacl-mc_talking.json5 could not be parsed - "
                    + "check the quotes and commas, see the server log. The previous settings stay in effect."));
            return 0;
        }
        final String newKey;
        final boolean hasKey;
        final String model;
        try {
            newKey = config.apiKey();
            hasKey = config.hasApiKey();
            model = config.model();
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.error("[ColonistErrands] /errands reloadtalking: config reloaded but could not be read back", t);
            source.sendSuccess(() -> Component.literal("[Colonist Errands] Talking Colonists config reloaded."), true);
            return 1;
        }
        final boolean changed = !Objects.equals(oldKey == null ? "" : oldKey.trim(), newKey == null ? "" : newKey.trim());
        final String line;
        if (!hasKey) {
            line = "[Colonist Errands] Talking Colonists config reloaded - but no Gemini API key is set in "
                    + "config/yacl-mc_talking.json5, so the colonists will not talk until one is.";
        } else if (changed) {
            line = "[Colonist Errands] Talking Colonists config reloaded - Gemini API key updated (model " + model
                    + "). Conversations started from now on use the new key; ones already running keep their connection.";
        } else {
            line = "[Colonist Errands] Talking Colonists config reloaded - Gemini API key unchanged, still set (model "
                    + model + ").";
        }
        ColonistErrands.LOGGER.info("[ColonistErrands] {} reloaded the Talking Colonists config: key {}, model {}",
                source.getTextName(), !hasKey ? "missing" : changed ? "updated" : "unchanged", model);
        source.sendSuccess(() -> Component.literal(line), true);
        return 1;
    }
}
