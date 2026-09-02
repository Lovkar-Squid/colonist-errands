package me.lovkar.errands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * General addon settings: config/colonist_errands_settings.properties.
 * Created with commented defaults on first launch; read once, lazily.
 */
public final class Settings {

    private Settings() {
    }

    private static Properties props;

    /** Max taverns per colony (Lovkar's request). 1 = vanilla MineColonies rule. */
    public static int maxTaverns() {
        return intValue("max_taverns", 1, 1, 10);
    }

    /** Three-way huddles (see {@link GroupChats}). Costs three conversations a round. */
    public static boolean groupChats() {
        return boolValue("group_chats", true);
    }

    private static synchronized boolean boolValue(String key, boolean def) {
        try {
            load();
            String v = props.getProperty(key, String.valueOf(def)).trim();
            return "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private static synchronized int intValue(String key, int def, int min, int max) {
        try {
            load();
            int v = Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
            return Math.max(min, Math.min(max, v));
        } catch (Throwable t) {
            return def;
        }
    }

    private static final String GROUP_CHATS_DOC = """
            # group_chats: let three colonists standing together hold a huddle - the
            #   conversation goes round the circle (A with B, B with C, C with A), so it
            #   plays as one group conversation. Gemini can only voice two speakers at a
            #   time, which is why it is a round rather than a genuine three-way.
            #   A round costs three conversations, so it happens at most once every
            #   quarter of an hour and only where a player is close enough to hear it.
            #   true (default) or false.
            group_chats=true""";

    /** Adds a key the user's existing file predates, so new settings are discoverable. */
    private static void ensureKey(Path path, String key, String documentedBlock) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.contains(key + "=")) {
                return;
            }
            Files.writeString(path, text.stripTrailing() + "\n\n" + documentedBlock + "\n", StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static void load() {
        if (props != null) {
            return;
        }
        props = new Properties();
        try {
            Path path = Path.of("config", "colonist_errands_settings.properties");
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, """
                        # Colonist Errands - general settings
                        #
                        # max_taverns: how many taverns one colony may build.
                        #   1 = the vanilla MineColonies rule (default).
                        #   2-10 = the addon lifts the limit to this number. Each tavern hosts its
                        #   own visitors; more visitors also means more marketplace customers if
                        #   you run MC Trade Post. Values above 1 are beyond what MineColonies
                        #   was balanced for - use at your own taste.
                        max_taverns=1

                        %s
                        """.formatted(GROUP_CHATS_DOC), StandardCharsets.UTF_8);
            }
            ensureKey(path, "group_chats", GROUP_CHATS_DOC);
            try (var in = Files.newInputStream(path)) {
                props.load(in);
            }
            ColonistErrands.LOGGER.info("[Settings] Loaded (max_taverns={}, group_chats={})",
                    props.getProperty("max_taverns", "1"), props.getProperty("group_chats", "true"));
        } catch (IOException | RuntimeException e) {
            ColonistErrands.LOGGER.warn("[Settings] Could not load settings, using defaults", e);
        }
    }
}
