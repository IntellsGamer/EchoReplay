package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.GzipRecordingReader;
import dev.idebugger.echoreplay.storage.GzipRecordingWriter;
import dev.idebugger.echoreplay.storage.MetaParser;
import dev.idebugger.echoreplay.storage.RecordingEntry;
import dev.idebugger.echoreplay.storage.TimelineCodec;
import dev.idebugger.echoreplay.util.NbtBytes;
import dev.idebugger.echoreplay.util.PalettedStorage;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manages the single active recording: budgeted snapshotting, live event
 * buffering, crash-safety checkpoints, and async gzip finalization on stop.
 */
public final class RecordingManager {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-]{1,32}");
    private static final String PARTIAL_SUFFIX = ".partial";

    private final EchoReplay plugin;
    private RecordingSession session;
    private File recordingsDir;
    private int marginBlocks = 8;
    private int flushSeconds = 15;
    private int blocksPerTick = 8000;
    private boolean captureTime = true;
    private int maxDurationMinutes = 30;

    private Cuboid.Section[] pendingSections;
    private int pendingIndex = 0;
    private long flushCounterMs = 0;
    private final EquipmentRecorder equipmentRecorder;
    private final EntityTickRecorder entityTickRecorder;
    private final RegionDiffRecorder regionDiffRecorder;

    public RecordingManager(EchoReplay plugin) {
        this.plugin = plugin;
        this.equipmentRecorder = new EquipmentRecorder(plugin);
        this.entityTickRecorder = new EntityTickRecorder(plugin);
        this.regionDiffRecorder = new RegionDiffRecorder(plugin);
    }

    public void onEnable(FileConfiguration config) {
        marginBlocks = config.getInt("recording.margin-blocks", 8);
        flushSeconds = config.getInt("recording.flush-seconds", 15);
        blocksPerTick = config.getInt("recording.snapshot.blocks-per-tick", 8000);
        captureTime = config.getBoolean("recording.capture-time", true);
        maxDurationMinutes = config.getInt("recording.max-duration-minutes", 30);
        recordingsDir = new File(plugin.getDataFolder(), config.getString("storage.directory", "recordings"));
        recordingsDir.mkdirs();
        regionDiffRecorder.configure(config.getInt("recording.scan-interval-ticks", 1));
        recoverCrashedCheckpoints();
    }

    public void registerListeners(EchoReplay p) {
        p.getServer().getPluginManager().registerEvents(new WorldRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ConnectionRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new EntityRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ActionRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ChatRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new FireworkRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new DamageRecorder(p), p);
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

    public EntityTickRecorder entityTickRecorder() { return entityTickRecorder; }

    public EquipmentRecorder equipmentRecorder() { return equipmentRecorder; }

    public void onTick() {
        if (session == null) return;
        switch (session.state()) {
            case SNAPSHOTTING -> tickSnapshot();
            case RECORDING -> tickRecording();
            default -> {}
        }
    }

    private int snapCursor = 0;

    private void tickSnapshot() {
        Cuboid c = session.cuboid();
        int sx = c.xSize(), sy = c.ySize(), sz = c.zSize();
        long vol = (long) sx * sy * sz;
        if (vol <= 0) {
            finishSnapshotAndRecord();
            return;
        }
        if (snapCursor < 0 || (long) snapCursor >= vol) snapCursor = 0;
        // Time-boxed capture: the old fixed 8000-blocks/tick budget cost
        // ~200ms+ per tick (getBlockData + getState per block) and halved TPS
        // while snapshotting. blocksPerTick remains as a secondary cap.
        long deadline = System.nanoTime() + 10_000_000L;
        int budget = blocksPerTick;
        int count = 0;
        World world = session.world();
        PalettedStorage storage = session.snapshotStorage();
        int minX = c.min().x(), minY = c.min().y(), minZ = c.min().z();
        while ((long) snapCursor < vol && count < budget) {
            int i = snapCursor++;
            int dx = i % sx;
            int dz = (i / sx) % sz;
            int dy = i / (sx * sz);
            int x = minX + dx, y = minY + dy, z = minZ + dz;
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
            count++;
            if ((count & 255) == 0 && System.nanoTime() >= deadline) break;
        }
        // Keep section progress monotonic for any status display.
        if (pendingSections != null && pendingSections.length > 0) {
            int target = (int) ((long) pendingSections.length * snapCursor / Math.max(1L, vol));
            while (pendingIndex < target && pendingIndex < pendingSections.length) {
                pendingIndex++;
                session.markSection();
            }
        }
        if ((long) snapCursor >= vol) {
            finishSnapshotAndRecord();
        }
    }

    private void finishSnapshotAndRecord() {
        entityTickRecorder.reset();
        session.clearEntitySpawned();
        regionDiffRecorder.reset(session);
        snapshotExistingEntities();
        session.setRecording();
        startCheckpointAsync(session);
    }

    private void snapshotExistingEntities() {
        RecordingSession s = session;
        if (s == null) return;
        Cuboid c = s.cuboid();
        World world = s.world();
        for (Player p : world.getPlayers()) {
            if (p.getWorld().getUID().equals(world.getUID())
                    && c.contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) {
                int npc = s.npcIdFor(p.getUniqueId());
                s.emit(new TimelineEvent.PlayerSpawn(s.mediaMillis(), npc, p.getUniqueId(), p.getName(),
                        skin(p), pos(p), rot(p), equipment(p), null));
            }
        }
    }

    private static PlayerSkin skin(Player p) {
        try {
            com.github.retrooper.packetevents.protocol.player.User user =
                    com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(p);
            if (user != null) {
                var props = user.getProfile().getTextureProperties();
                for (var prop : props) {
                    if ("textures".equals(prop.getName())) {
                        return new PlayerSkin(prop.getValue(),
                                prop.getSignature() == null ? null : prop.getSignature());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new PlayerSkin(null, null);
    }

    private static Vec3d pos(Player p) {
        return new Vec3d(p.getLocation().x(), p.getLocation().y(), p.getLocation().z());
    }

    private static Rotation rot(Player p) {
        return new Rotation(p.getLocation().getPitch(), p.getLocation().getYaw(), p.getLocation().getYaw());
    }

    private static List<byte[]> equipment(Player p) {
        var eq = p.getInventory();
        return List.of(
                EquipmentRecorder.serializeItem(eq.getItemInMainHand()),
                EquipmentRecorder.serializeItem(eq.getItemInOffHand()),
                EquipmentRecorder.serializeItem(eq.getBoots()),
                EquipmentRecorder.serializeItem(eq.getLeggings()),
                EquipmentRecorder.serializeItem(eq.getChestplate()),
                EquipmentRecorder.serializeItem(eq.getHelmet())
        );
    }

    private void tickRecording() {
        session.advanceClock(50);
        equipmentRecorder.tick();
        entityTickRecorder.tick();
        if (captureTime) {
            session.emitWorldTimeIfChanged();
            session.emitWeatherIfChanged();
        }
        flushCounterMs += 50;
        if (flushCounterMs >= flushSeconds * 1000L) {
            flushCounterMs = 0;
            autoFlush();
        }
        if (session.mediaMillis() / 1000 > maxDurationMinutes * 60L) {
            forceStopAndSave();
        }
    }

    /**
     * Crash safety: move buffered events out of RAM into the raw checkpoint
     * file so a server death mid-recording loses at most one flush window.
     * Runs on the IO thread.
     */
    private void autoFlush() {
        RecordingSession s = session;
        if (s == null) return;
        plugin.ioExecutor().execute(() -> {
            if (s.state() == RecordingSession.State.RECORDING) {
                flushCheckpoint(s);
            }
        });
    }

    private void flushCheckpoint(RecordingSession s) {
        List<TimelineEvent> batch = s.sink().drainAll();
        if (batch.isEmpty()) return;
        s.addCommitted(batch);
        GzipRecordingWriter w = s.checkpointWriter();
        if (w == null) return; // snapshot not complete yet — nothing to anchor
        synchronized (s.checkpointLock()) {
            try {
                // The palette only grows; checkpoints rewrite it only when it
                // grew since the last flush (last section wins on recovery).
                // Rewriting the full palette every flush is what made .partial
                // files many times bigger than the final recording.
                List<String> palette = s.snapshotPalette();
                if (palette.size() != s.lastCheckpointPaletteSize()) {
                    w.writePalette(palette);
                    s.setLastCheckpointPaletteSize(palette.size());
                }
                for (TimelineEvent ev : batch) {
                    byte[] body = TimelineCodec.encodeBody(ev, palette);
                    w.appendTimelineEvent(ev.tickMillis(), body, (byte) TimelineCodec.typeId(ev));
                }
                w.flush();
            } catch (Exception e) {
                plugin.getLogger().warning("Checkpoint flush failed: " + e);
            }
        }
    }

    /** Open the checkpoint file and write the immutable header sections. */
    private void startCheckpointAsync(RecordingSession s) {
        plugin.ioExecutor().execute(() -> {
            try {
                File f = new File(recordingsDir, s.name() + ".echoreplay.gz" + PARTIAL_SUFFIX);
                s.setCheckpointFile(f);
                s.openCheckpointWriter(f);
                synchronized (s.checkpointLock()) {
                    GzipRecordingWriter w = s.checkpointWriter();
                    if (w == null) return;
                    Cuboid c = s.cuboid();
                    w.writeMeta(plugin.getServer().getMinecraftVersion(), s.world().getUID(), s.world().getName(),
                            c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z(),
                            s.startedMillis(), s.recorderUuid(), s.recorderName(), 0L, s.name());
                    PalettedStorage st = s.snapshotStorage();
                    w.writeBlocks(st.sizeX(), st.sizeY(), st.sizeZ(), st.raw(), bitsFor(s.snapshotPalette().size()));
                    w.writeBlockNbt(packSnapNbt(st, s.snapshotNbt()));
                    w.writeEntities(new byte[0]);
                    w.flush();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not open crash-safety checkpoint: " + e);
            }
        });
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
        // Grow the recorded region so edge activity is not clipped.
        if (marginBlocks > 0) {
            World w = player.getWorld();
            int m = marginBlocks;
            int minY = Math.max(w.getMinHeight(), cuboid.min().y() - m);
            int maxY = Math.min(w.getMaxHeight() - 1, cuboid.max().y() + m);
            cuboid = new Cuboid(new BlockPos(cuboid.min().x() - m, minY, cuboid.min().z() - m),
                    new BlockPos(cuboid.max().x() + m, maxY, cuboid.max().z() + m));
        }
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
        snapCursor = 0;
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
        regionDiffRecorder.stop();
        session = null;
        s.closeCheckpointWriter();
        File cp = s.checkpointFile();
        if (cp != null) cp.delete();
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
        // Stop producers first so the event stream ends here.
        regionDiffRecorder.stop();
        s.sink().close();
        // Cheap main-thread captures only. Draining + sorting millions of
        // events on the tick thread froze the server for seconds (TPS 5 on
        // stop); that work now runs on the IO thread with the file write.
        final long fd = s.mediaMillis();
        final List<String> fpal = s.snapshotPalette();
        final PalettedStorage fs = s.snapshotStorage();
        final Map<String, byte[]> fnbt = s.snapshotNbt();
        final Cuboid fc = s.cuboid();
        final long fsTime = s.startedMillis();
        final String fname = s.name();
        final String fworldName = s.world().getName();
        final java.util.UUID fworldUuid = s.world().getUID();
        final UUID frecorderUuid = s.recorderUuid();
        final String frecorderName = s.recorderName();
        final int fsizeX = fs.sizeX(), fsizeY = fs.sizeY(), fsizeZ = fs.sizeZ();

        session = null;

        plugin.ioExecutor().execute(() -> {
            File out = new File(recordingsDir, fname + ".echoreplay.gz");
            try {
                // Final timeline = checkpointed batches + whatever is still buffered.
                List<TimelineEvent> fev = new ArrayList<>(s.takeCommitted());
                fev.addAll(s.sink().drainAll());
                fev.sort(Comparator.comparingLong(TimelineEvent::tickMillis));
                writeRecordingFile(out, plugin.getServer().getMinecraftVersion(), fworldUuid, fworldName, fc,
                        fd, fsTime, frecorderUuid, frecorderName, fname, fpal,
                        fsizeX, fsizeY, fsizeZ, fs.raw(), packSnapNbt(fs, fnbt), fev);
                plugin.recordingIndex().put(new RecordingEntry(fname, fworldUuid, fworldName, fd, out.length(),
                        fsTime, fc.min().x(), fc.min().y(), fc.min().z(), fc.max().x(), fc.max().y(), fc.max().z()));
                boolean keep = plugin.cfg().getBoolean("storage.keep-partial-on-crash", false);
                s.closeCheckpointWriter();
                File cp = s.checkpointFile();
                if (!keep && cp != null) cp.delete();
            } catch (IOException e) {
                // Keep the checkpoint as a safety net; it is recovered on next start.
                plugin.getLogger().warning("Failed to write recording '" + fname + "': " + e.getMessage()
                        + " — checkpoint kept for recovery on next start.");
            }
        });
        return "<green>Saved recording '" + fname + "' (" + RecordingManager.formatDuration(fd) + ").</green>";
    }

    /**
     * Write the final gzip recording via a temp file + atomic rename (with a
     * cross-device copy fallback that actually runs).
     */
    private void writeRecordingFile(File out, String serverVersion, UUID worldUuid, String worldName, Cuboid c,
                                    long duration, long epoch, UUID recUuid, String recName, String name,
                                    List<String> palette, int sizeX, int sizeY, int sizeZ, int[] blocks,
                                    byte[] blockNbt, List<TimelineEvent> events) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (GzipRecordingWriter w = new GzipRecordingWriter(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            w.writeMeta(serverVersion, worldUuid, worldName,
                    c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z(),
                    epoch, recUuid, recName, duration, name);
            w.writePalette(palette);
            w.writeBlocks(sizeX, sizeY, sizeZ, blocks, bitsFor(Math.max(1, palette.size())));
            w.writeBlockNbt(blockNbt);
            w.writeEntities(new byte[0]);
            for (TimelineEvent ev : events) {
                byte[] body = TimelineCodec.encodeBody(ev, palette);
                w.appendTimelineEvent(ev.tickMillis(), body, (byte) TimelineCodec.typeId(ev));
            }
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            java.nio.file.Files.copy(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.delete();
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

    /**
     * Crash recovery: on start, turn leftover checkpoint files into real
     * recordings (duration recomputed from the last surviving event), or clean
     * up stale ones whose final file already exists. Runs on the IO thread.
     */
    public void recoverCrashedCheckpoints() {
        File dir = recordingsDir;
        File[] partials = dir.listFiles((d, n) -> n.endsWith(PARTIAL_SUFFIX));
        if (partials == null || partials.length == 0) return;
        plugin.ioExecutor().execute(() -> {
            for (File partial : partials) {
                File finalFile = new File(dir,
                        partial.getName().substring(0, partial.getName().length() - PARTIAL_SUFFIX.length()));
                try {
                    if (finalFile.exists()) {
                        partial.delete(); // final recording already saved — stale checkpoint
                        continue;
                    }
                    GzipRecordingReader r = GzipRecordingReader.readLenient(new BufferedInputStream(new FileInputStream(partial)));
                    if (r == null || r.meta() == null) {
                        plugin.getLogger().warning("Checkpoint " + partial.getName()
                                + " has no complete header — keeping for manual recovery.");
                        continue;
                    }
                    MetaParser.Parsed meta = MetaParser.parse(r.meta());
                    List<TimelineEvent> events = r.timeline();
                    r.releaseFragments();
                    long duration = events.isEmpty() ? 0 : events.get(events.size() - 1).tickMillis();
                    String name = meta.name() != null && !meta.name().isEmpty()
                            ? meta.name() : finalFile.getName().replace(".echoreplay.gz", "");
                    writeRecordingFile(finalFile, meta.serverVersion(), meta.worldUuid(), meta.worldName(),
                            meta.cuboid(), duration, meta.epochMillis(), meta.recorderUuid(), meta.recorderName(),
                            name, r.palette() != null ? r.palette() : List.of(),
                            r.blockSizeX(), r.blockSizeY(), r.blockSizeZ(),
                            r.blockData() != null ? r.blockData() : new int[0], r.blockNbt(), events);
                    plugin.recordingIndex().put(new RecordingEntry(name, meta.worldUuid(), meta.worldName(),
                            duration, finalFile.length(), meta.epochMillis(),
                            meta.cuboid().min().x(), meta.cuboid().min().y(), meta.cuboid().min().z(),
                            meta.cuboid().max().x(), meta.cuboid().max().y(), meta.cuboid().max().z()));
                    if (!plugin.cfg().getBoolean("storage.keep-partial-on-crash", true)) partial.delete();
                    plugin.getLogger().info("Recovered crashed recording '" + name + "' ("
                            + events.size() + " events, " + formatDuration(duration) + ").");
                } catch (Exception e) {
                    plugin.getLogger().warning("Checkpoint recovery failed for " + partial.getName() + ": " + e);
                }
            }
        });
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
