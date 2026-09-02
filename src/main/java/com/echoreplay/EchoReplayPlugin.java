package com.echoreplay;

import com.echoreplay.command.EchoCommand;
import com.echoreplay.command.WandListener;
import com.echoreplay.packet.PacketEventsSetup;
import com.echoreplay.record.RecordingManager;
import com.echoreplay.replay.ReplayManager;
import com.echoreplay.select.SelectionManager;
import com.echoreplay.storage.RecordingIndex;
import com.echoreplay.util.Text;
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
public final class EchoReplayPlugin extends JavaPlugin {

    private static final AtomicReference<EchoReplayPlugin> INSTANCE = new AtomicReference<>();

    private final SelectionManager selectionManager = new SelectionManager();
    private final RecordingIndex recordingIndex = new RecordingIndex(new File(getDataFolder(), "recordings/index.yml"));
    private final RecordingManager recordingManager = new RecordingManager(this);
    private final ReplayManager replayManager = new ReplayManager(this);

    private ExecutorService ioExecutor;
    private int tickTaskId = -1;
    private com.github.retrooper.packetevents.event.PacketListenerCommon movementListener;

    public static EchoReplayPlugin get() {
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

        recordingManager.onEnable(config);
        replayManager.onEnable(config);

        WandListener wandListener = new WandListener(this);
        getServer().getPluginManager().registerEvents(wandListener, this);
        recordingManager.registerListeners(this);
        replayManager.registerListeners(this);

        EchoCommand echoCommand = new EchoCommand(this);
        echoCommand.register();

        movementListener = com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                .registerListener(new com.echoreplay.record.MovementRecorder(this));

        tickTaskId = getServer().getScheduler()
                .runTaskTimer(this, this::onTick, 1L, 1L).getTaskId();

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
        ioExecutor.shutdown();
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
    public ExecutorService ioExecutor() { return ioExecutor; }

    public FileConfiguration cfg() { return getConfig(); }
}
