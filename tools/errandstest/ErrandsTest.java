package me.lovkar.errands.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.errands.tc.PairChats;
import me.lovkar.errands.tc.PromptBridge;
import me.lovkar.errands.tc.ToolNames;
import me.lovkar.errands.tc.Rules;
import me.lovkar.errands.tc.Talk;
import me.lovkar.errands.tc.ToolNames;
import me.sshcrack.mc_talking.api.context.CitizenContextService;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionHandle;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionPolicy;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.api.prompt.PromptContribution;
import me.sshcrack.mc_talking.api.prompt.PromptContributionContext;
import me.sshcrack.mc_talking.api.prompt.PromptSessionContext;
import me.sshcrack.mc_talking.api.prompt.PromptTarget;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.api.tool.AiCommandTool;
import me.sshcrack.mc_talking.api.tool.AiQueryTool;
import me.sshcrack.mc_talking.api.tool.AiTool;
import me.sshcrack.mc_talking.api.tool.AiToolContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless smoke test for Colonist Errands 3.0 on the Talking Colonists 2.0 API. Runs in the
 * colony the Voyager colony test builds: fetches Errands' tools back out of the core's registry
 * and calls them through the public contracts with a fake player as the colony owner, checks the
 * activity lease an errand takes, asks the prompt contributor for its blocks (from the server
 * thread and from a background thread), writes and reads a memory, and tries a pair chat.
 */
