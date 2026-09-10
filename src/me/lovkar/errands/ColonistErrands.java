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
import me.lovkar.errands.tools.CourierBoardAction;
import me.lovkar.errands.tools.GuardGearAction;
import me.lovkar.errands.tools.MintCoinsAction;
import me.lovkar.errands.tools.ArmGuardsAction;
import me.lovkar.errands.tools.BuildStatusAction;
import me.lovkar.errands.tools.PrioritizeAction;
import me.lovkar.errands.tools.RememberFallenAction;
import me.lovkar.errands.tools.RequestCraftAction;
import me.lovkar.errands.tools.TradeStatusAction;
import me.lovkar.errands.tools.CitizenReportAction;
import me.lovkar.errands.tools.ComeHereAction;
import me.lovkar.errands.tools.DefendHereAction;
import me.lovkar.errands.tools.DismissAction;
import me.lovkar.errands.tools.EveryoneHomeAction;
import me.lovkar.errands.tools.FarmerPlantAction;
import me.lovkar.errands.tools.FetchItemAction;
import me.lovkar.errands.tools.FollowPlayerAction;
import me.lovkar.errands.tools.GatherAtAction;
import me.lovkar.errands.tools.GuardLeaderboardAction;
import me.lovkar.errands.tools.GuardMeAction;
import me.lovkar.errands.tools.LeaveConversationAction;
import me.lovkar.errands.tools.NotifyWhenAction;
import me.lovkar.errands.tools.SendMessengerAction;
import me.lovkar.errands.tools.SendToBuildingAction;
import me.lovkar.errands.tools.StopErrandAction;
import me.lovkar.errands.tools.SummonGuardsAction;
import me.lovkar.errands.tools.WaitHereAction;
import me.lovkar.errands.tc.ErrandTool;
import me.lovkar.errands.tc.PairChats;
import me.lovkar.errands.tc.Talk;
import me.lovkar.errands.tc.TcBridge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.List;

@Mod("colonist_errands")
public class ColonistErrands {

    public static final Logger LOGGER = LoggerFactory.getLogger("colonist_errands");

    public ColonistErrands(IEventBus modEventBus) {
        modEventBus.addListener(FMLCommonSetupEvent.class, e -> e.enqueueWork(ColonistErrands::registerTools));
        NeoForge.EVENT_BUS.register(this);
        AliasStore.load();
        PromiseStore.load();
    }

    /**
     * Every Errands tool, registered with Talking Colonists' addon API. Which rank may order what
     * (Lovkar's idea #30) travels with each tool now - see the group each constructor names and
     * RankGuard for the configurable minimum ranks. leave_conversation and note_player_conduct are
     * never gated.
     */
    private static void registerTools() {
        final List<ErrandTool> tools = List.of(
                new SendToBuildingAction(), new FollowPlayerAction(), new StopErrandAction(),
                new ComeHereAction(), new WaitHereAction(), new GatherAtAction(), new EveryoneHomeAction(),
                new GuardMeAction(), new SendMessengerAction(), new CitizenReportAction(),
                new LeaveConversationAction(), new DismissAction(), new SummonGuardsAction(),
                new DefendHereAction(), new CallMeAction(), new CheckStockAction(),
                new FetchItemAction(), new FarmerPlantAction(), new NotifyWhenAction(), new BackToWorkAction(),
                new CallCitizenAction(), new FindCitizenAction(), new ColonyReportAction(),
                new WhyUnhappyAction(), new ResearchStatusAction(), new RedAlertAction(),
                new TakeJobAction(), new DeliverItemAction(), new PatrolHereAction(),
                new MakePromiseAction(), new ResolvePromiseAction(), new NotePlayerConductAction(),
                new GuardLeaderboardAction(), new RequestCraftAction(), new CourierBoardAction(),
                new GuardGearAction(), new TradeStatusAction(), new MintCoinsAction(),
                new RememberFallenAction(), new ArmGuardsAction(), new PrioritizeAction(),
                new BuildStatusAction());
        final int registered = TcBridge.register(tools);
        if (registered < 0) {
            return;
        }
        LOGGER.info("[ColonistErrands] Registered {} of {} tools with Talking Colonists (v3.0.0-alpha.1, addon API {}); "
                + "rank-gated per config. Voyager mod {}", registered, tools.size(),
                me.sshcrack.mc_talking.api.TalkingColonistsApi.API_MAJOR_VERSION,
                VoyagerCompat.isLoaded() ? "detected - Voyager, astronomer and photographer lore enabled" : "not installed");
    }

    @SubscribeEvent
    public void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        ErrandsCommands.register(event);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ErrandManager.tick(event.getServer());
        WatchManager.tick(event.getServer());
        RaidWatcher.tick(event.getServer());
        PairChats.tick(event.getServer());
        FamilyChats.tick(event.getServer());
        ShopChats.tick(event.getServer());
        GroupChats.tick(event.getServer());
        VoyagerLore.tick(event.getServer());
        VoyagerChats.tick(event.getServer());
        DeathWatcher.tick(event.getServer());
        PromiseWatcher.tick(event.getServer());
        FetchQueue.tick(event.getServer());
        CraftWatch.tick(event.getServer());
        Fallen.tick(event.getServer());
        ResearchWatcher.tick(event.getServer());
        BuildWatch.tick(event.getServer());
        BedCheck.tick(event.getServer());
        ConstructionWatcher.tick(event.getServer());
        RestartNudge.tick(event.getServer());
        GuardScore.tick(event.getServer());
        if (event.getServer().getTickCount() % 100 == 0) {
            try {
                PromiseStore.setCurrentDay(event.getServer().overworld().getDayTime() / 24000L);
            } catch (Throwable ignored) {
            }
        }
    }

    @SubscribeEvent
    public void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        try {
            if (!event.getEntity().level().isClientSide()) {
                GuardScore.onKill(event.getEntity(), event.getSource().getEntity());
                Fallen.onDeath(event.getEntity(), event.getSource(), event.getEntity().getServer());
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onLivingDamaged(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        try {
            if (!event.getEntity().level().isClientSide()) {
                GuardScore.onDamaged(event.getEntity(), event.getNewDamage());
                GuardScore.onDamageDealt(event.getEntity(), event.getSource().getEntity(), event.getNewDamage());
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ErrandManager.clearAll();
        WatchManager.clearAll();
        BuilderAssist.clearAll();
        PromiseWatcher.clearAll();
        FoodCheck.clearAll();
        SupplyCheck.clearAll();
        FetchQueue.clearAll();
        CraftWatch.clearAll();
        Fallen.clearAll();
        ResearchWatcher.clearAll();
        HomeCheck.clearAll();
        HospitalCheck.clearAll();
        BuildWatch.clearAll();
        BedCheck.clearAll();
        ConstructionWatcher.clearAll();
        RestartNudge.clearAll();
        GuardScore.saveNow();
        RaidWatcher.clearAll();
        DeathWatcher.clearAll();
        FamilyChats.clearAll();
        ShopChats.clearAll();
        GroupChats.clearAll();
        VoyagerLore.clearAll();
        VoyagerChats.clearAll();
        PairChats.clearAll();
        Talk.releaseAll();
    }
}
