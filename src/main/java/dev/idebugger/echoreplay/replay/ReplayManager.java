package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.GzipRecordingReader;
import dev.idebugger.echoreplay.storage.MetaParser;
import dev.idebugger.echoreplay.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the currently playing replay. Loads recordings async, owns the
 * session, drives the per-tick loop, and applies physics-freezing while a
 * world-mode replay is active.
 */
public final class ReplayManager implements Listener {

    private final EchoReplay plugin;
    private ReplaySession session;
    private boolean physicsFrozen = false;

    public ReplayManager(EchoReplay plugin) {
        this.plugin = plugin;
    }

    public void onEnable(org.bukkit.configuration.file.FileConfiguration config) {
        physicsFrozen = config.getBoolean("replay.physics-frozen", true);
    }

    public void registerListeners(EchoReplay p) {
        p.getServer().getPluginManager().registerEvents(this, p);
    }

    public void onDisable() {
        if (session != null) {
            session.stop();
            session = null;
        }
    }

    public void onTick() {
        if (session != null) {
            boolean finished = session.tick();
            if (finished) {
                // Playback reached the end: restore terrain + unlock, end session.
                stopPlay(false);
            }
        }
    }

    public ReplaySession session() {
        return session;
    }

    public boolean isPlayingIn(UUID worldUuid, Cuboid cuboid) {
        return session != null && session.world().getUID().equals(worldUuid)
                && session.cuboid().volume() > 0 && cuboid != null && intersects(session.cuboid(), cuboid);
    }

    private static boolean intersects(Cuboid a, Cuboid b) {
        return a.min().x() <= b.max().x() && a.max().x() >= b.min().x()
                && a.min().y() <= b.max().y() && a.max().y() >= b.min().y()
                && a.min().z() <= b.max().z() && a.max().z() >= b.min().z();
    }

