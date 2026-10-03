package me.lovkar.errands.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.BedHandlingModule;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless probe for the evening bed check of Colonist Errands (3.0.0-beta.3).
 * <p>
 * The question: on a server nobody is standing on, does the bed check load the chunks of a colony
 * that is not in memory? A real server answered yes - MineColonies' cavalry-horse debug logger
 * printed "CavHorse left level" every twenty minutes, once per game evening - so this builds the
 * same situation on a flat world and counts the chunk loads:
 * <ol>
 *   <li>a colony far from the world spawn, with a Residence whose registered beds stand in four
 *       other chunks (three real beds and one entry that points at nothing),</li>
 *   <li>wait until every one of those chunks has dropped out of memory,</li>
 *   <li>make it dusk and count what loads - "UNLOADED dusk",</li>
 *   <li>force-load the chunks again, make it dusk on the next day - "LOADED dusk" - and check that
 *       the check still does its job when somebody IS there (the dead entry must be dropped).</li>
 * </ol>
 * The server halts itself at the end. Every line it writes starts with {@code [bedchurn]}.
 */
@Mod("bedchurn")
public class BedChurn {
    private static final Logger LOG = LoggerFactory.getLogger("bedchurn");
    private static final int X0 = 3000;
    private static final int Z0 = 3000;
    private static final int WINDOW = 420;   // ticks to watch after dusk; the check runs on a 200-tick beat

    private int phase = 0;
    private long phaseTick = 0;
    private int calm = 0;
    private ServerLevel level;
    private IColony colony;
    private IBuilding home;
    private BlockPos center;
    private final Map<String, BlockPos> sites = new LinkedHashMap<>();
    private final List<ChunkPos> watched = new ArrayList<>();

    private boolean window;
    private int loadsWatched;
    private int loadsOther;
    private int unloadsWatched;
    private final List<String> culprits = new ArrayList<>();

