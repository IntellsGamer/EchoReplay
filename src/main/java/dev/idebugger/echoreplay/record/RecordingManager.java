package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.GzipRecordingWriter;
import dev.idebugger.echoreplay.storage.RecordingEntry;
import dev.idebugger.echoreplay.storage.TimelineCodec;
import dev.idebugger.echoreplay.util.NbtBytes;
import dev.idebugger.echoreplay.util.PalettedStorage;
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

    private final EchoReplay plugin;
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
        blocksPerTick = config.getInt("snapshot.blocks-per-tick", 8000);
        maxDurationMinutes = config.getInt("recording.max-duration-minutes", 30);
        recordingsDir = new File(plugin.getDataFolder(), config.getString("storage.directory", "recordings"));
        recordingsDir.mkdirs();
        regionDiffRecorder.configure(config.getInt("recording.scan-interval-ticks", 20));
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
            // D-5: use the BlockState-aware overload — catches BEACON, SPAWNER,
            // BANNER, BEEHIVE, CONDUIT, ITEM_FRAME, etc. via TileState check,
            // not just the explicit Material list.
            if (bs != null && Snapshotter.needsNbt(bs)) {
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
        regionDiffRecorder.tick();
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

        session = null;

        plugin.ioExecutor().execute(() -> {
            try {
                List<TimelineEvent> fev = drainEvents(s);
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
                try {
                    byte[] body = TimelineCodec.encodeBody(ev, palette);
                    w.appendTimelineEvent(ev.tickMillis(), body, (byte) TimelineCodec.typeId(ev));
                } catch (IOException oversized) {
                    // S-4: skip the single oversized event instead of failing
                    // the entire recording. PlayerSpawn with shulker-box-of-
                    // shulker-boxes (~2MB NBT) is the most common culprit.
                    plugin.getLogger().warning("Skipping oversized event "
                        + ev.getClass().getSimpleName() + " at " + ev.tickMillis()
                        + "ms in '" + name + "': " + oversized.getMessage());
                }
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
