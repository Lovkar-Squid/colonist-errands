package me.lovkar.errands.tc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonObject;
import me.sshcrack.mc_talking.api.tool.AiCommandTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import me.sshcrack.mc_talking.api.tool.AiToolParameter;

/**
 * An Errands order: something that changes the colony (an errand starts, a guard is posted, a
 * job changes hands). The core calls {@link #executeCommand} on the server thread and wants a
 * completion stage back at once; Errands orders are all bookkeeping that finishes in the same
 * tick (the walking happens later, driven by the ErrandManager), so the stage is already done.
 */
public abstract class ErrandCommand extends ErrandTool implements AiCommandTool {

    protected ErrandCommand(final String name, final String description, final AiToolParameter parameters, final String group) {
        super(name, description, parameters, group);
    }

    protected ErrandCommand(final String name, final String description, final String group) {
        super(name, description, group);
    }

    @Override
    public final CompletionStage<JsonObject> executeCommand(final AiToolContext context, final JsonObject parameters) {
        return CompletableFuture.completedFuture(runGuarded(context, parameters));
    }
}
