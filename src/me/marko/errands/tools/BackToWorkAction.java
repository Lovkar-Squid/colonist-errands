package me.marko.errands.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.ai.statemachine.AIOneTimeEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.ai.workers.AbstractAISkeleton;
import me.marko.errands.C2cAudioFollower;
import me.marko.errands.ColonistErrands;
import me.marko.errands.ErrandManager;
import me.marko.errands.Texts;
import me.sshcrack.mc_talking.manager.tools.PlayerFunctionAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Marko's idea: walk up to gossiping citizens and send them back to work.
 * Ends the addressed citizen's idle chat, kicks their work AI, and does the
 * same for their (current or recent) chat partner.
 */
public class BackToWorkAction extends PlayerFunctionAction {

    public BackToWorkAction() {
        super("back_to_work",
                "The player orders you back to work ('back to work', 'stop chatting', 'go do your job', "
                        + "'quit gossiping'). Your idle chat ends and you AND the colleague you were chatting with "
                        + "both resume your jobs immediately. This is a final order: confirm it with ONE short "
                        + "apologetic sentence, then call leave_conversation right away. "
                        + "If you have no job, say so honestly instead.");
    }

    @Override
    @NotNull
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        JsonObject result = new JsonObject();
        ICitizenData data = citizen.getCitizenData();

        boolean hadChat = C2cAudioFollower.abortFor(citizen.getUUID());
        UUID partnerId = C2cAudioFollower.partnerOf(citizen.getUUID());
        String partnerNote = "";

        // Send the chat partner back to work too.
        if (partnerId != null) {
            try {
                for (ICitizenData other : colony.getCitizenManager().getCitizens()) {
                    if (other.getEntity().isPresent() && partnerId.equals(other.getEntity().get().getUUID())) {
                        AbstractEntityCitizen partner = other.getEntity().get();
                        ErrandManager.cancel(partner);
                        if (kickWorkAI(other)) {
                            partnerNote = " Your chat partner " + other.getName() + " is also heading back to work.";
                            ColonistErrands.LOGGER.info("[BackToWork] Partner {} kicked back to work", other.getName());
                        }
                        break;
                    }
                }
            } catch (Throwable t) {
                ColonistErrands.LOGGER.warn("back_to_work partner kick failed", t);
            }
        }

        ErrandManager.cancel(citizen);

        if (data == null || data.getJob() == null) {
            result.addProperty("success", false);
            result.addProperty("error", "You have no job to return to (honestly tell the player you are unemployed"
                    + (partnerNote.isEmpty() ? "" : "; but your chat partner went back to work") + ").");
            return result;
        }
        boolean kicked = kickWorkAI(data);
        ColonistErrands.LOGGER.info("[BackToWork] {} ordered back to work (chat aborted: {}, AI kicked: {})",
                data.getName(), hadChat, kicked);
        result.addProperty("success", true);
        result.addProperty("info", "Understood - you stop idling RIGHT NOW and resume your job." + partnerNote
                + Texts.GOODBYE);
        return result;
    }

    /** Same proven kick as farmer_plant/guard restore: jolt the worker AI into START_WORKING. */
    static boolean kickWorkAI(ICitizenData data) {
        try {
            IJob<?> job = data.getJob();
            if (job != null && job.getWorkerAI() instanceof AbstractAISkeleton<?> ai) {
                ai.registerTarget(new AIOneTimeEventTarget(AIWorkerState.START_WORKING));
                return true;
            }
        } catch (Throwable t) {
            ColonistErrands.LOGGER.warn("kickWorkAI failed", t);
        }
        return false;
    }
}
