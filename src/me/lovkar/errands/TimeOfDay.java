package me.lovkar.errands;

/** The clock, stated plainly, so nobody wishes anyone good morning at dusk. */
public final class TimeOfDay {

    private TimeOfDay() {
    }

    public static String promptLine(long gameTimeTicks) {
        try {
            long t = ((gameTimeTicks % 24000L) + 24000L) % 24000L;
            String when;
            if (t < 1000) when = "just after dawn";
            else if (t < 5000) when = "morning";
            else if (t < 7000) when = "around midday";
            else if (t < 11000) when = "afternoon";
            else if (t < 13000) when = "evening, the sun is going down";
            else if (t < 18000) when = "night";
            else if (t < 22000) when = "the small hours, deep night";
            else when = "just before dawn";
            return "\n\nTHE HOUR: it is " + when + " right now. Greet and talk to fit THIS hour - never wish "
                    + "someone good morning in the evening or good night at noon.";
        } catch (Throwable t) {
            return "";
        }
    }
}
