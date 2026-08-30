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

    private static synchronized int intValue(String key, int def, int min, int max) {
        try {
            load();
            int v = Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
            return Math.max(min, Math.min(max, v));
        } catch (Throwable t) {
            return def;
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
                        """, StandardCharsets.UTF_8);
            }
            try (var in = Files.newInputStream(path)) {
                props.load(in);
            }
            ColonistErrands.LOGGER.info("[Settings] Loaded (max_taverns={})", props.getProperty("max_taverns", "1"));
        } catch (IOException | RuntimeException e) {
            ColonistErrands.LOGGER.warn("[Settings] Could not load settings, using defaults", e);
        }
    }
}
