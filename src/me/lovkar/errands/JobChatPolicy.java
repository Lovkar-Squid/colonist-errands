package me.lovkar.errands;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.jobs.IJob;

import java.util.Map;

/**
 * Lovkar's per-job chat policy: which professions may hold citizen-to-citizen
 * chats DURING work, which chat WHILE WALKING together (patrol guards,
 * couriers), and which only when they have no task.
 *
 * CHATTY  - calm, social, bench/pen work: may chat during work; the chaperone
 *           walks the pair together and they stand facing each other.
 * WALKER  - professions on the move: may chat while walking; the chaperone
 *           does NOT freeze them - they stroll together (barracks patrol
 *           buddies, couriers) and the voices walk along.
 * FOCUSED - heavy/dangerous/noisy work: chat only when idle at the job.
 */
public final class JobChatPolicy {

    public enum Policy { CHATTY, WALKER, FOCUSED }

    private static final Map<String, Policy> BY_JOB_CLASS = Map.ofEntries(
            // Library & school - they sit together all day.
            Map.entry("JobResearch", Policy.CHATTY),
            Map.entry("JobStudent", Policy.CHATTY),
            Map.entry("JobTeacher", Policy.CHATTY),
            Map.entry("JobPupil", Policy.CHATTY),
            // Kitchen folk - social by nature.
            Map.entry("JobCook", Policy.CHATTY),
            Map.entry("JobChef", Policy.CHATTY),
            Map.entry("JobBaker", Policy.CHATTY),
            // Calm counter/garden work.
            Map.entry("JobFlorist", Policy.CHATTY),
            Map.entry("JobEnchanter", Policy.CHATTY),
            Map.entry("JobAlchemist", Policy.CHATTY),
            Map.entry("JobHealer", Policy.CHATTY),
            Map.entry("JobComposter", Policy.CHATTY),
            Map.entry("JobBeekeeper", Policy.CHATTY),
            // Herders hang around their pens.
            Map.entry("JobShepherd", Policy.CHATTY),
            Map.entry("JobCowboy", Policy.CHATTY),
            Map.entry("JobSwineHerder", Policy.CHATTY),
            Map.entry("JobChickenHerder", Policy.CHATTY),
            Map.entry("JobRabbitHerder", Policy.CHATTY),
            Map.entry("JobStablemaster", Policy.CHATTY),
            // Fishermen chatting by the water is a classic.
            Map.entry("JobFisherman", Policy.CHATTY),
            // Trainees between training posts.
            Map.entry("JobCombatTraining", Policy.CHATTY),
            Map.entry("JobArcherTraining", Policy.CHATTY),
            // On the move - chat WHILE walking, no freezing.
            Map.entry("JobKnight", Policy.WALKER),
            Map.entry("JobRanger", Policy.WALKER),
            Map.entry("JobDruid", Policy.WALKER),
            Map.entry("JobDeliveryman", Policy.WALKER),
            // Heavy, dangerous or noisy work - only when idle.
            Map.entry("JobBuilder", Policy.FOCUSED),
            Map.entry("JobMiner", Policy.FOCUSED),
            Map.entry("JobQuarrier", Policy.FOCUSED),
            Map.entry("JobLumberjack", Policy.FOCUSED),
            Map.entry("JobFarmer", Policy.FOCUSED),
            Map.entry("JobSmelter", Policy.FOCUSED),
            Map.entry("JobStonemason", Policy.FOCUSED),
            Map.entry("JobSawmill", Policy.FOCUSED),
            Map.entry("JobBlacksmith", Policy.FOCUSED),
            Map.entry("JobMechanic", Policy.FOCUSED),
            Map.entry("JobStoneSmeltery", Policy.FOCUSED),
            Map.entry("JobGlassblower", Policy.FOCUSED),
            Map.entry("JobDyer", Policy.FOCUSED),
            Map.entry("JobFletcher", Policy.FOCUSED),
            Map.entry("JobCrusher", Policy.FOCUSED),
            Map.entry("JobSifter", Policy.FOCUSED),
            Map.entry("JobConcreteMixer", Policy.FOCUSED),
            Map.entry("JobPlanter", Policy.FOCUSED),
            Map.entry("JobNetherWorker", Policy.FOCUSED),
            Map.entry("JobUndertaker", Policy.FOCUSED)
    );

    private JobChatPolicy() {
    }

    /** Policy for this citizen's job; CHATTY for the jobless, FOCUSED for unknown jobs. */
    public static Policy of(ICitizenData data) {
        if (data == null || data.getJob() == null) {
            return Policy.CHATTY;
        }
        IJob<?> job = data.getJob();
        return BY_JOB_CLASS.getOrDefault(job.getClass().getSimpleName(), Policy.FOCUSED);
    }
}
