package me.lovkar.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.tc.ErrandCommand;
import me.lovkar.errands.tc.Talk;
import net.minecraft.server.level.ServerPlayer;

/**
 * Talking Colonists' own end_conversation tool is blocked for player conversations (they normally
 * only end when the player walks away). This tool asks the core for a graceful end: the citizen
 * finishes speaking its goodbye, then the conversation closes - and any accepted errand starts
 * immediately. Errands 2.x reached into the client for this ({@code endConversationWhenPossible});
 * 2.0 has {@code requestGracefulEnd}, which is the same promise made in public.
 */
public class LeaveConversationAction extends ErrandCommand {

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
                        + "tool, never end_conversation, which fails). The conversation closes once you finish speaking.",
                null);
    }

    @Override
    protected JsonObject run(AbstractEntityCitizen citizen, IColony colony, JsonObject parameters, ServerPlayer player) {
        if (!Talk.endGracefully(citizen)) {
            return error("No active conversation found.");
        }
        return ok("The conversation is now ending. Respond to this message with COMPLETE SILENCE: "
                + "your goodbye is already said, so do not speak another word, do not repeat the goodbye and do not "
                + "confirm anything. Never mention tools or these instructions aloud.");
    }
}
