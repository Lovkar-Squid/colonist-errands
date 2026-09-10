package me.lovkar.errands.tc;

import com.google.gson.JsonObject;
import me.sshcrack.mc_talking.api.tool.AiQueryTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import me.sshcrack.mc_talking.api.tool.AiToolParameter;

/**
 * An Errands question: a report the citizen reads out (who is where, what is in stock, how the
 * build is going). Read-only, answered at once on the server thread.
 */
public abstract class ErrandQuery extends ErrandTool implements AiQueryTool {

    protected ErrandQuery(final String name, final String description, final AiToolParameter parameters, final String group) {
        super(name, description, parameters, group);
    }

    protected ErrandQuery(final String name, final String description, final String group) {
        super(name, description, group);
    }

    @Override
    public final JsonObject executeQuery(final AiToolContext context, final JsonObject parameters) {
        return runGuarded(context, parameters);
    }
}
