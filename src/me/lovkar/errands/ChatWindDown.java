package me.lovkar.errands;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.conversations.LiveConversationWsClient;
import me.sshcrack.mc_talking.manager.GeminiWsClient;

/**
 * Lovkar's report: when a counter chat reached our three minute cap it was simply
 * cut off mid-word. Ending a conversation politely is possible, but only for the
 * LIVE_WEBSOCKETS kind, and it is worth knowing why.
 * <p>
 * mc_talking has two ways of making citizens talk to each other
 * ({@code conversationMode}, AUTO tries the first and falls back to the second):
 * <ul>
 *   <li><b>FLASH_TTS</b> - Gemini Flash writes the whole transcript in one go and
 *       Gemini TTS renders it as a single multi-speaker clip. By the time you hear
 *       it, every word is already decided; there is nothing to negotiate with, and
 *       stopping it can only ever be a cut. It ends by itself when the clip ends.</li>
 *   <li><b>LIVE_WEBSOCKETS</b> - two live Gemini sessions feeding each other, one
 *       speaking while the other holds its audio, swapping turn by turn (capped at
 *       ten turns between them). This one is a real conversation still in progress,
 *       so it can be asked to finish.</li>
 * </ul>
 * For the live kind we do two things, in order, and never at the same moment:
 * first {@link #askToWrapUp} drops a line into both sessions telling them to bring
 * it to a close - they get a whole turn to say goodbye properly - and only then
 * {@link #endAfterThisLine} sets mc_talking's own end flag, which lets the current
 * sentence finish and closes the session cleanly at the end of it. Setting the flag
 * first would end the conversation before the goodbye was ever spoken.
 */
public final class ChatWindDown {

    private ChatWindDown() {
    }

    /** The live session for this citizen, or null if there is none (Flash/TTS, or not talking). */
    private static LiveConversationWsClient liveClient(AbstractEntityCitizen citizen) {
        try {
            if (citizen == null) {
                return null;
            }
            GeminiWsClient client = ConversationManager.getClientForEntity(citizen.getUUID());
            if (client instanceof LiveConversationWsClient live && !live.isClosed()) {
                return live;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** True when this pair is talking the live way and can therefore be asked to finish. */
    public static boolean canBeAskedToFinish(AbstractEntityCitizen a, AbstractEntityCitizen b) {
        return liveClient(a) != null || liveClient(b) != null;
    }

    /**
     * Ask both speakers to bring the conversation to a close in their own words.
     * The text is queued behind whatever is being said right now, so nobody is
     * interrupted; they hear it as the next thing on their mind.
     *
     * @return true if at least one live session took the instruction
     */
    public static boolean askToWrapUp(AbstractEntityCitizen a, AbstractEntityCitizen b, String instruction) {
        boolean any = false;
        for (AbstractEntityCitizen c : new AbstractEntityCitizen[]{a, b}) {
            LiveConversationWsClient live = liveClient(c);
            if (live == null) {
                continue;
            }
            try {
                live.addPromptTextAfterTalkingComplete(instruction);
                any = true;
            } catch (Throwable ignored) {
            }
        }
        return any;
    }

    /**
     * Let the current sentence finish, then close. mc_talking propagates the flag
     * to the peer session itself, so one call is enough - we try both only in case
     * one of the two has already gone.
     *
     * @return true if at least one live session took the request
     */
    public static boolean endAfterThisLine(AbstractEntityCitizen a, AbstractEntityCitizen b) {
        boolean any = false;
        for (AbstractEntityCitizen c : new AbstractEntityCitizen[]{a, b}) {
            LiveConversationWsClient live = liveClient(c);
            if (live == null) {
                continue;
            }
            try {
                live.endConversationWhenPossible();
                any = true;
            } catch (Throwable ignored) {
            }
        }
        return any;
    }
}
