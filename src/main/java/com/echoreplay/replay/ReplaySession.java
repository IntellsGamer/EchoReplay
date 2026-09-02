package com.echoreplay.replay;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.select.Cuboid;
import com.echoreplay.storage.GzipRecordingReader;
import com.echoreplay.storage.MetaParser;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A loaded, playing recording. Holds decoded data, the clock, viewer set, and
 * the runtime fake-entity state. Driven by ReplayManager's per-tick loop (main
 * thread only).
 */
public final class ReplaySession {

    private final EchoReplayPlugin plugin;
    private final String name;
    private final World world;
    private final Cuboid cuboid;
    private final boolean virtual;
    private final List<String> palette;
    private final int[] snapshotData;
    private final int snapshotSizeX, snapshotSizeY, snapshotSizeZ;
    private final Map<String, byte[]> snapNbt;
    private final List<TimelineEvent> timeline;
    private final Clock clock = new Clock();

    // event index applied so far (monotonic forward cursor)
    private int appliedIndex = 0;
    // stableId -> runtime fake entity id
    private final Map<Integer, Integer> runtimeByStable = new HashMap<>();
    // stableId -> current position/rotation for the snapshot-reset seek
    private final Map<Integer, EntityPose> entityPoses = new HashMap<>();
    private final FakeEntityTracker fakes = new FakeEntityTracker();
    private final List<UUID> viewers = new ArrayList<>();

    private boolean started = false;
    private boolean stopping = false;
    private double skipSfxAbove = 2.0;

    // track per-stable whether currently spawned viewer-visible
    private final Map<Integer, Integer> stableToRuntime = new HashMap<>();
    // runtimeId -> players this fake entity was actually spawned to
    private final Map<Integer, Set<UUID>> spawnedFor = new HashMap<>();

    record EntityPose(com.echoreplay.model.Vec3d pos, com.echoreplay.model.Rotation rot) {}

    public ReplaySession(EchoReplayPlugin plugin, String name, World world, boolean virtual,
                         GzipRecordingReader reader, MetaParser.Parsed meta) {
        this.plugin = plugin;
        this.name = name;
        this.world = world;
        this.cuboid = meta.cuboid();
        this.virtual = virtual;
        this.palette = reader.palette() != null ? reader.palette() : List.of();
        this.snapshotData = reader.blockData() != null ? reader.blockData() : new int[0];
        this.snapshotSizeX = reader.blockSizeX();
        this.snapshotSizeY = reader.blockSizeY();
        this.snapshotSizeZ = reader.blockSizeZ();
        this.snapNbt = decodeSnapNbt(reader.blockNbt());
        this.timeline = reader.timeline() != null ? reader.timeline() : List.of();
        clock.setSpeed(plugin.cfg().getDouble("replay.default-speed", 1.0));
        skipSfxAbove = plugin.cfg().getDouble("replay.skip-sfx-when-speed-above", 2.0);
    }

    public String name() { return name; }
    public World world() { return world; }
    public Cuboid cuboid() { return cuboid; }
    public boolean virtual() { return virtual; }
    public Clock clock() { return clock; }
    public boolean started() { return started; }
    public double durationMs() { return timeline.isEmpty() ? 0 : timeline.get(timeline.size() - 1).tickMillis(); }

    public void addViewer(Player p) {
        if (!viewers.contains(p.getUniqueId())) {
            viewers.add(p.getUniqueId());
        }
    }

    public void removeViewer(Player p) {
        viewers.remove(p.getUniqueId());
    }

    public boolean isViewer(Player p) {
        return viewers.contains(p.getUniqueId());
    }

    public List<UUID> viewerIds() {
        return new ArrayList<>(viewers);
    }

