package me.lovkar.errands;

import org.slf4j.LoggerFactory;
import me.lovkar.errands.tools.BackToWorkAction;
import me.lovkar.errands.tools.CallCitizenAction;
import me.lovkar.errands.tools.ColonyReportAction;
import me.lovkar.errands.tools.DeliverItemAction;
import me.lovkar.errands.tools.FindCitizenAction;
import me.lovkar.errands.tools.MakePromiseAction;
import me.lovkar.errands.tools.NotePlayerConductAction;
import me.lovkar.errands.tools.PatrolHereAction;
import me.lovkar.errands.tools.RedAlertAction;
import me.lovkar.errands.tools.ResearchStatusAction;
import me.lovkar.errands.tools.ResolvePromiseAction;
import me.lovkar.errands.tools.TakeJobAction;
import me.lovkar.errands.tools.WhyUnhappyAction;
import me.lovkar.errands.tools.CallMeAction;
import me.lovkar.errands.tools.CheckStockAction;
import me.lovkar.errands.tools.CitizenReportAction;
import me.lovkar.errands.tools.ComeHereAction;
import me.lovkar.errands.tools.DefendHereAction;
import me.lovkar.errands.tools.DismissAction;
import me.lovkar.errands.tools.EveryoneHomeAction;
import me.lovkar.errands.tools.FarmerPlantAction;
import me.lovkar.errands.tools.FetchItemAction;
import me.lovkar.errands.tools.FollowPlayerAction;
import me.lovkar.errands.tools.GatherAtAction;
import me.lovkar.errands.tools.GuardMeAction;
import me.lovkar.errands.tools.LeaveConversationAction;
import me.lovkar.errands.tools.NotifyWhenAction;
import me.lovkar.errands.tools.SendMessengerAction;
import me.lovkar.errands.tools.SendToBuildingAction;
import me.lovkar.errands.tools.StopErrandAction;
import me.lovkar.errands.tools.SummonGuardsAction;
import me.lovkar.errands.tools.WaitHereAction;
import me.sshcrack.mc_talking.manager.tools.AITools;
import me.sshcrack.mc_talking.manager.tools.FunctionAction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

@Mod("colonist_errands")
public class ColonistErrands {

    public static final Logger LOGGER = LoggerFactory.getLogger("colonist_errands");

