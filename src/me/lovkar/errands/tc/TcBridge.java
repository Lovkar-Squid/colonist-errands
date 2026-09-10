package me.lovkar.errands.tc;

import java.util.ArrayList;
import java.util.List;

import me.lovkar.errands.ColonistErrands;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationRules;
import me.sshcrack.mc_talking.api.pregen.PregenerationPromptService;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptService;
import me.sshcrack.mc_talking.api.registration.AddonRegistration;
import me.sshcrack.mc_talking.api.tool.AiToolRegistry;

/**
 * Where Colonist Errands plugs into Talking Colonists: every registration the addon API offers
 * and Errands uses, made once at common setup and kept for the life of the mod (the API keeps
 * process-lifetime registrations open across server restarts; closing them on a server stop
 * would only mean registering them again).
 */
public final class TcBridge {

    private TcBridge() {
    }

    /** Registration order among addons; Errands is happy to go after the core's own contributors. */
    private static final int ORDER = 100;

    private static final List<AddonRegistration> HANDLES = new ArrayList<>();
    private static boolean registered;

    /**
     * @return the number of tools registered, or -1 when Talking Colonists' API is not there
     */
    public static synchronized int register(final List<ErrandTool> tools) {
        if (registered) {
            return HANDLES.size();
        }
        try {
            if (!TalkingColonistsApi.isAvailable()) {
                ColonistErrands.LOGGER.error("[ColonistErrands] Talking Colonists' addon API is not available - "
                        + "is Talking Colonists 2.0 or newer installed? Errands does nothing without it.");
                return -1;
            }
            final int runtime = TalkingColonistsApi.runtimeApiMajorVersion();
            if (runtime != TalkingColonistsApi.API_MAJOR_VERSION) {
                ColonistErrands.LOGGER.warn("[ColonistErrands] built against Talking Colonists API {} but API {} is running - "
                        + "expect trouble", TalkingColonistsApi.API_MAJOR_VERSION, runtime);
            }
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.error("[ColonistErrands] could not reach Talking Colonists' addon API", t);
            return -1;
        }

        int count = 0;
        for (final ErrandTool tool : tools) {
            try {
                HANDLES.add(AiToolRegistry.register(ToolNames.NAMESPACE, tool.name(), tool));
                ToolNames.verify(tool.name());
                count++;
            } catch (final Throwable t) {
                ColonistErrands.LOGGER.error("[ColonistErrands] could not register tool {}", tool.name(), t);
            }
        }
        keep("prompt contributor", () -> CitizenPromptService.registerContributor(PromptBridge.ID, ORDER, new PromptBridge()));
        keep("speech policy", () -> CitizenConversationRules.registerSpeechPolicy("colonist_errands:job_chat_policy", ORDER, Rules::canSpeak));
        keep("urgency modifier", () -> CitizenConversationRules.registerUrgencyModifier("colonist_errands:patience", ORDER, Rules::urgency));
        keep("pregeneration modifier", () -> PregenerationPromptService.registerModifier("colonist_errands:timeless_greetings", ORDER, Rules::pregenerationPrompt));
        registered = true;
        return count;
    }

    private interface Registration {
        AddonRegistration register();
    }

    private static void keep(final String what, final Registration registration) {
        try {
            HANDLES.add(registration.register());
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.error("[ColonistErrands] could not register the {}", what, t);
        }
    }
}
