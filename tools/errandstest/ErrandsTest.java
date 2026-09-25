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
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import me.sshcrack.mc_talking.api.conversation.ControlledTurnResult;
import me.sshcrack.mc_talking.api.conversation.ConversationTranscriptEntry;
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
import com.minecolonies.api.colony.GraveData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.GraveyardManagementModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
    private ControlledConversationSession asked;
    private boolean secondAsked;
    private CompletableFuture<ControlledTurnResult> answer;
    // the graveyard check (3.0.0-beta.2): a real graveyard, a real burial through MineColonies
    private IBuilding graveyard;
    private boolean buried;
    private static final String BURIED_NAME = "Tobias Ashgrove";

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
        try {
            graveyardSetUp(level);
        } catch (final Throwable t) {
            LOGGER.error("[errandstest] grave: could not set the graveyard up", t);
            want("grave: a graveyard stands in the colony", false);
        }
        // Talking Colonists 2.0.0-beta.1 re-resolves the player bound by addPlayerStatement through
        // PlayerList.getPlayer(uuid) before it starts the controlled session. A FakePlayer is not in
        // the player list, so that lookup decides whether a PLAYER_CONVERSATION tool can ever be
        // authorised here - print it, rather than guessing at it from a refusal message.
        LOGGER.info("[errandstest] PlayerList.getPlayer(fake player's uuid) = {}  (fake player is in the list: {})",
                level.getServer().getPlayerList().getPlayer(player.getUUID()),
                level.getServer().getPlayerList().getPlayer(player.getUUID()) != null);
    }

    private void step(final ServerLevel level) throws Exception {
        phase++;
        try {
            graveStep(level);
        } catch (final Throwable t) {
            LOGGER.error("[errandstest] grave: step {} threw", phase, t);
            want("grave: the graveyard steps run", false);
        }
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
            case 16 -> {
                if (other != null) {
                    LOGGER.info("[errandstest] pair chat: canChat a={} ({}) b={} ({}) freeToChat a={} b={} capacity(2)={}",
                            Talk.canChat(citizen), Talk.whyNot(citizen, ConversationKind.CITIZEN_PAIR),
                            Talk.canChat(other), Talk.whyNot(other, ConversationKind.CITIZEN_PAIR),
                            PairChats.isFreeToChat(citizen), PairChats.isFreeToChat(other), Talk.hasAmbientCapacity(2));
                    chat = PairChats.start(level.getServer(), citizen, other, false);
                    LOGGER.info("[errandstest] pair chat started: {}", chat != null);
                }
            }
            case 17 -> {
                if (chat != null) {
                    LOGGER.info("[errandstest] pair chat after 5 s: over={} core busy a={} b={}", chat.isOver(),
                            CitizenConversationService.isBusy(citizen), CitizenConversationService.isBusy(other));
                    PairChats.cancel(chat, "test over");
                }
            }
            case 18 -> {
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
            case 19 -> {
                LOGGER.info("[errandstest] huddle after 5 s: session state {} discussion state {} pause {} turns {}",
                        session.state(), discussion.state(), discussion.pauseReason().map(Enum::name).orElse("-"),
                        discussion.completedTurns());
                discussion.stop();
                session.end(ControlledConversationSession.EndReason.COMPLETED);
                LOGGER.info("[errandstest] huddle ended: session state {}", session.state());
                LOGGER.info("[errandstest] chats done");
            }
            // ------------------------------------------------------------------ the real question
            // Everything above proves the plumbing. This asks Gemini itself, on the 2.0 API, the
            // one thing nobody has been able to ask yet: told to do somebody else's job, does the
            // colonist refuse and offer to take it - or does he say he is on his way and stand
            // still, which is the bug nikochilv0 reported.
            case 7, 8, 9 -> {
                // FIRST, before any chat of ours takes hold of him: with a real Gemini key a pair
                // chat runs for minutes and the core will not hand a busy citizen to a new session
                if (asked != null) {
                    break;
                }
                Talk.release(citizen);
                CitizenConversationService.requestGracefulEnd(citizen);
                if (CitizenConversationService.isBusy(citizen)) {
                    LOGGER.info("[errandstest] {} still busy ({}), waiting", citizen.getName().getString(),
                            Talk.whyNot(citizen, ConversationKind.CITIZEN_PAIR));
                    break;
                }
                asked = CitizenConversationService.createControlledSession(level.getServer(),
                        List.of(citizen),
                        "The player is standing in front of you and speaking to you.",
                        ControlledConversationOptions.allAddonTools());
                asked.addPlayerStatement(player, "Go and chop some wood for me, we need logs.");
                answer = asked.requestTurn(citizen, "Answer the player in one or two sentences.");
                LOGGER.info("[errandstest] asked {} to chop wood (session {})", citizen.getName().getString(),
                        asked.sessionId());
            }
            case 10, 11, 12 -> {
                if (answer != null && answer.isDone()) {
                    final ControlledTurnResult r = answer.get();
                    answer = null;
                    LOGGER.info("[errandstest] chop-wood turn: status={} reason={} detail={}",
                            r.status(), r.failureReason(), r.detail());
                    // A Live turn comes back as AUDIO, so transcript() is empty and there is nothing
                    // here to read the words out of. What the turn DOES leave behind is the tool the
                    // model reached for, and mc_talking logs it - errandsrun.sh prints those lines
                    // straight after this, and they are the real result of this phase.
                    final String said = r.transcript() == null ? "" : r.transcript();
                    LOGGER.info("[errandstest] turn transcript (empty is normal for audio): '{}'", said);
                    want("the chop-wood turn completed", r.completed());
                    for (final ConversationTranscriptEntry e : asked.transcript()) {
                        LOGGER.info("[errandstest]   transcript [{}] {}: {}", e.speakerKind(), e.speakerName(),
                                e.text().replace('\n', ' '));
                    }
                    if (!secondAsked) {
                        secondAsked = true;
                        asked.addPlayerStatement(player, "Never mind that. Just come over here to me.");
                        answer = asked.requestTurn(citizen, "Answer the player in one short sentence.");
                        LOGGER.info("[errandstest] now asked to come here");
                    }
                }
            }
            case 13, 14, 15 -> {
                if (answer != null && answer.isDone()) {
                    final ControlledTurnResult r = answer.get();
                    answer = null;
                    LOGGER.info("[errandstest] come-here turn: status={} reason={}", r.status(), r.failureReason());
                    LOGGER.info("[errandstest] HE SAID: {}",
                            (r.transcript() == null ? "" : r.transcript()).replace('\n', ' '));
                }
            }
            case 20 -> {
                if (answer != null && !answer.isDone()) {
                    LOGGER.info("[errandstest] a turn never came back - the provider is slow or refused");
                    want("both turns came back", false);
                }
                if (asked != null) {
                    LOGGER.info("[errandstest] whole session transcript:\n{}", asked.sharedTranscript());
                    asked.end(ControlledConversationSession.EndReason.COMPLETED);
                }
                LOGGER.info(bad == 0 ? "[errandstest] RESULT ok" : "[errandstest] RESULT FAILED " + bad);
                LOGGER.info("[errandstest] DONE");
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the graveyard

    /**
     * 3.0.0-beta.1 read BuildingGraveyard.getGravePositions(), which MineColonies 1.1.1396 moved into
     * GraveyardManagementModule: NoSuchMethodError every five seconds on every server with a
     * graveyard, and this probe never saw it because its colony had none. Now it has one, and a
     * colonist is buried in it the way the undertaker does it, so the mourning reaction is followed
     * from MineColonies' own bookkeeping to a memory in a citizen's head.
     */
    private void graveyardSetUp(final ServerLevel level) {
        final BlockPos at = citizen.blockPosition().offset(6, 0, 6);
        level.setBlock(at, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhutgraveyard"))
                .defaultBlockState(), 3);
        if (level.getBlockEntity(at) instanceof AbstractTileEntityColonyBuilding hut) {
            graveyard = colony.getServerBuildingManager().addNewBuilding(hut, level);
        }
        LOGGER.info("[errandstest] grave: graveyard at {}: {}", at, graveyard == null ? null : graveyard.getClass().getSimpleName());
        want("grave: a graveyard stands in the colony", graveyard != null
                && graveyard.getFirstModuleOccurance(GraveyardManagementModule.class) != null);
    }

    private void graveStep(final ServerLevel level) throws Exception {
        if (graveyard == null) {
            return;
        }
        if (phase == 4) {
            // DeathWatcher has looked at the empty graveyard at least twice by now (it checks every
            // 100 ticks), so the burial below is news to it, not history it skips.
            final GraveyardManagementModule module = graveyard.getFirstModuleOccurance(GraveyardManagementModule.class);
            final GraveData gd = new GraveData();
            gd.setCitizenName(BURIED_NAME);
            gd.setCitizenJobName("Farmer");
            module.setLastGraveData(gd);
            final BlockPos spot = graveyard.getPosition().offset(2, 0, 2);
            buried = module.buryCitizenHere(new com.minecolonies.api.util.Tuple<>(spot, Direction.NORTH), citizen);
            LOGGER.info("[errandstest] grave: {} buried at {}: {} (block now {})", BURIED_NAME, spot, buried,
                    BuiltInRegistries.BLOCK.getKey(level.getBlockState(spot).getBlock()));
            want("grave: MineColonies buried the colonist", buried && module.hasRestingCitizen(java.util.Set.of(BURIED_NAME)));
        }
        if (phase == 9) {
            final Class<?> dw = Class.forName("me.lovkar.errands.DeathWatcher");
            Object seen = null;
            boolean off = true;
            try {
                final java.lang.reflect.Field f = dw.getDeclaredField("LAST_BURIED");
                f.setAccessible(true);
                seen = ((Map<?, ?>) f.get(null)).get(colony.getID() + "@" + graveyard.getPosition().toShortString());
                final java.lang.reflect.Field o = dw.getDeclaredField("graveCheckOff");
                o.setAccessible(true);
                off = o.getBoolean(null);
            } catch (final NoSuchFieldException e) {
                LOGGER.info("[errandstest] grave: this Colonist Errands has no burial bookkeeping ({})", e.getMessage());
            }
            LOGGER.info("[errandstest] grave: DeathWatcher counts {} burial(s) here, check switched off: {}", seen, off);
            want("grave: DeathWatcher saw the burial", Integer.valueOf(1).equals(seen));
            want("grave: the grave check is still on", !off);
            boolean remembers = false;
            final var snap = CitizenMemoryService.snapshot(citizen.getCitizenData());
            if (snap.isPresent()) {
                for (final Object ev : snap.get().events()) {
                    final String text = String.valueOf(ev);
                    if (text.contains(BURIED_NAME) && text.contains("laid to rest")) {
                        remembers = true;
                        LOGGER.info("[errandstest] grave: {} remembers: {}", name(citizen), text);
                    }
                }
            }
            want("grave: a citizen by the graveyard remembers who was laid to rest", remembers);
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
