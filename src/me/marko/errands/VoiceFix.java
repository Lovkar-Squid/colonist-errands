package me.marko.errands;

import me.sshcrack.mc_talking.config.AvailableAI;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-healing fix for Gemini close code 1007 "No matching speaker voice found
 * for name: X and language: Y": mc_talking's hardcoded voice lists contain
 * names the live API no longer accepts (Marko hit "Archid"), and since the
 * voice is picked deterministically from the citizen's UUID, that citizen can
 * NEVER talk - all 5 reconnect attempts pick the same broken voice.
 *
 * Here: every voice that ever caused that error goes on a persistent blocklist
 * (config/colonist_errands_blocked_voices.txt); the voice picker rerolls with
 * a salted UUID until it finds an allowed voice, still deterministic per
 * citizen. The very next reconnect attempt after a failure already succeeds.
 */
public final class VoiceFix {

    private static final Path FILE = Path.of("config", "colonist_errands_blocked_voices.txt");
    private static final Set<String> BLOCKED = ConcurrentHashMap.newKeySet();
    private static final Pattern REASON = Pattern.compile("No matching speaker voice found for name: (\\S+)");
    private static volatile boolean loaded = false;

    private VoiceFix() {
    }

    private static void load() {
        if (loaded) return;
        synchronized (VoiceFix.class) {
            if (loaded) return;
            loaded = true;
            try {
                if (Files.exists(FILE)) {
                    for (String line : Files.readAllLines(FILE)) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            BLOCKED.add(line);
                        }
                    }
                    ColonistErrands.LOGGER.info("[VoiceFix] {} blocked voice(s) loaded: {}", BLOCKED.size(), BLOCKED);
                }
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("[VoiceFix] load failed", t);
            }
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, ("# Voices Gemini rejected (auto-detected from close code 1007)\n"
                    + String.join("\n", BLOCKED) + "\n").getBytes());
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("[VoiceFix] save failed", t);
        }
    }

    /** Replacement for AvailableAI.getRandomVoice - rerolls past blocked voices. */
    public static String pickVoice(AvailableAI ai, UUID uuid, boolean female) {
        load();
        String voice = ai.getRandomVoice(uuid, female);
        if (!BLOCKED.contains(voice)) {
            return voice;
        }
        for (int salt = 1; salt <= 12; salt++) {
            UUID salted = new UUID(uuid.getMostSignificantBits() ^ (0x9E3779B97F4A7C15L * salt),
                    uuid.getLeastSignificantBits() + salt);
            String candidate = ai.getRandomVoice(salted, female);
            if (!BLOCKED.contains(candidate)) {
                ColonistErrands.LOGGER.info("[VoiceFix] Voice '{}' is blocked - using '{}' instead", voice, candidate);
                return candidate;
            }
        }
        String fallback = female ? "Kore" : "Puck";
        ColonistErrands.LOGGER.info("[VoiceFix] Voice '{}' blocked, rerolls exhausted - fallback '{}'", voice, fallback);
        return fallback;
    }

    /** Called from the onClose mixin - learns broken voices from Gemini's 1007 reason. */
    public static void noteCloseReason(int code, String reason) {
        if (code != 1007 || reason == null) {
            return;
        }
        try {
            Matcher m = REASON.matcher(reason);
            if (m.find()) {
                load();
                String voice = m.group(1);
                if (BLOCKED.add(voice)) {
                    save();
                    ColonistErrands.LOGGER.info("[VoiceFix] Gemini rejected voice '{}' - blocklisted; the citizen "
                            + "gets a different voice on the next attempt", voice);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