    /** Restore the snapshot into the world (world mode) and reset entity state. */
    public void applySnapshot() {
        if (!virtual) {
            SnapshotApplier.applyToWorld(world, cuboid, snapshotData, snapshotSizeX, snapshotSizeY,
                    snapshotSizeZ, palette);
            applySnapNbt();
        }
        // reset fake entities: destroy all, clear mappings
        for (Map.Entry<Integer, Integer> e : stableToRuntime.entrySet()) {
            destroyFor(e.getValue());
        }
        stableToRuntime.clear();
        spawnedFor.clear();
        entityPoses.clear();
        // apply t=0 entity spawns
        while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() == 0) {
            applyEvent(timeline.get(appliedIndex));
            appliedIndex++;
        }
    }

    /** Advance the clock and apply not-yet-applied events up to media time. */
    public void tick() {
        if (!started || stopping) return;
        double media = clock.tick();
        while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() <= media) {
            TimelineEvent ev = timeline.get(appliedIndex);
            appliedIndex++;
            applyEvent(ev);
        }
        if (appliedIndex >= timeline.size() && media >= durationMs()) {
            // reached the end; hold
        }
    }

    /**
     * Seek to a media time by snapshot-reset then fast-applying all events up to
     * target. Backward always resets; forward resets too for simplicity/correctness.
     */
    public void seekTo(double targetMs) {
        applySnapshot();
        appliedIndex = 0;
        double resetMedia = 0;
        // re-apply coherently: walk events setting media time to each event's ts
        while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() <= targetMs) {
            TimelineEvent ev = timeline.get(appliedIndex);
            appliedIndex++;
            applyEvent(ev);
        }
        clock.seekTo(targetMs);
    }

    public void setPaused(boolean paused) {
        if (paused) clock.pause();
        else clock.resume();
    }

    public void setSpeed(double speed) {
        clock.setSpeed(speed);
    }

    public void play() {
        started = true;
        stopping = false;
        appliedIndex = 0;
        applySnapshot();
        clock.resume();
    }

    public void stop() {
        stopping = true;
        started = false;
        for (Map.Entry<Integer, Integer> e : stableToRuntime.entrySet()) {
            destroyFor(e.getValue());
        }
        stableToRuntime.clear();
    }

    private List<Player> liveViewers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !out.contains(p)) out.add(p);
        }
        // Broadcast to every player in the replay's world who is close enough to
        // the cuboid to see what is happening, so the replay is visible to all
        // nearby players (not just whoever typed /er play or /er watch).
        int margin = plugin.cfg().getInt("replay.auto-watch-radius", 32);
        for (Player p : world.getPlayers()) {
            if (out.contains(p)) continue;
            var loc = p.getLocation();
            if (!loc.getWorld().getUID().equals(world.getUID())) continue;
            int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
            if (bx >= cuboid.min().x() - margin && bx <= cuboid.max().x() + margin
                    && bz >= cuboid.min().z() - margin && bz <= cuboid.max().z() + margin
                    && by >= cuboid.min().y() - margin && by <= cuboid.max().y() + margin) {
                out.add(p);
            }
        }
        return out;
    }

    private void applyEvent(TimelineEvent ev) {
        switch (ev) {
            case TimelineEvent.BlockSet b -> applyBlockSet(b);
            case TimelineEvent.MultiBlock m -> m.blocks().forEach(this::applyBlockSet);
            case TimelineEvent.BlockBreakAnim b -> onBlockBreakAnim(b);
            case TimelineEvent.PlayerSpawn s -> onPlayerSpawn(s);
            case TimelineEvent.EntitySpawn s -> onEntitySpawn(s);
            case TimelineEvent.PlayerLeave l -> despawn(l.npcId());
            case TimelineEvent.EntityLeave l -> despawn(l.npcId());
            case TimelineEvent.Death d -> despawn(d.npcId());
            case TimelineEvent.Move m -> onMove(m);
            case TimelineEvent.Teleport t -> onTeleport(t);
            case TimelineEvent.Velocity ignored -> {}
            case TimelineEvent.Animation a -> onAnimation(a);
            case TimelineEvent.Chat c -> onChat(c);
            case TimelineEvent.Equipment eq -> onEquipment(eq);
            case TimelineEvent.SneakSprint ignored -> {}
            default -> {}
        }
    }

    private void onBlockBreakAnim(TimelineEvent.BlockBreakAnim b) {
        EchoReplayPlugin.getPlugin(EchoReplayPlugin.class).getLogger()
                .info("DBG BlockBreakAnim t=" + clock.mediaTime() + " stage=" + b.stage() + " npc=" + b.breakerNpcId());
        Integer runtime = stableToRuntime.get(b.breakerNpcId());
        if (runtime == null) runtime = 0;
        int wx = cuboid.min().x() + b.pos().x();
        int wy = cuboid.min().y() + b.pos().y();
        int wz = cuboid.min().z() + b.pos().z();
        var pkt = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation(
                runtime, new com.github.retrooper.packetevents.util.Vector3i(wx, wy, wz),
                (byte) (b.stage() & 0xFF));
        for (Player p : liveViewers()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, pkt);
        }
    }

    private void applyBlockSet(TimelineEvent.BlockSet b) {
        if (virtual) return; // virtual-write path is a TODO note: viewer-only overlay
        int wx = cuboid.min().x() + b.pos().x();
        int wy = cuboid.min().y() + b.pos().y();
        int wz = cuboid.min().z() + b.pos().z();
        BlockData data;
        try {
            String state = palette.get(b.paletteIndex());
            data = Bukkit.createBlockData(state);
        } catch (Exception ex) {
            EchoReplayPlugin.getPlugin(EchoReplayPlugin.class).getLogger()
                    .warning("BlockSet apply failed palette=" + b.paletteIndex() + " size=" + palette.size() + " " + ex);
            return;
        }
        EchoReplayPlugin.getPlugin(EchoReplayPlugin.class).getLogger()
                .info("DBG BlockSet t=" + clock.mediaTime() + " @" + wx + "," + wy + "," + wz
                        + " -> " + data.getAsString(true)
                        + " (paletteIdx=" + b.paletteIndex() + ")");
        // Update the actual world block; applyPhysics=true so neighbor updates,
        // gravity and state broadcast happen (the per-event physics-freeze
        // handler still cancels any unrecorded cascade inside the cuboid).
        world.getBlockAt(wx, wy, wz).setBlockData(data, true);
        // Also push the change straight to all viewers so the break/update is
        // guaranteed visible even if the viewer's client didn't get a chunk sync.
        var change = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange(
                new com.github.retrooper.packetevents.util.Vector3i(wx, wy, wz), 0);
        change.setBlockState(
                io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitBlockData(data));
        for (Player p : liveViewers()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacket(p, change);
        }
        // Re-apply block-entity NBT (sign text, container contents, respawn
        // anchor charges, etc.) so these blocks update rather than just place.
        if (b.nbt() != null && b.nbt().length > 0) {
            try {
                var tile = world.getBlockAt(wx, wy, wz).getState(true);
                com.echoreplay.util.NbtBytes.applyBlockState(tile, b.nbt());
                tile.update(true);
            } catch (Exception ignored) {
            }
        }
    }

    private void onPlayerSpawn(TimelineEvent.PlayerSpawn s) {
        int runtime = fakes.allocateId();
        stableToRuntime.put(s.npcId(), runtime);
        entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
        for (Player p : liveViewers()) {
            fakes.spawnPlayer(p, runtime, s.uuid(), s.name(), s.skin(), s.pos(), s.rot());
            recordSpawnedFor(runtime, p);
        }
    }

    private void onEntitySpawn(TimelineEvent.EntitySpawn s) {
        String key = s.typeKey();
        int slash = key.indexOf(':');
        String plain = slash >= 0 ? key.substring(slash + 1) : key;
        com.github.retrooper.packetevents.protocol.entity.type.EntityType type;
        try {
            type = com.github.retrooper.packetevents.protocol.entity.type.EntityTypes.getByName(plain);
        } catch (Exception ex) {
            type = null;
        }
        if (type == null) return; // unknown type — cannot faithfully spawn
        int runtime = fakes.allocateId();
        stableToRuntime.put(s.npcId(), runtime);
        entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
        for (Player p : liveViewers()) {
            fakes.spawnMob(p, runtime, s.uuid(), type, s.pos(), s.rot());
            recordSpawnedFor(runtime, p);
        }
    }

    private void onMove(TimelineEvent.Move m) {
        Integer runtime = stableToRuntime.get(m.npcId());
        if (runtime == null) return;
        entityPoses.put(m.npcId(), new EntityPose(m.pos(), m.rot()));
        for (Player p : liveViewers()) {
            fakes.positionSync(p, runtime, m.pos(), m.rot(), m.onGround());
            fakes.headLook(p, runtime, m.rot().headYaw());
        }
    }

    private void onTeleport(TimelineEvent.Teleport t) {
        Integer runtime = stableToRuntime.get(t.npcId());
        if (runtime == null) return;
        entityPoses.put(t.npcId(), new EntityPose(t.pos(), t.rot()));
        for (Player p : liveViewers()) {
            fakes.positionSync(p, runtime, t.pos(), t.rot(), true);
            fakes.headLook(p, runtime, t.rot().headYaw());
        }
    }

    private void onAnimation(TimelineEvent.Animation a) {
        Integer runtime = stableToRuntime.get(a.npcId());
        if (runtime == null) return;
        com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation anim;
        if (a.anim() == 1) {
            anim = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation(
                    runtime, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND);
        } else {
            anim = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation(
                    runtime, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM);
        }
        for (Player p : liveViewers()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, anim);
        }
    }

    private void onChat(TimelineEvent.Chat c) {
        for (Player p : liveViewers()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p,
                            new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(
                                    false, c.json()));
        }
    }

    private void onEquipment(TimelineEvent.Equipment eq) {
        Integer runtime = stableToRuntime.get(eq.npcId());
        if (runtime == null) return;
        var item = com.echoreplay.record.EquipmentRecorder.deserializeItem(eq.item());
        var peItem = io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(item);
        com.github.retrooper.packetevents.protocol.player.EquipmentSlot peSlot = switch (eq.slot()) {
            case 0 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND;
            case 1 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND;
            case 2 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS;
            case 3 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS;
            case 4 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE;
            case 5 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET;
            default -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND;
        };
        var eqPacket = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment(
                runtime, java.util.List.of(
                        new com.github.retrooper.packetevents.protocol.player.Equipment(peSlot, peItem)));
        for (Player p : liveViewers()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, eqPacket);
        }
    }

    private void despawn(int stableId) {
        Integer runtime = stableToRuntime.remove(stableId);
        entityPoses.remove(stableId);
        if (runtime == null) return;
        destroyFor(runtime);
    }

    /** Send a destroy packet to exactly the players this fake entity was spawned for. */
    private void destroyFor(int runtimeId) {
        Set<UUID> ids = spawnedFor.remove(runtimeId);
        if (ids == null) return;
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) fakes.destroy(p, runtimeId);
        }
    }

    private void recordSpawnedFor(int runtimeId, Player p) {
        spawnedFor.computeIfAbsent(runtimeId, k -> new HashSet<>()).add(p.getUniqueId());
    }

    /** Apply recorded tile-entity NBT for the initial snapshot blocks. */
    private void applySnapNbt() {
        if (snapNbt.isEmpty()) return;
        for (Map.Entry<String, byte[]> e : snapNbt.entrySet()) {
            String[] parts = e.getKey().split(",");
            try {
                int relX = Integer.parseInt(parts[0]);
                int relY = Integer.parseInt(parts[1]);
                int relZ = Integer.parseInt(parts[2]);
                int wx = cuboid.min().x() + relX;
                int wy = cuboid.min().y() + relY;
                int wz = cuboid.min().z() + relZ;
                var tile = world.getBlockAt(wx, wy, wz).getState(true);
                com.echoreplay.util.NbtBytes.applyBlockState(tile, e.getValue());
                tile.update(true);
            } catch (Exception ignored) {
            }
        }
    }

    private static Map<String, byte[]> decodeSnapNbt(byte[] raw) {
        Map<String, byte[]> out = new HashMap<>();
        if (raw == null || raw.length == 0) return out;
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw));
            int sizeX = in.readInt();
            int sizeY = in.readInt();
            int sizeZ = in.readInt();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                int len = in.readInt();
                byte[] nb = new byte[len];
                in.readFully(nb);
                out.put(x + "," + y + "," + z, nb);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
