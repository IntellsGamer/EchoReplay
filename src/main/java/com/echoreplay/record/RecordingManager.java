package com.echoreplay.record;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.BlockPos;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.select.Cuboid;
import com.echoreplay.storage.GzipRecordingWriter;
import com.echoreplay.storage.RecordingEntry;
import com.echoreplay.storage.TimelineCodec;
import com.echoreplay.util.NbtBytes;
import com.echoreplay.util.PalettedStorage;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manages the single active recording: budgeted snapshotting, live event
 * buffering, and async gzip finalization on stop.
 */
public final class RecordingManager {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-]{1,32}");

    private final EchoReplayPlugin plugin;
    private RecordingSession session;
    private File recordingsDir;
    private int marginBlocks = 8;
    private int flushSeconds = 15;
    private int blocksPerTick = 8000;
    private int maxDurationMinutes = 30;

    private Cuboid.Section[] pendingSections;
    private int pendingIndex = 0;
    private long flushCounterMs = 0;
    private final EquipmentRecorder equipmentRecorder;

    public RecordingManager(EchoReplayPlugin plugin) {
        this.plugin = plugin;
        this.equipmentRecorder = new EquipmentRecorder(plugin);
    }

    public void onEnable(FileConfiguration config) {
        marginBlocks = config.getInt("recording.margin-blocks", 8);
        flushSeconds = config.getInt("recording.flush-seconds", 15);
        blocksPerTick = config.getInt("snapshot.blocks-per-tick", 8000);
        maxDurationMinutes = config.getInt("recording.max-duration-minutes", 30);
        recordingsDir = new File(plugin.getDataFolder(), config.getString("storage.directory", "recordings"));
        recordingsDir.mkdirs();
    }

    public void registerListeners(EchoReplayPlugin p) {
        p.getServer().getPluginManager().registerEvents(new WorldRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ConnectionRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new EntityRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ActionRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ChatRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(equipmentRecorder, p);
    }

    public void onDisable() {
        if (session != null) {
            forceStopAndSave();
        }
    }

    public RecordingSession activeSession() {
        return session;
    }

    public File recordingsDir() { return recordingsDir; }

    public void onTick() {
        if (session == null) return;
        switch (session.state()) {
            case SNAPSHOTTING -> tickSnapshot();
            case RECORDING -> tickRecording();
            default -> {}
        }
    }

    private void tickSnapshot() {
        if (pendingSections == null || pendingIndex >= pendingSections.length) {
            finishSnapshotAndRecord();
            return;
        }
        int budget = blocksPerTick;
        int count = 0;
        while (pendingIndex < pendingSections.length && count < budget) {
            Cuboid.Section sec = pendingSections[pendingIndex];
            captureSection(sec);
            pendingIndex++;
            session.markSection();
            count += (int) sec.volume();
        }
    }

    private void captureSection(Cuboid.Section sec) {
        World world = session.world();
        Cuboid c = session.cuboid();
        PalettedStorage storage = session.snapshotStorage();
        for (int x = sec.x0(); x <= sec.x1(); x++) {
            for (int y = sec.y0(); y <= sec.y1(); y++) {
                for (int z = sec.z0(); z <= sec.z1(); z++) {
                    int dx = x - c.min().x();
                    int dy = y - c.min().y();
                    int dz = z - c.min().z();
                    Block block = world.getBlockAt(x, y, z);
                    String state = block.getBlockData() == null ? "minecraft:air" : block.getBlockData().getAsString(true);
                    int pi = session.paletteIndex(state);
                    storage.set(dx, dy, dz, pi);
                    BlockState bs = block.getState();
                    if (bs != null && Snapshotter.needsNbt(bs.getType())) {
                        byte[] nb = NbtBytes.serializeBlockState(bs);
                        if (nb != null && nb.length > 0) {
                            session.putSnapshotNbt(BlockPos.of(dx, dy, dz), nb);
                        }
                    }
                }
            }
        }
    }

    private void finishSnapshotAndRecord() {
        session.setRecording();
    }

    private void tickRecording() {
        session.advanceClock(50);
        equipmentRecorder.tick();
        flushCounterMs += 50;
        if (flushCounterMs >= flushSeconds * 1000L) {
            flushCounterMs = 0;
            autoFlush();
        }
        if (session.mediaMillis() / 1000 > maxDurationMinutes * 60L) {
            forceStopAndSave();
        }
    }

    private void autoFlush() {
        // Events are buffered in the EventSink; actual persistence occurs on stop.
        // A future version may stream to a sidecar here for crash-safety.
    }

    public String start(Player player, String name) {
        if (session != null) {
            return "<red>Already recording '" + session.name() + "'. Use /er stop first.</red>";
        }
        if (!NAME.matcher(name).matches()) {
            return "<red>Name must match [a-zA-Z0-9_\\-]{1,32}.</red>";
        }
        var selection = plugin.selectionManager().get(player);
        if (!selection.isComplete()) {
            return "<red>You need a complete selection (pos1 + pos2) first.</red>";
        }
        if (selection.world() == null || !selection.world().getUID().equals(player.getWorld().getUID())) {
            return "<red>Selection must be in your current world.</red>";
        }
        Cuboid cuboid = selection.cuboid();
        long volume = cuboid.volume();
        int maxVolume = plugin.cfg().getInt("selection.max-volume", 300000);
        int maxSpan = plugin.cfg().getInt("selection.max-horizontal-span", 256);
        boolean bypass = player.hasPermission("echoreplay.bypass-limits");
        if (!bypass && volume > maxVolume) {
            return "<red>Selection volume " + volume + " exceeds limit " + maxVolume + ".</red>";
        }
        if (!bypass && (cuboid.xSize() > maxSpan || cuboid.zSize() > maxSpan)) {
            return "<red>Horizontal span exceeds " + maxSpan + ".</red>";
        }
        File out = new File(recordingsDir, name + ".echoreplay.gz");
        if (out.exists()) {
            return "<red>A recording named '" + name + "' already exists.</red>";
        }
        if (plugin.replayManager().isPlayingIn(player.getWorld().getUID(), cuboid)) {
            return "<red>A replay is active in this region — stop it first.</red>";
        }

        session = new RecordingSession(player.getWorld(), cuboid, name, player.getUniqueId(), player.getName());
        session.setTotalSections(cuboid.sections().size());
        pendingSections = cuboid.sections().toArray(new Cuboid.Section[0]);
        pendingIndex = 0;
        return null;
    }

    public String stop() {
        if (session == null) return "<red>Nothing is recording.</red>";
        return finalizeAndSave();
    }

    public String cancel() {
        if (session == null) return "<red>Nothing is recording.</red>";
        RecordingSession s = session;
        s.setCancelled();
        s.sink().close();
        session = null;
        return "<green>Cancelled recording '" + s.name() + "'.</green>";
    }

    public String forceStopAndSave() {
        if (session == null) return null;
        return finalizeAndSave();
    }

    private String finalizeAndSave() {
        RecordingSession s = session;
        if (s == null) return "<red>Nothing recording.</red>";
        s.setFinalizing();
        long duration = s.mediaMillis();
        List<String> palette = s.snapshotPalette();
        PalettedStorage storage = s.snapshotStorage();
        Map<String, byte[]> snapNbt = s.snapshotNbt();
        List<TimelineEvent> events = drainEvents(s);
        Cuboid c = s.cuboid();

        session = null;

        final long fd = duration;
        final List<String> fpal = palette;
        final PalettedStorage fs = storage;
        final Map<String, byte[]> fnbt = snapNbt;
        final List<TimelineEvent> fev = events;
        final Cuboid fc = c;
        final long fsTime = s.startedMillis();
        final String fname = s.name();
        final String fworldName = s.world().getName();
        final java.util.UUID fworldUuid = s.world().getUID();
        final UUID frecorderUuid = s.recorderUuid();
        final String frecorderName = s.recorderName();

        plugin.ioExecutor().execute(() -> {
            try {
                writeFile(fname, fworldUuid, fworldName, fc, fd, fsTime,
                        frecorderUuid, frecorderName, fpal, fs, fnbt, fev);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to write recording '" + fname + "': " + e.getMessage());
            }
        });
        return "<green>Saved recording '" + fname + "' (" + RecordingManager.formatDuration(fd) + ").</green>";
    }

    private List<TimelineEvent> drainEvents(RecordingSession s) {
        s.sink().close();
        List<TimelineEvent> out = new ArrayList<>();
        TimelineEvent e;
        while ((e = s.sink().poll()) != null) {
            out.add(e);
        }
        out.sort(java.util.Comparator.comparingLong(TimelineEvent::tickMillis));
        return out;
    }

    private void writeFile(String name, java.util.UUID worldUuid, String worldName, Cuboid c, long duration,
                           long epoch, UUID recUuid, String recName, List<String> palette,
                           PalettedStorage storage, Map<String, byte[]> snapNbt, List<TimelineEvent> events)
            throws IOException {
        File partial = new File(recordingsDir, name + ".echoreplay.gz.partial");
        File out = new File(recordingsDir, name + ".echoreplay.gz");
        try (GzipRecordingWriter w = new GzipRecordingWriter(new BufferedOutputStream(new FileOutputStream(partial)))) {
            w.writeMeta(plugin.getServer().getMinecraftVersion(), worldUuid, worldName,
                    c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z(),
                    epoch, recUuid, recName, duration, name);
            w.writePalette(palette);
            w.writeBlocks(storage.sizeX(), storage.sizeY(), storage.sizeZ(), storage.raw(),
                    bitsFor(palette.size()));
            w.writeBlockNbt(packSnapNbt(storage, snapNbt));
            w.writeEntities(new byte[0]);
            for (TimelineEvent ev : events) {
                byte[] body = TimelineCodec.encodeBody(ev, palette);
                w.appendTimelineEvent(ev.tickMillis(), body, (byte) TimelineCodec.typeId(ev));
            }
        }
        if (!partial.renameTo(out)) {
            out.delete();
            if (!partial.renameTo(out)) {
                throw new IOException("could not move partial to final (cross-device?) — copying");
                // fallback copy
            }
        }
        if (!out.exists()) {
            // rename cross-device fallback
            java.nio.file.Files.copy(partial.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            partial.delete();
        }
        plugin.recordingIndex().put(new RecordingEntry(name, worldUuid, worldName, duration, out.length(),
                epoch, c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z()));
    }

    private byte[] packSnapNbt(PalettedStorage storage, Map<String, byte[]> snapNbt) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bos)) {
            out.writeInt(storage.sizeX());
            out.writeInt(storage.sizeY());
            out.writeInt(storage.sizeZ());
            out.writeInt(snapNbt.size());
            for (Map.Entry<String, byte[]> e : snapNbt.entrySet()) {
                String[] parts = e.getKey().split(",");
                out.writeInt(Integer.parseInt(parts[0]));
                out.writeInt(Integer.parseInt(parts[1]));
                out.writeInt(Integer.parseInt(parts[2]));
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
        } catch (IOException ignored) {
        }
        return bos.toByteArray();
    }

    private static int bitsFor(int paletteSize) {
        int bits = 1;
        while ((1 << bits) < paletteSize && bits < 64) {
            bits++;
        }
        return bits;
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
