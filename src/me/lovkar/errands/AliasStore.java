package me.lovkar.errands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Persistent "call me X" nicknames: Minecraft account name -> preferred name.
 * Stored in config/colonist_errands_aliases.properties so it survives restarts
 * and applies to every citizen's prompt (via CitizenPromptServiceMixin).
 */
public final class AliasStore {

    private static final Path FILE = Path.of("config", "colonist_errands_aliases.properties");
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static volatile boolean loaded = false;

    private AliasStore() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(FILE)) {
                    props.load(in);
                }
                for (String key : props.stringPropertyNames()) {
                    ALIASES.put(key, props.getProperty(key));
                }
                ColonistErrands.LOGGER.info("[ColonistErrands] Loaded {} player alias(es)", ALIASES.size());
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("Failed to load aliases", t);
        }
    }

    public static synchronized void set(String accountName, String alias) {
        load();
        ALIASES.put(accountName, alias);
        try {
            Files.createDirectories(FILE.getParent());
            Properties props = new Properties();
            ALIASES.forEach(props::setProperty);
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props.store(out, "Colonist Errands - player nicknames (account=preferred name)");
            }
        } catch (IOException | RuntimeException t) {
            ColonistErrands.LOGGER.warn("Failed to save aliases", t);
        }
    }

    /** Preferred display name of an account: the alias if one is set, else the account name. */
    public static synchronized String display(String accountName) {
        load();
        if (accountName == null || accountName.isBlank()) {
            return "the player";
        }
        String alias = ALIASES.get(accountName);
        return alias == null || alias.isBlank() ? accountName : alias;
    }

    /** Global rules + nickname preferences appended to every citizen prompt. */
    public static synchronized String promptBlock() {
        load();
        StringBuilder sb = new StringBuilder("\n\nIMPORTANT conversation rules:")
                .append("\n- NEVER say the same sentence twice. If the connection was re-established or your context was ")
                .append("restored mid-conversation, do NOT repeat anything you already said (no repeated greetings, ")
                .append("answers or goodbyes) - continue naturally from where you left off, or stay silent and listen.")
                .append("\n- One goodbye total: after you call leave_conversation, say NOTHING more - never repeat ")
                .append("or rephrase a goodbye you already spoke.");
        if (!ALIASES.isEmpty()) {
            sb.append("\nPlayer name preferences:");
            ALIASES.forEach((account, alias) -> sb.append("\n- Always address the player '").append(account)
                    .append("' as '").append(alias).append("' (never use their account name)."));
        }
        return sb.toString();
    }
}