    public ColonistErrands(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, e -> e.enqueueWork(ColonistErrands::registerTools));
        NeoForge.EVENT_BUS.register(this);
        AliasStore.load();
        PromiseStore.load();
    }

    /** Lovkar's idea #30: tool -> permission group (which rank may order what, see RankGuard). */
    private static final Map<String, String> TOOL_GROUPS = Map.ofEntries(
            // chat: harmless questions, reports, promises
            Map.entry("citizen_report", RankGuard.GROUP_CHAT),
            Map.entry("colony_report", RankGuard.GROUP_CHAT),
            Map.entry("why_unhappy", RankGuard.GROUP_CHAT),
            Map.entry("research_status", RankGuard.GROUP_CHAT),
            Map.entry("find_citizen", RankGuard.GROUP_CHAT),
            Map.entry("check_stock", RankGuard.GROUP_CHAT),
            Map.entry("make_promise", RankGuard.GROUP_CHAT),
            Map.entry("resolve_promise", RankGuard.GROUP_CHAT),
            Map.entry("call_me", RankGuard.GROUP_CHAT),
            // errands: everyday orders
            Map.entry("send_to_building", RankGuard.GROUP_ERRANDS),
            Map.entry("follow_player", RankGuard.GROUP_ERRANDS),
            Map.entry("stop_errand", RankGuard.GROUP_ERRANDS),
            Map.entry("come_here", RankGuard.GROUP_ERRANDS),
            Map.entry("wait_here", RankGuard.GROUP_ERRANDS),
            Map.entry("gather_at", RankGuard.GROUP_ERRANDS),
            Map.entry("send_messenger", RankGuard.GROUP_ERRANDS),
            Map.entry("fetch_item", RankGuard.GROUP_ERRANDS),
            Map.entry("deliver_item", RankGuard.GROUP_ERRANDS),
            Map.entry("farmer_plant", RankGuard.GROUP_ERRANDS),
            Map.entry("notify_when", RankGuard.GROUP_ERRANDS),
            Map.entry("back_to_work", RankGuard.GROUP_ERRANDS),
            Map.entry("call_citizen", RankGuard.GROUP_ERRANDS),
            Map.entry("dismiss", RankGuard.GROUP_ERRANDS),
            // military: defense & alarms
            Map.entry("guard_me", RankGuard.GROUP_MILITARY),
            Map.entry("summon_guards", RankGuard.GROUP_MILITARY),
            Map.entry("defend_here", RankGuard.GROUP_MILITARY),
            Map.entry("everyone_home", RankGuard.GROUP_MILITARY),
            Map.entry("red_alert", RankGuard.GROUP_MILITARY),
            Map.entry("patrol_here", RankGuard.GROUP_MILITARY),
            // jobs: colony management
            Map.entry("take_job", RankGuard.GROUP_JOBS));
            // leave_conversation and note_player_conduct are NEVER gated.

    @SuppressWarnings("unchecked")
    private static void registerTools() {
        try {
            Field f = AITools.class.getDeclaredField("playerConversationOnlyTools");
            f.setAccessible(true);
            Map<String, FunctionAction> map = (Map<String, FunctionAction>) f.get(null);
            for (FunctionAction action : List.of(
                    new SendToBuildingAction(), new FollowPlayerAction(), new StopErrandAction(),
                    new ComeHereAction(), new WaitHereAction(), new GatherAtAction(), new EveryoneHomeAction(),
                    new GuardMeAction(), new SendMessengerAction(), new CitizenReportAction(),
                    new LeaveConversationAction(), new DismissAction(), new SummonGuardsAction(),
                    new DefendHereAction(), new CallMeAction(), new CheckStockAction(),
                    new FetchItemAction(), new FarmerPlantAction(), new NotifyWhenAction(), new BackToWorkAction(),
                    new CallCitizenAction(), new FindCitizenAction(), new ColonyReportAction(),
                    new WhyUnhappyAction(), new ResearchStatusAction(), new RedAlertAction(),
                    new TakeJobAction(), new DeliverItemAction(), new PatrolHereAction(),
                    new MakePromiseAction(), new ResolvePromiseAction(), new NotePlayerConductAction())) {
                String group = TOOL_GROUPS.get(action.getName());
                map.put(action.getName(), group == null ? action : new RankGatedAction(action, group));
            }
            LOGGER.info("[ColonistErrands] Registered tools (v2.0.0-alpha.1): 32 tools - v1.5 set plus call_citizen, find_citizen, "
                    + "colony_report, why_unhappy, research_status, red_alert, take_job, deliver_item, patrol_here, "
                    + "make_promise, resolve_promise, note_player_conduct; rank-gated per config");
        } catch (Throwable t) {
            LOGGER.error("[ColonistErrands] Failed to register AI tools - the mc_talking internals may have changed", t);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ErrandManager.tick(event.getServer());
        WatchManager.tick(event.getServer());
        RaidWatcher.tick(event.getServer());
        C2cAudioFollower.tick(event.getServer());
        FamilyChats.tick(event.getServer());
        DeathWatcher.tick(event.getServer());
        PromiseWatcher.tick(event.getServer());
        if (event.getServer().getTickCount() % 100 == 0) {
            try {
                PromiseStore.setCurrentDay(event.getServer().overworld().getDayTime() / 24000L);
            } catch (Throwable ignored) {
            }
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ErrandManager.clearAll();
        WatchManager.clearAll();
        BuilderAssist.clearAll();
        PromiseWatcher.clearAll();
        RaidWatcher.clearAll();
        DeathWatcher.clearAll();
        FamilyChats.clearAll();
    }
}
