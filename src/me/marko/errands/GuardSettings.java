package me.marko.errands;

import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;

import java.util.List;

/**
 * Safe access to guard StringSettings (GUARD_TASK, FOLLOW_MODE). Found via
 * Marko's raid log: StringSetting.set(value) does currentIndex=indexOf(value),
 * so setting an option a RESTRICTED tower doesn't offer stores -1 and
 * PERMANENTLY breaks the setting - every later getValue() (ours AND
 * MineColonies' own GUI/AI) throws IndexOutOfBounds. These helpers never set
 * unsupported values and heal any setting already broken that way.
 */
public final class GuardSettings {

    private GuardSettings() {
    }

    /** Heal a setting whose currentIndex is out of range (broken by an unsupported set). */
    public static void repair(StringSetting s) {
        try {
            List<String> opts = s.getSettings();
            int i = s.getCurrentIndex();
            if (opts != null && !opts.isEmpty() && (i < 0 || i >= opts.size())) {
                s.set(opts.contains(GuardTaskSetting.PATROL) ? GuardTaskSetting.PATROL : opts.get(0));
                ColonistErrands.LOGGER.info("[GuardFix] Repaired a broken guard setting (index was out of range)");
            }
        } catch (Throwable ignored) {
        }
    }

    /** Safe read - never throws; repairs a broken index on the way. */
    public static String value(StringSetting s, String fallback) {
        try {
            repair(s);
            return s.getValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Safe write - only when THIS tower actually offers the option. @return success. */
    public static boolean set(StringSetting s, String value) {
        try {
            if (value == null) {
                return false;
            }
            List<String> opts = s.getSettings();
            if (opts == null || !opts.contains(value)) {
                return false;
            }
            s.set(value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
