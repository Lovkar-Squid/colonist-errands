package me.lovkar.errands.tc;

import java.util.HashSet;
import java.util.Set;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.ErrandManager;
import me.lovkar.errands.FoodCheck;
import me.lovkar.errands.PromiseStore;
import me.lovkar.errands.SupplyCheck;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.pregen.PregenerationPromptContext;

/**
 * The rules Errands adds to who speaks and who walks up to the player - as Talking Colonists 2.0
 * speech policies, urgency modifiers and pregeneration prompt modifiers, where 2.x had three
 * mixins.
 */
public final class Rules {

    private Rules() {
    }

    /**
     * Lovkar's rule: random citizen-to-citizen chats are for citizens who have nothing to do -
     * the unemployed and children any time, workers by their job's policy, guards never while on
     * duty. Only the core's RANDOM picks are vetoed: our own chats check the same rule before
     * they start, and a veto on CITIZEN_PAIR would stop them at the door.
     */
    public static boolean canSpeak(final AbstractEntityCitizen citizen, final ConversationKind kind) {
        if (kind != ConversationKind.RANDOM_CITIZEN) {
            return true;
        }
        try {
            return PairChats.isFreeToChat(citizen);
        } catch (final Throwable t) {
            return true;
        }
    }

    /**
     * How urgently the citizen wants to walk up to the player, after the core has worked out its
     * own number. Guards on our escort/defense duty are muted entirely (on duty they guard;
     * complaints wait for the dismissal). A citizen with an open, not-overdue promise is patient
     * about the promised problem (Lovkar's idea #23); one under medical care, one whose hunger the
     * restaurant will solve, one whose tools the courier is already bringing - the same: the core's
     * weight is recomputed with those topics muted, and the lower number wins.
     */
    public static double urgency(final AbstractEntityCitizen citizen, final double current) {
        try {
            if (current <= 0.0 || citizen == null) {
                return current;
            }
            if (ErrandManager.isOnMilitaryDuty(citizen)) {
                return 0.0;
            }
            final ICitizenData data = citizen.getCitizenData();
            if (data == null) {
                return current;
            }
            final Set<String> topics = new HashSet<>(PromiseStore.activeSuppressions(data.getName()));
            try {
                // Lovkar's hospital report (Gunilda): a sick citizen he SENT to the hospital left it a
                // minute later to walk up and complain about being sick. Being ill at all is reason
                // enough not to cross the colony to complain; if the player asks, they can still say it.
                final var dh = data.getCitizenDiseaseHandler();
                if (dh != null && (dh.isSick() || dh.isHurt())) {
                    topics.add("health");
                }
            } catch (final Throwable ignored) {
            }
            try {
                // Lovkar's report: citizens walk up asking for food while carrying edible food, or
                // while the restaurant has food to serve them. Self-solvable hunger is muted.
                if (!topics.contains("food") && data.getSaturation() <= 3.0 && FoodCheck.canResolveHungerAlone(citizen)) {
                    topics.add("food");
                }
            } catch (final Throwable ignored) {
            }
            try {
                // Lovkar's report: the forester pesters the player for tools the courier is ALREADY bringing.
                if (SupplyCheck.requestsUnderway(citizen)) {
                    topics.add("supply");
                }
            } catch (final Throwable ignored) {
            }
            if (topics.isEmpty()) {
                return current;
            }
            return Math.min(current, PromiseStore.suppressedUrgency(citizen, topics));
        } catch (final Throwable t) {
            return current;
        }
    }

    /**
     * Lovkar's report: colonists sometimes wish him "good morning" in the evening. A pregenerated
     * greeting is written and voiced ahead of time and can be heard hours later, so every
     * pregeneration prompt asks for a line that works at any hour.
     */
    private static final String TIMELESS =
            " IMPORTANT: this line is recorded now but may be heard hours later, so it must work at ANY time of day. "
                    + "Never say good morning, good afternoon, good evening or good night, and never refer to the "
                    + "time, the light, the weather or the meal you are about to have. Greet in a way that fits "
                    + "dawn and midnight alike.";

    public static String pregenerationPrompt(final PregenerationPromptContext context, final String prompt) {
        try {
            if (prompt == null || prompt.contains("must work at ANY time of day")) {
                return prompt;
            }
            return prompt + TIMELESS;
        } catch (final Throwable t) {
            return prompt;
        }
    }
}
