package dev.idebugger.echoreplay;

import dev.idebugger.echoreplay.command.EchoCommand;
import dev.idebugger.echoreplay.command.WandListener;
import dev.idebugger.echoreplay.packet.PacketEventsSetup;
import dev.idebugger.echoreplay.record.RecordingManager;
import dev.idebugger.echoreplay.replay.PlaybackBorderPrefs;
import dev.idebugger.echoreplay.replay.ReplayManager;
import dev.idebugger.echoreplay.select.SelectionManager;
import dev.idebugger.echoreplay.storage.RecordingIndex;
import dev.idebugger.echoreplay.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EchoReplay main class. Holds all managers, wiring, and a central reference
 * accessible via {@link #get()}.
 */
public final class EchoReplay extends JavaPlugin {

    private static final AtomicReference<EchoReplay> INSTANCE = new AtomicReference<>();

    private final SelectionManager selectionManager = new SelectionManager();
    private final RecordingIndex recordingIndex = new RecordingIndex(new File(getDataFolder(), "recordings/index.yml"));
    private final RecordingManager recordingManager = new RecordingManager(this);
    private final ReplayManager replayManager = new ReplayManager(this);
    private PlaybackBorderPrefs borderPrefs;

    private ExecutorService ioExecutor;
    private int tickTaskId = -1;
    private com.github.retrooper.packetevents.event.PacketListenerCommon movementListener;
    private com.github.retrooper.packetevents.event.PacketListenerCommon outboundListener;
    private dev.idebugger.echoreplay.record.MovementRecorder movementRecorder;

    public static EchoReplay get() {
        return INSTANCE.get();
    }

    @Override
    public void onLoad() {
        INSTANCE.set(this);
        PacketEventsSetup.onLoad(this);
    }

    @Override
    public void onEnable() {
        PacketEventsSetup.onEnable();

        saveDefaultConfig();
        FileConfiguration config = getConfig();

        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "echoreplay-io");
            t.setDaemon(true);
            return t;
        });

        borderPrefs = new PlaybackBorderPrefs(new File(getDataFolder(), "border_prefs.yml"));
        recordingManager.onEnable(config);
        replayManager.onEnable(config);

        WandListener wandListener = new WandListener(this);
        getServer().getPluginManager().registerEvents(wandListener, this);
        recordingManager.registerListeners(this);
        replayManager.registerListeners(this);

        EchoCommand echoCommand = new EchoCommand(this);
        echoCommand.register();

        movementRecorder = new dev.idebugger.echoreplay.record.MovementRecorder(this);
        movementListener = com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(movementRecorder);
        outboundListener = com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(new dev.idebugger.echoreplay.record.PacketOutRecorder(this));

        tickTaskId = getServer().getScheduler()
                .runTaskTimer(this, this::onTick, 1L, 1L).getTaskId();

        // Warm up PacketEvents block-state mappings off the main thread. The
        // first conversion otherwise happens on the first /er play and stalls
        // the tick ~500ms ("Can't keep up" + client stutter on resume).
        ioExecutor.execute(() -> {
            try {
                org.bukkit.block.data.BlockData air =
                        getServer().createBlockData("minecraft:air");
                io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitBlockData(air);
            } catch (Exception ignored) {}
        });

        Text.broadcast(Text.mm("<gray>EchoReplay <green>enabled</green>.</gray>"));
    }

    @Override
    public void onDisable() {
        if (tickTaskId != -1) {
            getServer().getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        recordingManager.onDisable();
        replayManager.onDisable();
        if (movementListener != null) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(movementListener);
            movementListener = null;
        }
        if (outboundListener != null) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(outboundListener);
            outboundListener = null;
        }
        if (ioExecutor != null) ioExecutor.shutdown();
        PacketEventsSetup.onDisable();
        INSTANCE.set(null);
    }

    private int outlineCounter = 0;

    private void onTick() {
        recordingManager.onTick();
        replayManager.onTick();
        // Selection outline particles, throttled to every 10th tick.
        if ((++outlineCounter % 10) == 0) {
            renderSelectionOutlines();
        }
    }

    /**
     * END_ROD wireframe around each player's complete selection, only for
     * players with {@code selection.outline-particles} enabled and only
     * while they are reasonably close to it. Budget-capped per player per
     * pass so a huge selection cannot spike a tick.
     */
    private void renderSelectionOutlines() {
        if (!cfg().getBoolean("selection.outline-particles", false)) return;
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            // Personal border toggle (/er border) covers region outlines too.
            if (borderPrefs != null && !borderPrefs.isEnabled(p.getUniqueId())) continue;
            dev.idebugger.echoreplay.select.Selection sel = selectionManager.getIfExists(p);
            if (sel == null || !sel.isComplete()) continue;
            dev.idebugger.echoreplay.select.Cuboid c = sel.cuboid();
            org.bukkit.Location loc = p.getLocation();
            // Skip viewers far away: particle packets would just be dropped
            // and the loop would still cost CPU.
            double dx = Math.max(c.min().x() - loc.getBlockX(), Math.max(loc.getBlockX() - c.max().x(), 0));
            double dy = Math.max(c.min().y() - loc.getBlockY(), Math.max(loc.getBlockY() - c.max().y(), 0));
            double dz = Math.max(c.min().z() - loc.getBlockZ(), Math.max(loc.getBlockZ() - c.max().z(), 0));
            if (dx * dx + dy * dy + dz * dz > 80 * 80) continue;
            int[] budget = {512};
            org.bukkit.Particle part = org.bukkit.Particle.END_ROD;
            for (int axis = 0; axis < 3; axis++) {
                for (int ox = 0; ox < 2; ox++) {
                    for (int oz = 0; oz < 2; oz++) {
                        outlineEdge(p, part, c, axis, ox, oz, budget);
                    }
                }
            }
        }
    }

    /** One cuboid edge: the axis coordinate runs min→max, the others fixed. */
    private void outlineEdge(org.bukkit.entity.Player p, org.bukkit.Particle part,
                             dev.idebugger.echoreplay.select.Cuboid c,
                             int axis, int i, int j, int[] budget) {
        double from, to;
        double fx = 0, fy = 0, fz = 0;
        if (axis == 0) { // X runs; i fixes Y, j fixes Z
            from = c.min().x(); to = c.max().x();
            fy = i == 0 ? c.min().y() : c.max().y();
            fz = j == 0 ? c.min().z() : c.max().z();
        } else if (axis == 1) { // Y runs; i fixes X, j fixes Z
            from = c.min().y(); to = c.max().y();
            fx = i == 0 ? c.min().x() : c.max().x();
            fz = j == 0 ? c.min().z() : c.max().z();
        } else { // Z runs; i fixes X, j fixes Y
            from = c.min().z(); to = c.max().z();
            fx = i == 0 ? c.min().x() : c.max().x();
            fy = j == 0 ? c.min().y() : c.max().y();
        }
        int span = (int) Math.abs(to - from);
        int step = Math.max(1, span / 32);
        for (double t = from; t <= to + 1e-9 && budget[0] > 0; t += step) {
            double x = axis == 0 ? t : fx;
            double y = axis == 1 ? t : fy;
            double z = axis == 2 ? t : fz;
            budget[0]--;
            try {
                p.spawnParticle(part, x, y, z, 1);
            } catch (Exception ignored) {
            }
        }
    }

    public SelectionManager selectionManager() { return selectionManager; }
    public dev.idebugger.echoreplay.record.MovementRecorder movementRecorder() { return movementRecorder; }
    public RecordingIndex recordingIndex() { return recordingIndex; }
    public RecordingManager recordingManager() { return recordingManager; }
    public ReplayManager replayManager() { return replayManager; }
    public PlaybackBorderPrefs borderPrefs() { return borderPrefs; }
    public ExecutorService ioExecutor() { return ioExecutor; }

    public FileConfiguration cfg() { return getConfig(); }
}