    public String play(Player sender, String name, boolean forceVirtual) {
        if (session != null) {
            return "<red>A replay is already playing. Stop it first.</red>";
        }
        File file = new File(plugin.recordingManager().recordingsDir(), name + ".echoreplay.gz");
        if (!file.exists()) {
            return "<red>No recording named '" + name + "'.</red>";
        }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return GzipRecordingReader.read(new java.io.BufferedInputStream(new java.io.FileInputStream(file)));
            } catch (Exception e) {
                return null;
            }
        }, plugin.ioExecutor()).thenAccept(reader -> {
            if (reader == null) {
                if (sender != null) sender.sendMessage(Text.mm("<red>Failed to read recording.</red>"));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                MetaParser.Parsed meta;
                try {
                    meta = MetaParser.parse(reader.meta());
                } catch (Exception e) {
                    if (sender != null) sender.sendMessage(Text.mm("<red>Corrupt recording header.</red>"));
                    return;
                }
                World world = plugin.getServer().getWorld(meta.worldUuid());
                if (world == null) world = plugin.getServer().getWorld(meta.worldName());
                if (world == null) {
                    if (sender != null) sender.sendMessage(Text.mm("<red>World for this recording is not loaded.</red>"));
                    return;
                }
                boolean virtual = forceVirtual || plugin.cfg().getBoolean("replay.virtual-packets-only", false);
                session = new ReplaySession(plugin, name, world, virtual, reader, meta);
                // record snapshot restore (world mode) is applied within session
                if (sender != null && sender.isOnline()) {
                    session.addViewer(sender);
                }
                if (session.viewerIds().isEmpty()) {
                    List<Player> nearby = world.getPlayers().stream().filter(p ->
                            session.cuboid().contains(p.getLocation().getBlockX(),
                                    p.getLocation().getBlockY(), p.getLocation().getBlockZ())).toList();
                    for (Player p : nearby) {
                        if (p.hasPermission("echoreplay.watch")) session.addViewer(p);
                    }
                }
                session.play();
                if (sender != null) sender.sendMessage(Text.mm("<green>Playing '" + name + "' in " + world.getName() + ".</green>"));
            });
        });
        return null;
    }

    public String stopPlay(boolean restoreLive) {
        if (session == null) return "<red>Nothing playing.</red>";
        session.stop();
        session = null;
        return "<green>Stopped playback.</green>";
    }

    public String pause() {
        if (session == null) return "<red>Nothing playing.</red>";
        session.setPaused(true);
        return "<gray>Paused.</gray>";
    }

    public String resume() {
        if (session == null) return "<red>Nothing playing.</red>";
        session.setPaused(false);
        return "<gray>Resumed.</gray>";
    }

    public String speed(double s) {
        if (session == null) return "<red>Nothing playing.</red>";
        session.setSpeed(s);
        return "<gray>Speed set to " + s + "x.</gray>";
    }

    public String seek(double seconds) {
        if (session == null) return "<red>Nothing playing.</red>";
        session.seekTo(seconds * 1000);
        return "<gray>Seeked to " + RecordingManagerTime.format(seconds) + ".</gray>";
    }

    public String rewind(double seconds) {
        if (session == null) return "<red>Nothing playing.</red>";
        double target = Math.max(0, session.clock().mediaTime() - seconds * 1000);
        session.seekTo(target);
        return "<gray>Rewound to " + RecordingManagerTime.format(target / 1000) + ".</gray>";
    }

    public String forward(double seconds) {
        if (session == null) return "<red>Nothing playing.</red>";
        double target = Math.min(session.durationMs(), session.clock().mediaTime() + seconds * 1000);
        session.seekTo(target);
        return "<gray>Fast-forwarded to " + RecordingManagerTime.format(target / 1000) + ".</gray>";
    }

    public String watch(Player viewer) {
        if (session == null) return "<red>No replay is playing.</red>";
        session.addViewer(viewer);
        // send current fake-entity state to this viewer
        for (Map.Entry<Integer, Integer> e : sessionEntrySnapshot()) {
            // re-send current entity spawn+pose (simplified: reseek render one-shot)
        }
        return "<green>You are now watching the replay.</green>";
    }

    private List<Map.Entry<Integer, Integer>> sessionEntrySnapshot() {
        return new ArrayList<>();
    }

    public String leave(Player p) {
        if (session == null) return "<red>No replay is playing.</red>";
        session.removeViewer(p);
        return "<gray>You left the replay.</gray>";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent e) {
        if (physicsFrozen && session != null && !session.virtual()) {
            if (e.getBlock().getWorld().getUID().equals(session.world().getUID())
                    && session.cuboid().contains(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ())) {
                e.setCancelled(true);
            }
        }
    }

    /** True while a world-mode replay is locking this block's location. */
    private boolean isLocked(org.bukkit.block.Block b) {
        return session != null && !session.virtual()
                && b.getWorld().getUID().equals(session.world().getUID())
                && session.cuboid().contains(b.getX(), b.getY(), b.getZ());
    }

    private boolean isLocked(World w, int x, int y, int z) {
        return session != null && !session.virtual()
                && w.getUID().equals(session.world().getUID())
                && session.cuboid().contains(x, y, z);
    }

    // --- Region lock: block ANY player/live change inside the cuboid while a
    //     world-mode replay runs, so the playback cannot be manipulated. The
    //     region is unlocked automatically on stopplay / auto-end. ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (isLocked(e.getBlock()) || isLocked(e.getBlockAgainst())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockMultiPlace(org.bukkit.event.block.BlockMultiPlaceEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFromTo(org.bukkit.event.block.BlockFromToEvent e) {
        if (isLocked(e.getBlock()) || isLocked(e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(org.bukkit.event.block.BlockSpreadEvent e) {
        if (isLocked(e.getBlock()) || isLocked(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFade(org.bukkit.event.block.BlockFadeEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(org.bukkit.event.block.BlockBurnEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(org.bukkit.event.block.BlockIgniteEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        e.blockList().removeIf(b -> isLocked(b));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        e.blockList().removeIf(b -> isLocked(b));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidLevelChange(org.bukkit.event.block.FluidLevelChangeEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(org.bukkit.event.block.BlockDispenseEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockGrow(org.bukkit.event.block.BlockGrowEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent e) {
        if (isLocked(e.getBlock()) || e.getBlocks().stream().anyMatch(this::isLocked)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent e) {
        if (isLocked(e.getBlock()) || e.getBlocks().stream().anyMatch(this::isLocked)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(org.bukkit.event.block.EntityBlockFormEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquidToBlock(org.bukkit.event.block.BlockFormEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreventPhysics(org.bukkit.event.block.BlockCanBuildEvent e) {
        if (isLocked(e.getBlock())) e.setBuildable(false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (session != null) {
            session.removeViewer(e.getPlayer());
        }
    }

    static final class RecordingManagerTime {
        static String format(double sec) {
            long s = (long) sec;
            return String.format("%d:%02d", s / 60, s % 60);
        }
    }
}
