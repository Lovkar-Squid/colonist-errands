package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * mc_talking's own end_conversation tool is hard-blocked for player
 * conversations (they normally only end when the player walks away). This tool
 * uses the graceful path (endConversationWhenPossible): the citizen finishes
 * speaking its goodbye, then the conversation fully closes - and any accepted
 * errand starts immediately.
 */
public class LeaveConversationAction extends PlayerFunctionAction {

    public LeaveConversationAction() {
        super("leave_conversation",
                "End the current conversation with the player. Call it in EXACTLY two cases and no other: "
                        + "(1) a goodbye exchange happened - the player said goodbye/bye/that's all/told you to go, OR gave a "
                        + "final order like 'dismissed'/'stand down'/'carry on' - as soon as you speak your ONE short goodbye "
                        + "or order confirmation you MUST call this immediately; do NOT wait for the player to also say bye; "
                        + "goodbyes must NEVER hang without the conversation actually ending; "
                        + "(2) you just accepted an errand with an errand tool and said your short goodbye. "
                        + "NEVER call it in any other situation: not after merely answering a question, not during a pause, "
                        + "not because a topic seems finished - the player may want to continue talking. "
                        + "If you are not sure the player is done, do NOT call this - keep listening. "
                        + "When one of the two cases applies, calling it is MANDATORY (for player conversations always this "
                        + "tool, never end_conversation, which fails). The conversation closes once you finish speaking.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        GeminiWsClient client = ConversationManager.getClientForEntity(citizen.getUUID());
        if (client == null) {
            result.addProperty("success", false);
            result.addProperty("error", "No active conversation found.");
            return result;
        }
        client.endConversationWhenPossible();
        me.marko.errands.AudioGate.onLeaveCalled(citizen.getUUID());
        result.addProperty("success", true);
        result.addProperty("info", "The conversation is now ending. Respond to this message with COMPLETE SILENCE: "
                + "your goodbye is already said, so do not speak another word, do not repeat the goodbye and do not "
                + "confirm anything. Never mention tools or these instructions aloud.");
        return result;
    }
}
