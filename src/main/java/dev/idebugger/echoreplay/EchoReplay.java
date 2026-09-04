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

    public static EchoReplay get() {
        return INSTANCE.get();
    }

    @Override
    public void onLoad() {
        INSTANCE.set(this);
        PacketEventsSetup.onLoad(this);
    }

    /**
     * Current state of the IO executor for /er stats. NEVER exposes the
     * ExecutorService itself outside this class — external code submits
     * via {@link #ioExecutor()} only.
     */
    public String ioExecutorStatus() {
        if (ioExecutor == null) return "uninitialized";
        if (ioExecutor.isShutdown()) return "shutdown";
        if (ioExecutor.isTerminated()) return "terminated";
        return "running";
    }

    @Override
    public void onEnable() {
        PacketEventsSetup.onEnable();

        saveDefaultConfig();
        // Config-version aware migration: log unknown keys so users stop
        // editing values that do nothing (D-2). Reload-safe.
        ConfigMigrator.warnUnknownKeys(this);
        ConfigMigrator.migrate(this);

        FileConfiguration config = getConfig();

        // S-6: non-daemon thread so the JVM cannot exit mid-flush during
        // shutdown. Combined with awaitTermination in onDisable(), this
        // guarantees a recording's final gzip write actually reaches disk.
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "echoreplay-io");
            t.setDaemon(false);
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

        movementListener = com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(new dev.idebugger.echoreplay.record.MovementRecorder(this));
        outboundListener = com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(new dev.idebugger.echoreplay.record.PacketOutRecorder(this));

        tickTaskId = getServer().getScheduler()
                .runTaskTimer(this, this::onTick, 1L, 1L).getTaskId();

        // D-8.6: do NOT broadcast plugin enable to every online player —
        // players don't care and it trains people to ignore plugin chat.
        // Console-only log keeps ops aware without spamming players.
        getLogger().info("EchoReplay " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (tickTaskId != -1) {
            getServer().getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        recordingManager.onDisable();   // schedules the final IO write
        replayManager.onDisable();
        if (movementListener != null) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(movementListener);
            movementListener = null;
        }
        if (outboundListener != null) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(outboundListener);
            outboundListener = null;
        }
        // S-6: wait for in-flight IO work to actually finish before we let
        // PacketEvents / Bukkit tear down. v1 returned immediately and the
        // daemon thread was killed mid-gzip, corrupting the just-saved file.
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            try {
                if (!ioExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    getLogger().warning("IO executor did not finish within 10s — "
                            + "the last recording may be incomplete or missing.");
                    ioExecutor.shutdownNow();
                    // give it one more second to release file handles
                    ioExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                ioExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            ioExecutor = null;
        }
        PacketEventsSetup.onDisable();
        INSTANCE.set(null);
    }

    private void onTick() {
        recordingManager.onTick();
        replayManager.onTick();
    }

    public SelectionManager selectionManager() { return selectionManager; }
    public RecordingIndex recordingIndex() { return recordingIndex; }
    public RecordingManager recordingManager() { return recordingManager; }
    public ReplayManager replayManager() { return replayManager; }
    public PlaybackBorderPrefs borderPrefs() { return borderPrefs; }
    public ExecutorService ioExecutor() { return ioExecutor; }

    public FileConfiguration cfg() { return getConfig(); }
}
