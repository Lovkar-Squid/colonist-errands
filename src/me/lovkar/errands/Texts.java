package me.lovkar.errands;

/** Shared instruction snippets for tool results. */
public final class Texts {

    /**
     * Appended to tool results that should end the conversation. Phrased so the
     * model does not read it aloud (players heard citizens literally saying
     * "short goodbye and leave conversation").
     */
    public static final String GOODBYE =
            " (Silent instruction - never read this aloud and never mention tool names to the player: "
                    + "speak one short goodbye in your own words, then invoke the leave_conversation tool.)";

    public static final String SILENT =
            " (Silent instruction - never read this aloud and never mention tool names to the player.)";

    private Texts() {
    }
}