    public BedChurn(final IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onLoad);
        NeoForge.EVENT_BUS.addListener(this::onUnload);
    }

    private void onLoad(final ChunkEvent.Load e) {
        if (level == null || e.getLevel() != level) {
            return;
        }
        final ChunkPos p = e.getChunk().getPos();
        final boolean mine = watched.contains(p);
        LOG.info("[bedchurn] event phase={} tick={} chunk-LOAD {},{} {}", phase, level.getServer().getTickCount(),
                p.x, p.z, mine ? "(colony)" : "(other)");
        if (window) {
            if (mine) {
                loadsWatched++;
                if (culprits.size() < 3) {
                    culprits.add(who());
                }
            } else {
                loadsOther++;
            }
        }
    }

    private void onUnload(final ChunkEvent.Unload e) {
        if (level == null || e.getLevel() != level) {
            return;
        }
        final ChunkPos p = e.getChunk().getPos();
        final boolean mine = watched.contains(p);
        LOG.info("[bedchurn] event phase={} tick={} chunk-UNLOAD {},{} {}", phase, level.getServer().getTickCount(),
                p.x, p.z, mine ? "(colony)" : "(other)");
        if (window && mine) {
            unloadsWatched++;
        }
    }

    /** The first mod frames on the stack that is loading the chunk: who asked for it. */
    private static String who() {
        final StringBuilder sb = new StringBuilder();
        int n = 0;
        for (final StackTraceElement f : new Throwable().getStackTrace()) {
            final String c = f.getClassName();
            final boolean ours = c.startsWith("me.lovkar.") && !c.startsWith("me.lovkar.errands.test.");
            if (ours || c.startsWith("com.minecolonies.") || c.startsWith("com.ldtteam.") || c.startsWith("com.deathfrog.")) {
                sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(f.getMethodName())
                        .append(':').append(f.getLineNumber()).append(' ');
                if (++n >= 3) {
                    break;
                }
            }
        }
        return n == 0 ? "(no mod frame on the stack)" : sb.toString().trim();
    }

    private void onTick(final ServerTickEvent.Post e) {
        final MinecraftServer server = e.getServer();
        final long t = server.getTickCount();
        try {
            if (phase == 0) {
                if (t >= 300) {
                    setUp(server.overworld(), t);
                }
                return;
            }
            switch (phase) {
                case 1 -> {
                    // wait until the whole colony has dropped out of memory
                    if (t % 20 != 0) {
                        return;
                    }
                    calm = allChunks(false) ? calm + 1 : 0;
                    if (calm >= 5 || t - phaseTick > 3600) {
                        LOG.info("[bedchurn] settle: colony out of memory={} after {} ticks; still loaded: {}",
                                calm >= 5, t - phaseTick, stillLoaded(false));
                        startWindow(11500L, t, 2);
                    }
                }
                case 2 -> {
                    if (t - phaseTick >= WINDOW) {
                        report("UNLOADED dusk", server);
                        // somebody arrives: hold the whole colony in memory
                        for (final ChunkPos p : watched) {
                            level.setChunkForced(p.x, p.z, true);
                        }
                        phase = 3;
                        phaseTick = t;
                        calm = 0;
                    }
                }
                case 3 -> {
                    if (t % 20 != 0) {
                        return;
                    }
                    calm = allChunks(true) ? calm + 1 : 0;
                    if (calm >= 3 || t - phaseTick > 1200) {
                        LOG.info("[bedchurn] settle: colony in memory={} after {} ticks", calm >= 3, t - phaseTick);
                        startWindow(24000L + 11500L, t, 4);
                    }
                }
                case 4 -> {
                    if (t - phaseTick >= WINDOW) {
                        report("LOADED dusk", server);
                        LOG.info("[bedchurn] DONE");
                        phase = 5;
                        server.halt(false);
                    }
                }
                default -> {
                }
            }
        } catch (final Throwable ex) {
            LOG.error("[bedchurn] FAILED at tick {} phase {}", t, phase, ex);
            phase = 5;
            server.halt(false);
        }
    }

    private void startWindow(final long dayTime, final long t, final int nextPhase) {
        loadsWatched = 0;
        loadsOther = 0;
        unloadsWatched = 0;
        culprits.clear();
        window = true;
        level.setDayTime(dayTime);
        phase = nextPhase;
        phaseTick = t;
        LOG.info("[bedchurn] dusk: dayTime set to {} at tick {} - watching {} ticks", dayTime, t, WINDOW);
    }

    private void report(final String what, final MinecraftServer server) {
        window = false;
        int regs = -1;
        try {
            final BedHandlingModule bm = home.getModule(BuildingModules.BED);
            regs = bm.getRegisteredBlocks().size();
        } catch (final Throwable ignored) {
        }
        LOG.info("[bedchurn] RESULT {}: colony chunk loads={} (unloads={}), other chunk loads={}, registered beds={} of {}, "
                + "loaded by: {}", what, loadsWatched, unloadsWatched, loadsOther, regs, sites.size(),
                culprits.isEmpty() ? "nobody" : String.join(" | ", culprits));
    }

    private boolean allChunks(final boolean wantLoaded) {
        return stillLoaded(wantLoaded).isEmpty();
    }

    /** The watched chunks that are NOT in the wanted state. */
    private List<String> stillLoaded(final boolean wantLoaded) {
        final List<String> out = new ArrayList<>();
        for (final ChunkPos p : watched) {
            final boolean in = level.getChunkSource().hasChunk(p.x, p.z);
            if (in != wantLoaded) {
                out.add(p.x + "," + p.z);
            }
        }
        return out;
    }

    private void watch(final BlockPos pos) {
        final ChunkPos p = new ChunkPos(pos);
        if (!watched.contains(p)) {
            watched.add(p);
        }
    }

    private void setUp(final ServerLevel lvl, final long t) {
        level = lvl;
        phase = 1;
        phaseTick = t;
        final MinecraftServer server = lvl.getServer();
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        lvl.setDayTime(1000L);
        LOG.info("[bedchurn] Colonist Errands version: {}", ModList.get().getModContainerById("colonist_errands")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("NOT LOADED"));
        LOG.info("[bedchurn] MineColonies version: {}", ModList.get().getModContainerById("minecolonies")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("NOT LOADED"));

        final int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, X0, Z0);
        center = new BlockPos(X0, y, Z0);
        final Player owner = FakePlayerFactory.getMinecraft(lvl);
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(lvl, center, owner, "Bed Churn", "Medieval Oak");
        LOG.info("[bedchurn] colony {} created at {}", colony.getID(), center);
        watch(center);

        final Block townHall = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall"));
        lvl.setBlock(center, townHall.defaultBlockState(), 3);
        if (lvl.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding hut) {
            colony.getServerBuildingManager().addNewBuilding(hut, lvl);
        }

        final BlockPos homePos = center.offset(10, 0, 0);
        final Block residence = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhutcitizen"));
        lvl.setBlock(homePos, residence.defaultBlockState(), 3);
        final BlockEntity hte = lvl.getBlockEntity(homePos);
        if (hte instanceof AbstractTileEntityColonyBuilding hut) {
            home = colony.getServerBuildingManager().addNewBuilding(hut, lvl);
        }
        if (home == null) {
            throw new IllegalStateException("the Residence did not register");
        }
        watch(homePos);
        LOG.info("[bedchurn] residence {} at {}, has bed module: {}", home, homePos, home.hasModule(BuildingModules.BED));

        final BedHandlingModule beds = home.getModule(BuildingModules.BED);
        final int[][] offs = {{48, 0}, {-48, 0}, {0, 48}, {0, -48}};
        final String[] names = {"east", "west", "south", "north-ghost"};
        for (int i = 0; i < offs.length; i++) {
            final int bx = X0 + offs[i][0];
            final int bz = Z0 + offs[i][1];
            final int by = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            final BlockPos foot = new BlockPos(bx, by, bz);
            final BlockPos head = foot.relative(Direction.EAST);
            final BlockState fs = Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
            final BlockState hs = fs.setValue(BedBlock.PART, BedPart.HEAD);
            lvl.setBlock(foot, fs, 2);
            lvl.setBlock(head, hs, 2);
            beds.onBlockPlacedInBuilding(hs, head, lvl);
            if (i == offs.length - 1) {
                // the registered entry stays, the bed is gone: an entry that points at nothing
                lvl.setBlock(head, Blocks.AIR.defaultBlockState(), 2);
                lvl.setBlock(foot, Blocks.AIR.defaultBlockState(), 2);
            }
            sites.put(names[i], head);
            watch(head);
        }
        home.markDirty();
        LOG.info("[bedchurn] registered beds: {} ; watching {} chunks: {}", beds.getRegisteredBlocks(), watched.size(),
                watched);
    }
}
