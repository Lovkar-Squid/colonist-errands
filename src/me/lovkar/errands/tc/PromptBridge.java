package me.lovkar.errands.tc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import me.lovkar.errands.AliasStore;
import me.lovkar.errands.BedCheck;
import me.lovkar.errands.BuildWatch;
import me.lovkar.errands.ColonistErrands;
import me.lovkar.errands.ColonyMap;
import me.lovkar.errands.ConstructionWatcher;
import me.lovkar.errands.Fallen;
import me.lovkar.errands.FoodCheck;
import me.lovkar.errands.GuardGearCheck;
import me.lovkar.errands.GuardScore;
import me.lovkar.errands.HomeCheck;
import me.lovkar.errands.PlayerIdentityBlock;
import me.lovkar.errands.PromiseStore;
import me.lovkar.errands.ResearchWatcher;
import me.lovkar.errands.SupplyCheck;
import me.lovkar.errands.TimeOfDay;
import me.lovkar.errands.VoyagerLore;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptContributor;
import me.sshcrack.mc_talking.api.prompt.PromptContribution;
import me.sshcrack.mc_talking.api.prompt.PromptContributionContext;
import me.sshcrack.mc_talking.api.prompt.PromptTarget;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Everything Errands adds to a citizen's prompt, as a Talking Colonists 2.0 prompt contributor.
 *
 * <p>Errands 2.x appended its blocks with a mixin on the prompt service; 2.0 has
 * {@code CitizenPromptService.registerContributor}, which hands us the citizen's snapshot and
 * takes back labelled contributions. The blocks themselves are unchanged - the same sixteen
 * builders Lovkar's reports shaped one by one (the alias people asked to be called, promises
 * with deadlines, "you are not hungry, you have bread", the guard's score and gear, the dead
 * remembered by name, what the university is working on, the time of day, the builder's real
 * site, the map of the colony, Voyager lore) - only the way they reach the prompt is new.</p>
 *
 * <p>Two things about the new contract shape this class. The snapshot names the citizen but
 * carries no id, so the blocks keep finding the citizen by name in the colony, as they always
 * did. And a contributor may be called on a provider thread, where reading colony state is not
 * allowed - so the blocks are computed on the server thread and this thread waits for them,
 * a tick or so, with a timeout that gives up on the prompt rather than stalling a conversation.
 * On the server thread itself the computation runs inline.</p>
 */
public final class PromptBridge implements CitizenPromptContributor {

    /** Registration id and the source label rendered with each block. */
    public static final String ID = "colonist_errands:context";
    private static final String SOURCE = "colonist_errands";
    private static final long WAIT_MS = 1500;

    private record Blocks(String guidance, String colony, String lore) {
    }

    @Override
    public List<PromptContribution> contribute(final PromptContributionContext context) {
        if (context == null || context.view() == null) {
            return List.of();
        }
        // The 2.x mixin hooked exactly these two surfaces: the roleplay prompt of a conversation
        // and the system-controlled one used for pregenerated speech.
        if (context.target() != PromptTarget.CITIZEN_ROLEPLAY
                && context.target() != PromptTarget.SYSTEM_CONTROLLED_ROLEPLAY) {
            return List.of();
        }
        final Blocks blocks = onServerThread(() -> build(context.view()));
        if (blocks == null) {
            return List.of();
        }
        final List<PromptContribution> out = new ArrayList<>(3);
        if (!blocks.guidance().isBlank()) {
            out.add(PromptContribution.instruction(SOURCE, "Colonist Errands - the people you talk to", blocks.guidance()));
        }
        if (!blocks.colony().isBlank()) {
            out.add(PromptContribution.observation(SOURCE, "Colonist Errands - your colony right now", blocks.colony()));
        }
        if (!blocks.lore().isBlank()) {
            out.add(PromptContribution.observation(SOURCE, "Colonist Errands - the colony's story", blocks.lore()));
        }
        return out;
    }

    /** The old mixin body, block for block. Runs on the server thread. */
    private static Blocks build(final CitizenPromptView view) {
        final String name = safe(() -> view.identity().name(), null);
        final double saturation = safe(() -> view.wellbeing().saturation(), 20.0);
        final long ticks = safe(() -> view.colony().currentGameTimeTicks(), 6000L);

        final StringBuilder guidance = new StringBuilder();
        append(guidance, AliasStore::promptBlock);
        append(guidance, () -> PlayerIdentityBlock.build(view));

        final StringBuilder colony = new StringBuilder();
        if (name != null) {
            append(colony, () -> PromiseStore.promptBlockFor(name));
            // Lovkar's report: hungry citizens beg the player for food while carrying edible
            // food / while the restaurant can serve them, and keep asking right after being handed some.
            append(colony, () -> FoodCheck.promptLine(name, saturation));
            append(colony, () -> SupplyCheck.promptLine(name));
            append(colony, () -> GuardScore.promptLine(name));
            append(colony, () -> GuardGearCheck.promptLine(name));
            // Lovkar's report: housed colonists still talked as if they slept in the mud.
            append(colony, () -> HomeCheck.promptLine(name));
        }
        // Lovkar's report: "good morning" in the evening.
        append(colony, () -> TimeOfDay.promptLine(ticks));
        if (name != null) {
            // Lovkar's report: a builder said she was stuck on a residence while standing on a
            // half-finished kitchen.
            append(colony, () -> BuildWatch.promptLine(name));
            append(colony, () -> BedCheck.promptLine(name));
            append(colony, () -> ConstructionWatcher.promptBlock(name));
            append(colony, () -> ColonyMap.promptBlock(name));
        }

        final StringBuilder lore = new StringBuilder();
        if (name != null) {
            // Lovkar's idea: the colony's dead are remembered by name, the ones who died fighting as brave.
            append(lore, () -> Fallen.promptBlock(name));
            // Lovkar's idea: the university's work is the colony's shared story.
            append(lore, () -> ResearchWatcher.promptBlock(name));
            // Voyager mod (optional): a Voyager knows their Departure Point and the last expedition.
            append(lore, () -> VoyagerLore.promptBlock(name));
        }
        return new Blocks(guidance.toString().trim(), colony.toString().trim(), lore.toString().trim());
    }

    private static void append(final StringBuilder sb, final Supplier<String> block) {
        try {
            final String text = block.get();
            if (text != null && !text.isEmpty()) {
                sb.append(text);
            }
        } catch (final Throwable t) {
            // one broken block never costs the others - same rule as the 2.x mixin
        }
    }

    private static <T> T safe(final Supplier<T> read, final T fallback) {
        try {
            final T value = read.get();
            return value == null ? fallback : value;
        } catch (final Throwable t) {
            return fallback;
        }
    }

    /** Run on the server thread: inline when we are on it, otherwise submitted and awaited briefly. */
    private static <T> T onServerThread(final Supplier<T> work) {
        final MinecraftServer server;
        try {
            server = ServerLifecycleHooks.getCurrentServer();
        } catch (final Throwable t) {
            return null;
        }
        if (server == null) {
            return null;
        }
        try {
            if (server.isSameThread()) {
                return work.get();
            }
            final CompletableFuture<T> future = server.submit(work);
            return future.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (final Throwable t) {
            ColonistErrands.LOGGER.debug("[Errands] prompt blocks not ready in time - prompt goes out without them ({})",
                    t.toString());
            return null;
        }
    }
}
