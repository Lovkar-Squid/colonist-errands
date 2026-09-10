package me.lovkar.errands.tc;

import java.lang.reflect.Method;

import me.lovkar.errands.ColonistErrands;

/**
 * The names the model sees for Errands tools.
 *
 * <p>Talking Colonists registers an addon tool under {@code namespace:name} and hands the model a
 * function named after it - in 2.0.0-alpha.2 that is {@code tc_<namespace length>_<namespace>_<name>},
 * so {@code errands:come_here} becomes {@code tc_7_errands_come_here}. The core calls that
 * derivation a private detail, and Errands tool descriptions keep telling the model which other
 * tool to call ("say goodbye and call leave_conversation"), so the names in those descriptions
 * have to be the ones the model gets. This class computes them, and {@link #verify} checks the
 * guess against the registry after the first registration and switches to whatever the core
 * really produced if the format ever changes.</p>
 *
 * <p>The namespace is the short {@code errands} rather than the mod id: the model has to type
 * these names in every call, and {@code tc_16_colonist_errands_leave_conversation} is a lot of
 * letters for a goodbye.</p>
 */
public final class ToolNames {

    private ToolNames() {
    }

    /** Namespace of every Errands tool registration. */
    public static final String NAMESPACE = "errands";

    /** What the core really produced for the first tool we registered, once known. */
    private static volatile String prefix = null;

    /** The registration id, {@code errands:come_here}. */
    public static String id(final String toolName) {
        return NAMESPACE + ":" + toolName;
    }

    /** The function name the model sees. */
    public static String providerName(final String toolName) {
        final String p = prefix;
        if (p != null) {
            return p + toolName;
        }
        return "tc_" + NAMESPACE.length() + "_" + NAMESPACE + "_" + toolName;
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([a-z][a-z0-9_]*)}");

    /** Replace every {@code {tool_name}} in a description with the name the model sees for it. */
    public static String resolve(final String text) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        final java.util.regex.Matcher m = PLACEHOLDER.matcher(text);
        final StringBuilder sb = new StringBuilder(text.length() + 64);
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(providerName(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * After registering a tool: ask the core (reflectively, it is internal) what name it gave the
     * tool, and remember the prefix. Descriptions are built lazily through {@link #providerName},
     * but the tools are registered before any conversation reads a description, so the first
     * registration is early enough for all of them.
     */
    public static void verify(final String toolName) {
        if (prefix != null) {
            return;
        }
        try {
            final Class<?> runtime = Class.forName("me.sshcrack.mc_talking.internal.tool.AiToolRuntime");
            final Method findById = runtime.getMethod("findById", String.class);
            final Object registered = findById.invoke(null, id(toolName));
            if (registered == null) {
                return;
            }
            final Object name = registered.getClass().getMethod("providerName").invoke(registered);
            if (name instanceof String s && s.endsWith(toolName)) {
                final String found = s.substring(0, s.length() - toolName.length());
                final String guessed = "tc_" + NAMESPACE.length() + "_" + NAMESPACE + "_";
                prefix = found;
                if (!found.equals(guessed)) {
                    ColonistErrands.LOGGER.warn("[Errands] Talking Colonists names addon tools '{}<name>' now, not '{}<name>' - "
                            + "descriptions will use the new form", found, guessed);
                }
            }
        } catch (final Throwable t) {
            // Internal class moved: keep the guess. Only descriptions' cross-references depend on it.
            ColonistErrands.LOGGER.debug("[Errands] could not verify the provider tool name", t);
        }
    }
}