@Mod("errandstest")
public class ErrandsTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("errandstest");
    private int tick = 0;
    private int phase = 0;
    private IColony colony;
    private AbstractEntityCitizen citizen;
    private AbstractEntityCitizen other;
    private ServerPlayer player;
    private PairChats.Chat chat;
    private CompletableFuture<List<PromptContribution>> background;
    private ControlledConversationSession session;
    private AutonomousDiscussionHandle discussion;

    public ErrandsTest(final IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onTick(final ServerTickEvent.Post e) {
        tick++;
        try {
            if (tick == 900) {
                setUp(e.getServer().overworld());
            } else if (colony != null && tick > 900 && tick % 100 == 0) {
                step(e.getServer().overworld());
            }
        } catch (final Throwable t) {
            LOGGER.error("[errandstest] FAILED at tick {} phase {}", tick, phase, t);
        }
    }

    private void setUp(final ServerLevel level) {
        // Not the FIRST colony: the rig's world is not always wiped and MineColonies keeps the
        // colonies of earlier runs, so the first one in the list is usually a dead town with
        // nobody in it. Take the one that has citizens, newest first.
        int best = -1;
        for (final IColony c : IColonyManager.getInstance().getAllColonies()) {
            int live = 0;
            try {
                for (final ICitizenData cd : c.getCitizenManager().getCitizens()) {
                    if (cd.getEntity().isPresent()) {
                        live++;
                    }
                }
            } catch (final Throwable ignored) {
            }
            // most citizens actually standing in the world wins; a tie goes to the newest colony,
            // because the one colonytest just built is always the highest id
            if (live > best || (live == best && colony != null && c.getID() > colony.getID())) {
                best = live;
                colony = c;
            }
        }
        if (colony != null) {
            LOGGER.info("[errandstest] colony {} of {} known, {} citizen(s) on the books", colony.getID(),
                    IColonyManager.getInstance().getAllColonies().size(),
                    colony.getCitizenManager().getCitizens().size());
        }
        if (colony == null) {
            LOGGER.error("[errandstest] no colony - is the Voyager colony test running?");
            return;
        }
        final List<AbstractEntityCitizen> alive = new ArrayList<>();
        for (final ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            cd.getEntity().ifPresent(alive::add);
        }
        if (alive.isEmpty()) {
            // A colony with no real player in it goes INACTIVE and MineColonies takes the citizen
            // entities away again - which on a headless server is every colony there is. Ask for
            // them back; the data is still there, only the body is gone.
            for (final ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                try {
                    colony.getCitizenManager().spawnOrCreateCitizen(cd, level);
                } catch (final Throwable ignored) {
                }
            }
            for (final ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                cd.getEntity().ifPresent(alive::add);
            }
            LOGGER.info("[errandstest] colony was inactive - respawned {} citizen(s)", alive.size());
        }
        if (alive.isEmpty()) {
            LOGGER.error("[errandstest] the colony has no citizens");
            colony = null;
            return;
        }
        citizen = alive.get(0);
        other = alive.size() > 1 ? alive.get(1) : null;
        player = FakePlayerFactory.getMinecraft(level);
        player.setPos(citizen.getX() + 30, citizen.getY(), citizen.getZ());
        LOGGER.info("[errandstest] colony {} ({} citizens): testing with {} and player {} (owner: {})", colony.getID(),
                alive.size(), name(citizen), player.getGameProfile().getName(),
                player.getUUID().equals(colony.getPermissions().getOwner()));
    }

    private void step(final ServerLevel level) throws Exception {
        phase++;
        switch (phase) {
            case 1 -> {
                LOGGER.info("[errandstest] provider name for come_here: {}", ToolNames.providerName("come_here"));
                query("colony_report", new JsonObject());
                query("citizen_report", new JsonObject());
                query("check_stock", json("item", "bread"));
                query("build_status", new JsonObject());
            }
            case 2 -> {
                command("come_here", new JsonObject());
                LOGGER.info("[errandstest] right after come_here: lease held={} core busy={}", Talk.isHeld(citizen.getUUID()),
                        CitizenConversationService.isBusy(citizen));
            }
            case 3 -> {
                LOGGER.info("[errandstest] after come_here: lease held={} core busy={} talkingToPlayer={} canChat={} ({})",
                        Talk.isHeld(citizen.getUUID()), CitizenConversationService.isBusy(citizen),
                        Talk.isTalkingToPlayer(citizen), Talk.canChat(citizen), Talk.whyNot(citizen, ConversationKind.CITIZEN_PAIR));
                command("stop_errand", new JsonObject());
            }
            case 4 -> {
                LOGGER.info("[errandstest] after stop_errand: lease held={} core busy={}", Talk.isHeld(citizen.getUUID()),
                        CitizenConversationService.isBusy(citizen));
                command("make_promise", json("promise", "I will bring you ten bread", "due_in_days", 2, "about", "food"));
                final var snap = CitizenMemoryService.snapshot(citizen.getCitizenData());
                LOGGER.info("[errandstest] memory snapshot present={} events={}", snap.isPresent(),
                        snap.map(s -> s.events().size()).orElse(-1));
                snap.ifPresent(s -> s.events().forEach(ev -> LOGGER.info("[errandstest]   event: {}", ev)));
                LOGGER.info("[errandstest] urgency 1.0 -> {}", Rules.urgency(citizen, 1.0));
            }
            case 5 -> {
                final CitizenPromptView view = CitizenContextService.snapshot(citizen, player);
                final PromptContributionContext ctx = new PromptContributionContext(view, PromptTarget.CITIZEN_ROLEPLAY,
                        PromptSessionContext.empty());
                final List<PromptContribution> onServer = new PromptBridge().contribute(ctx);
                LOGGER.info("[errandstest] prompt contributions (server thread): {}", onServer.size());
                String guidance = "";
                for (final PromptContribution c : onServer) {
                    LOGGER.info("[errandstest]   [{}] {} ({} chars): {}", c.kind(), c.section(), c.text().length(),
                            c.text().substring(0, Math.min(160, c.text().length())).replace('\n', ' '));
                    if (c.text().contains("WORK TRUTH")) {
                        guidance = c.text();
                    }
                }
                // nikochilv0's report: a colonist told to chop wood must say he cannot, not play along.
                // The whole fix is one prompt block, so the only thing worth testing is that it is there
                // and that it says something true about THIS citizen and THIS colony.
                final int cut = guidance.indexOf("WORK TRUTH");
                final String work = cut < 0 ? "" : guidance.substring(cut);
                LOGGER.info("[errandstest] WORK TRUTH block: {}", work.replace('\n', ' '));
                want("the work-truth block reaches the prompt", !work.isEmpty());
                want("...it forbids playing along", work.contains("NEVER answer that you are on your way"));
                // a prompt block is NOT a tool description: {placeholders} are resolved only when the
                // core reads description(), so a block must name the tool through providerName itself
                want("...it offers take_job by the name the model will see",
                        work.contains(ToolNames.providerName("take_job")) && !work.contains("{take_job}"));
                want("...it says what this citizen's job is", work.contains("NO job at all") || work.contains("Your job is:"));
                want("...and what the colony cannot do at all", work.contains("workplace for chopping wood")
                        || work.contains("chopping wood (the "));
                // a provider thread asks while the server keeps ticking - the answer is read next phase
                background = CompletableFuture.supplyAsync(() -> new PromptBridge().contribute(ctx));
            }
            case 6 -> {
                LOGGER.info("[errandstest] prompt contributions (background thread): done={} count={}", background.isDone(),
                        background.isDone() ? background.get().size() : -1);
                command("resolve_promise", json("kept", true));
                command("leave_conversation", new JsonObject());
                command("call_me", json("name", "Boss"));
            }
            case 7 -> {
                if (other != null) {
                    LOGGER.info("[errandstest] pair chat: canChat a={} ({}) b={} ({}) freeToChat a={} b={} capacity(2)={}",
                            Talk.canChat(citizen), Talk.whyNot(citizen, ConversationKind.CITIZEN_PAIR),
                            Talk.canChat(other), Talk.whyNot(other, ConversationKind.CITIZEN_PAIR),
                            PairChats.isFreeToChat(citizen), PairChats.isFreeToChat(other), Talk.hasAmbientCapacity(2));
                    chat = PairChats.start(level.getServer(), citizen, other, false);
                    LOGGER.info("[errandstest] pair chat started: {}", chat != null);
                }
            }
            case 8 -> {
                if (chat != null) {
                    LOGGER.info("[errandstest] pair chat after 5 s: over={} core busy a={} b={}", chat.isOver(),
                            CitizenConversationService.isBusy(citizen), CitizenConversationService.isBusy(other));
                    PairChats.cancel(chat, "test over");
                }
            }
            case 9 -> {
                // the huddle's plumbing: a controlled session with every citizen, floor delegated
                final List<AbstractEntityCitizen> all = new ArrayList<>();
                for (final ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                    cd.getEntity().ifPresent(all::add);
                }
                session = CitizenConversationService.createControlledSession(level.getServer(), all,
                        "A chat between neighbours stood together.", ControlledConversationOptions.noAddonTools());
                discussion = session.delegateAutonomousDiscussion(new AutonomousDiscussionPolicy(4, java.time.Duration.ofMinutes(1), 256));
                discussion.completion().thenAccept(reason -> LOGGER.info("[errandstest] huddle completed: {}", reason));
                LOGGER.info("[errandstest] controlled session {} with {} participants, discussion state {}",
                        session.sessionId(), all.size(), discussion.state());
            }
            case 10 -> {
                LOGGER.info("[errandstest] huddle after 5 s: session state {} discussion state {} pause {} turns {}",
                        session.state(), discussion.state(), discussion.pauseReason().map(Enum::name).orElse("-"),
                        discussion.completedTurns());
                discussion.stop();
                session.end(ControlledConversationSession.EndReason.COMPLETED);
                LOGGER.info("[errandstest] huddle ended: session state {}", session.state());
                LOGGER.info(bad == 0 ? "[errandstest] RESULT ok" : "[errandstest] RESULT FAILED " + bad);
                LOGGER.info("[errandstest] DONE");
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ helpers

    private AiTool tool(final String name) throws Exception {
        final Class<?> runtime = Class.forName("me.sshcrack.mc_talking.internal.tool.AiToolRuntime");
        final Object registered = runtime.getMethod("findById", String.class).invoke(null, ToolNames.id(name));
        if (registered == null) {
            throw new IllegalStateException("tool " + name + " is not registered with the core");
        }
        return (AiTool) registered.getClass().getMethod("tool").invoke(registered);
    }

    private AiToolContext context() {
        final UUID session = UUID.randomUUID();
        return new AiToolContext() {
            @Override public UUID sessionId() { return session; }
            @Override public AbstractEntityCitizen citizen() { return citizen; }
            @Override public IColony colony() { return colony; }
            @Override public ServerPlayer player() { return player; }
        };
    }

    private void query(final String name, final JsonObject params) throws Exception {
        final AiTool t = tool(name);
        final JsonObject out = ((AiQueryTool) t).executeQuery(context(), params);
        LOGGER.info("[errandstest] {} -> {}", name, trim(out));
    }

    private void command(final String name, final JsonObject params) throws Exception {
        final AiTool t = tool(name);
        final JsonObject out = ((AiCommandTool) t).executeCommand(context(), params).toCompletableFuture().get(2, TimeUnit.SECONDS);
        LOGGER.info("[errandstest] {} -> {}", name, trim(out));
    }

    private static JsonObject json(final Object... kv) {
        final JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            final Object v = kv[i + 1];
            if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else if (v instanceof Boolean b) o.addProperty((String) kv[i], b);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        return o;
    }

    private static String trim(final JsonObject o) {
        final String s = String.valueOf(o);
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    private static String name(final AbstractEntityCitizen c) {
        try {
            return c.getCitizenData().getName();
        } catch (final Throwable t) {
            return "?";
        }
    }

    private static int bad = 0;

    /** One asserted fact, printed the way every other probe in this project prints them. */
    private static void want(final String what, final boolean ok) {
        LOGGER.info("[errandstest] {}: {}", what, ok ? "yes" : "WRONG");
        if (!ok) {
            bad++;
        }
    }
}
