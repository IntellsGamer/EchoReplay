package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import dev.idebugger.echoreplay.util.Text;
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

    private final EchoReplay plugin;
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
    // stableId -> recorded player display name, so chat can be re-rendered as
    // "<Name> message" in all-white.
    private final Map<Integer, String> nameByStable = new HashMap<>();
    private final FakeEntityTracker fakes = new FakeEntityTracker();
    private final List<UUID> viewers = new ArrayList<>();

    // runtimeId -> ticks remaining before the fake entity is destroyed, so dead
    // mobs play their death animation instead of vanishing instantly.
    private final Map<Integer, Integer> dyingRuntimes = new HashMap<>();
    private static final int DEATH_DELAY_TICKS = 22;

    // Playback border particles: throttling counter, per-session.
    private int borderTickCounter = 0;

    private boolean started = false;
    private boolean stopping = false;
    private double skipSfxAbove = 2.0;

    // track per-stable whether currently spawned viewer-visible
    private final Map<Integer, Integer> stableToRuntime = new HashMap<>();
    // runtimeId -> players this fake entity was actually spawned to
    private final Map<Integer, Set<UUID>> spawnedFor = new HashMap<>();
    // runtimeId -> last stance flags byte / pose, so successive stance events
    // can be merged into one absolute metadata packet (metadata is not relative).
    private final Map<Integer, Byte> runtimeFlags = new HashMap<>();
    private final Map<Integer, com.github.retrooper.packetevents.protocol.entity.pose.EntityPose> runtimePose = new HashMap<>();
    // stableId -> last sent head yaw; head-look packets are only re-sent when
    // the head actually turned (moves usually only change position).
    private final Map<Integer, Float> runtimeHeadYaw = new HashMap<>();

    // S-8: viewers who have already received the full current state.
    // New viewers (just joined via /er watch or auto-watch-radius) are added
    // here only after resendStateTo runs for them, so the next per-tick
    // applyEvent loop won't try to send updates for entities they've never
    // seen spawned.
    private final Set<UUID> caughtUp = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // D-7: catch-up flag. While true, side-effect events (chat, sound,
    // particle, block-break anim, damage flashes) are suppressed —
    // otherwise a seek from 0:00 to 10:00 dumps every chat line + sound
    // from those 10 minutes into a single tick, flooding chat windows.
    // State events (move, spawn, equipment, pose) are still applied because
    // they define what the world looks like at the new time.
    private boolean catchingUp = false;
    private int chatSkippedDuringCatchup = 0;
    private int sfxSkippedDuringCatchup = 0;

    // Viewers resolved once per tick and reused by every event applied that
    // tick. Recomputing per event (world player scan + Location allocs) was
    // the dominant per-tick cost during busy playbacks.
    private List<Player> tickViewers = new ArrayList<>();

    // Palette strings parsed lazily into BlockData (+ packet block states)
    // and cached per index. Bukkit.createBlockData parses text on every call;
    // recordings replay the same handful of states thousands of times, and
    // parsing the whole palette upfront stalls /er play on large recordings.
    // All access is main-thread only (playback tick), so plain HashMaps do.
    private final Map<Integer, BlockData> blockDataCache = new HashMap<>();
    private final Map<Integer, Object> packetStateCache = new HashMap<>();
    private final Map<Integer, BlockData> liveDataCache = new HashMap<>();
    private final Set<Integer> warnedBadPalette = new HashSet<>();

    // Region streaming phases: CAPTURE/SNAPSHOT/RESTORE walk the cuboid with
    // a per-tick millisecond budget so play/seek/stop never freeze the tick
    // loop, no matter how large the region is.
    private enum Phase { CAPTURE, SNAPSHOT, CATCHUP, RUN, RESTORE, DONE }
    private Phase phase = Phase.RUN;
    private int phaseCursor = 0;
    private double catchupTargetMs = -1;
    private double pendingSeekMs = -1;
    private boolean stopInitiated = false;
    private long phaseBudgetNanos = 8_000_000L;

    // Live-terrain capture build state (world mode only).
    private java.util.Map<String, Integer> capturePalIdx;
    private java.util.List<String> capturePal;

    // Metadata index-0 entity-flag bits we are allowed to transmit. Everything
    // else (0x01 fire, 0x20 invisible, 0x40 glow, 0x80 elytra) is masked out.
    private static final int FLAG_MASK_CROUCHED = 0x02;
    private static final int FLAG_MASK_SPRINTING = 0x08;
    private static final int FLAG_MASK_SWIMMING = 0x10;

    // Live terrain captured when playback starts (world mode only), so stopplay /
    // auto-end can restore the region to its pre-playback state.
    private boolean liveCaptured = false;
    private java.util.List<String> livePalette = java.util.List.of();
    private int[] liveData = new int[0];
    private final Map<String, byte[]> liveNbt = new HashMap<>();

    record EntityPose(dev.idebugger.echoreplay.model.Vec3d pos, dev.idebugger.echoreplay.model.Rotation rot) {}

    public ReplaySession(EchoReplay plugin, String name, World world, boolean virtual,
                         DecodedRecording rec) {
        this.plugin = plugin;
        this.name = name;
        this.world = world;
        this.cuboid = rec.meta().cuboid();
        this.virtual = virtual;
        this.palette = rec.palette() != null ? rec.palette() : List.of();
        this.snapshotData = rec.blockData() != null ? rec.blockData() : new int[0];
        this.snapshotSizeX = rec.blockSizeX();
        this.snapshotSizeY = rec.blockSizeY();
        this.snapshotSizeZ = rec.blockSizeZ();
        this.snapNbt = decodeSnapNbt(rec.blockNbt());
        this.timeline = rec.timeline() != null ? rec.timeline() : List.of();
        clock.setSpeed(plugin.cfg().getDouble("replay.default-speed", 1.0));
        skipSfxAbove = plugin.cfg().getDouble("replay.skip-sfx-when-speed-above", 2.0);
        long budgetMs = 8L;
        try {
            budgetMs = plugin.cfg().getLong("replay.phase-max-ms-per-tick", 8L);
        } catch (Exception ignored) {
        }
        if (budgetMs < 1) budgetMs = 1;
        if (budgetMs > 40) budgetMs = 40;
        this.phaseBudgetNanos = budgetMs * 1_000_000L;
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
            // S-8: viewer is NOT yet caughtUp — refreshViewers() will detect
            // this next tick and call resendStateTo(p). That method sends
            // every currently-spawned fake entity's spawn+pose to this viewer.
        }
    }

    public void removeViewer(Player p) {
        viewers.remove(p.getUniqueId());
        // S-8: forget catch-up state for this viewer so they get re-caught-up
        // if they /er watch again later.
        caughtUp.remove(p.getUniqueId());
    }

    public boolean isViewer(Player p) {
        return viewers.contains(p.getUniqueId());
    }

    public List<UUID> viewerIds() {
        return new ArrayList<>(viewers);
    }

    /** Parsed BlockData for a palette index, parsed once then cached. */
    private BlockData blockDataFor(int pi) {
        return parsedFor(blockDataCache, palette, pi);
    }

    /** Packet block state for a palette index, converted once then cached. */
    private Object packetStateFor(int pi) {
        BlockData data = blockDataFor(pi);
        if (data == null) return null;
        Object cached = packetStateCache.get(pi);
        if (cached != null) return cached;
        try {
            Object wrapped = io.github.retrooper.packetevents.util.SpigotConversionUtil
                    .fromBukkitBlockData(data);
            if (wrapped != null) packetStateCache.put(pi, wrapped);
            return wrapped;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Parsed BlockData for a live-terrain palette index, parsed once then cached. */
    private BlockData liveDataFor(int pi) {
        return parsedFor(liveDataCache, livePalette, pi);
    }

    private BlockData parsedFor(Map<Integer, BlockData> cache, List<String> pal, int pi) {
        if (pi < 0 || pi >= pal.size()) {
            if (warnedBadPalette.add(pi)) {
                EchoReplay.getPlugin(EchoReplay.class).getLogger()
                        .warning("BlockSet apply failed palette=" + pi + " size=" + pal.size());
            }
            return null;
        }
        BlockData data = cache.get(pi);
        if (data != null) return data;
        try {
            data = Bukkit.createBlockData(pal.get(pi));
        } catch (Exception ex) {
            if (warnedBadPalette.add(pi)) {
                EchoReplay.getPlugin(EchoReplay.class).getLogger()
                        .warning("BlockSet apply failed palette=" + pi + " size=" + pal.size() + " " + ex);
            }
            return null;
        }
        cache.put(pi, data);
        return data;
    }

    /** Refresh the per-tick viewer cache and run catch-up for any new viewers. */
    private void refreshViewers() {
        tickViewers = liveViewers();
        // S-8: any viewer in tickViewers not yet in caughtUp needs the
        // current full state re-sent to them. This runs at most once per
        // viewer (caughtUp set is checked before re-send).
        for (Player p : tickViewers) {
            if (!caughtUp.contains(p.getUniqueId())) {
                try {
                    resendStateTo(p);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Viewer catch-up failed for "
                        + p.getName() + ": " + t);
                    caughtUp.add(p.getUniqueId()); // avoid retry loop
                }
            }
        }
    }

    /**
     * Advance streaming phases and, while RUNNING, the clock + events.
     *
     * @return true when the session is fully done (timeline ended and terrain
     * restored, or stop-restore completed) and the manager may drop it.
     */
    public boolean tick() {
        if (!started) return false;
        refreshViewers();
        switch (phase) {
            case CAPTURE -> {
                if (drainCapture()) {
                    beginSnapshot(catchupTargetMs >= 0 || pendingSeekMs >= 0
                            ? Math.max(catchupTargetMs, pendingSeekMs) : -1);
                    pendingSeekMs = -1;
                }
                return false;
            }
            case SNAPSHOT -> {
                if (drainSnapshot()) {
                    finishSnapshot();
                }
                return false;
            }
            case CATCHUP -> {
                drainCatchup();
                return false;
            }
            case RESTORE -> {
                return drainRestore();
            }
            case DONE -> {
                return true;
            }
            case RUN -> {
                if (stopping) return false;
                tickDeaths();
                double media = clock.tick();
                while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() <= media) {
                    TimelineEvent ev = timeline.get(appliedIndex);
                    appliedIndex++;
                    applyEvent(ev);
                }
                if (appliedIndex >= timeline.size() && media >= durationMs()) {
                    // Reached the end: destroy fakes + restore terrain, then done.
                    beginStopPhase();
                    return false;
                }
                return false;
            }
        }
        return false;
    }

    /** Count down pending death animations and destroy the fake entity at the end. */
    private void tickDeaths() {
        if (dyingRuntimes.isEmpty()) return;
        for (java.util.Iterator<Map.Entry<Integer, Integer>> it = dyingRuntimes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> en = it.next();
            int left = en.getValue() - 1;
            if (left <= 0) {
                destroyFor(en.getKey());
                it.remove();
            } else {
                en.setValue(left);
            }
        }
    }

    /**
     * Seek to a media time by snapshot-reset then fast-applying all events up to
     * target. Backward always resets; forward resets too for simplicity/correctness.
     */
    /** Seek to a marker by name (or to time if numeric). */
    public boolean seekToMarker(String nameOrMs) {
        try {
            double ms = Double.parseDouble(nameOrMs) * 1000;
            seekTo(ms);
            return true;
        } catch (NumberFormatException e) {
            // Find marker by name
            for (TimelineEvent ev : timeline) {
                if (ev instanceof TimelineEvent.Marker m) {
                    if (m.name().equals(nameOrMs) || m.name().contains(nameOrMs)) {
                        seekTo(m.tickMillis());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void seekTo(double targetMs) {
        if (stopping || phase == Phase.RESTORE || phase == Phase.DONE) return;
        if (virtual) {
            // D-7: route virtual seek through the same budgeted CATCHUP phase
            // as world mode. v1 ran it synchronously with no budget — for a
            // 30-min entity-heavy take that's potentially hundreds of
            // thousands of applyEvent calls in one command call = multi-
            // second main-thread stall. Now it streams over ticks like world
            // mode, with side-effect events suppressed.
            resetEntities();
            appliedIndex = 0;
            seekWasPaused = clock.paused();
            clock.pause();
            // Use the catchup phase machinery to stream events up to target.
            beginCatchup(targetMs);
            return;
        }
        boolean wasPaused = clock.paused();
        clock.pause();
        // If still capturing the pre-playback terrain, the snapshot must wait
        // for it (otherwise stop could restore a half-captured region).
        if (phase == Phase.CAPTURE) {
            pendingSeekMs = targetMs;
            seekWasPaused = wasPaused;
            appliedIndex = 0;
            return;
        }
        seekWasPaused = wasPaused;
        appliedIndex = 0;
        beginSnapshot(targetMs);
    }

    /** Begin streaming events up to {@code targetMs}, with side effects suppressed. */
    private void beginCatchup(double targetMs) {
        catchupTargetMs = targetMs;
        chatSkippedDuringCatchup = 0;
        sfxSkippedDuringCatchup = 0;
        catchingUp = true;
        phase = Phase.CATCHUP;
    }

    /** Re-send the current full fake-entity state to a viewer who just joined. */
    public void resendStateTo(Player p) {
        // S-8: walk every currently-spawned fake entity and re-send the
        // spawn + current pose + flags + head yaw + name + skin + equipment
        // to exactly this viewer. They will then see all future moves for
        // entity IDs their client now knows about. Without this, late
        // viewers see invisible replays (move packets for IDs the client
        // never spawned).
        for (Map.Entry<Integer, Integer> e : stableToRuntime.entrySet()) {
            int stable = e.getKey();
            int runtime = e.getValue();
            EntityPose pose = entityPoses.get(stable);
            if (pose == null) continue;
            String name = nameByStable.get(stable);
            if (name != null) {
                // Re-send player spawn + info entry. Skin is not preserved
                // post-spawn (only the spawn packet carried it), so a late
                // viewer sees the default skin — acceptable trade-off for
                // not having to keep a per-stable skin cache.
                fakes.spawnPlayer(p, runtime, UUID.randomUUID(), name,
                        null, pose.pos(), pose.rot());
                recordSpawnedFor(runtime, p);
            } else {
                // For mobs we lost the original EntityType, so skip the
                // re-spawn — best-effort catch-up for the common case
                // (player-heavy replays). Mob-only replays will still see
                // missing entities for late joiners, which is the same as v1.
            }
            fakes.positionSync(p, runtime, pose.pos(), pose.rot(), true);
            Float head = runtimeHeadYaw.get(stable);
            if (head != null) fakes.headLook(p, runtime, head);
            Byte flags = runtimeFlags.get(runtime);
            com.github.retrooper.packetevents.protocol.entity.pose.EntityPose ep =
                    runtimePose.get(runtime);
            if (flags != null || ep != null) {
                java.util.List<EntityData<?>> data = new ArrayList<>();
                data.add(new EntityData<>(0, EntityDataTypes.BYTE,
                        (byte) (flags == null ? 0 : (flags & (FLAG_MASK_CROUCHED | FLAG_MASK_SPRINTING | FLAG_MASK_SWIMMING)))));
                data.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE,
                        ep == null ? com.github.retrooper.packetevents.protocol.entity.pose.EntityPose.STANDING : ep));
                fakes.setMetadata(p, runtime, data);
            }
        }
        caughtUp.add(p.getUniqueId());
    }

    private boolean seekWasPaused = false;

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
        stopInitiated = false;
        appliedIndex = 0;
        borderTickCounter = 0;
        runtimeHeadYaw.clear();
        caughtUp.clear();
        if (virtual) {
            // No terrain to rebuild: entities + t=0 events only, then run.
            resetEntities();
            applyT0Events();
            phase = Phase.RUN;
            clock.resume();
            return;
        }
        // Capture the region's pre-playback terrain (once) so we can restore
        // it when playback ends, before the recorded snapshot wipes it.
        // Both capture and snapshot stream across ticks (see tick()).
        clock.pause();
        if (!liveCaptured) {
            beginCapture();
        } else {
            beginSnapshot(-1);
        }
    }

    public void stop() {
        if (stopInitiated) return;
        stopInitiated = true;
        stopping = true;
        beginStopPhase();
    }

    /** True once stop() began; the session lingers only until restore drains. */
    public boolean isStopping() {
        return stopping;
    }

    /** Destroy fakes, notify viewers, then stream the terrain restore. */
    private void beginStopPhase() {
        stopping = true;
        // Notify viewers that playback ended
        for (Player p : liveViewers()) {
            p.sendMessage(Text.mm("<gray>Playback ended.</gray>"));
        }
        for (Map.Entry<Integer, Integer> e : stableToRuntime.entrySet()) {
            destroyFor(e.getValue());
        }
        for (Integer dying : dyingRuntimes.keySet()) {
            destroyFor(dying);
        }
        dyingRuntimes.clear();
        stableToRuntime.clear();
        runtimeHeadYaw.clear();
        // Put the region back to its pre-playback state (world mode only),
        // streamed so even huge regions don't freeze the tick loop.
        if (!virtual && liveCaptured) {
            phaseCursor = 0;
            phase = Phase.RESTORE;
        } else {
            phase = Phase.DONE;
        }
    }

    /** Destroy all fake entities and clear runtime mappings (no terrain). */
    private void resetEntities() {
        for (Map.Entry<Integer, Integer> e : stableToRuntime.entrySet()) {
            destroyFor(e.getValue());
        }
        for (Integer dying : dyingRuntimes.keySet()) {
            destroyFor(dying);
        }
        stableToRuntime.clear();
        spawnedFor.clear();
        entityPoses.clear();
        nameByStable.clear();
        runtimeFlags.clear();
        runtimePose.clear();
        runtimeHeadYaw.clear();
        dyingRuntimes.clear();
        // S-8: also clear caughtUp because all fake entities are gone —
        // next tick, refreshViewers() will re-send spawn packets to every
        // current viewer for whatever entities exist post-reset.
        caughtUp.clear();
    }

    /** Apply t=0 entity spawns after a snapshot reset. */
    private void applyT0Events() {
        refreshViewers();
        while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() == 0) {
            applyEvent(timeline.get(appliedIndex));
            appliedIndex++;
        }
    }

    // ---- Region streaming phases (time-boxed per tick) ----

    private void beginCapture() {
        int vol = cuboid.xSize() * cuboid.ySize() * cuboid.zSize();
        liveData = new int[Math.max(0, vol)];
        capturePalIdx = new java.util.LinkedHashMap<>();
        capturePalIdx.put("minecraft:air", 0);
        capturePal = new ArrayList<>();
        capturePal.add("minecraft:air");
        liveNbt.clear();
        liveDataCache.clear();
        phaseCursor = 0;
        phase = Phase.CAPTURE;
    }

    /** @return true when capture completed. */
    private boolean drainCapture() {
        int sx = cuboid.xSize(), sy = cuboid.ySize(), sz = cuboid.zSize();
        long deadline = System.nanoTime() + phaseBudgetNanos;
        int minX = cuboid.min().x(), minY = cuboid.min().y(), minZ = cuboid.min().z();
        int vol = sx * sy * sz;
        while (phaseCursor < vol) {
            int i = phaseCursor++;
            int dx = i % sx;
            int dz = (i / sx) % sz;
            int dy = i / (sx * sz);
            org.bukkit.block.Block b = world.getBlockAt(minX + dx, minY + dy, minZ + dz);
            org.bukkit.block.data.BlockData bd = b.getBlockData();
            String state = bd == null ? "minecraft:air" : bd.getAsString(true);
            Integer pi = capturePalIdx.get(state);
            if (pi == null) {
                pi = capturePal.size();
                capturePalIdx.put(state, pi);
                capturePal.add(state);
            }
            liveData[i] = pi;
            org.bukkit.block.BlockState bs = b.getState();
            if (bs != null && dev.idebugger.echoreplay.record.Snapshotter.needsNbt(bs)) {
                byte[] nb = dev.idebugger.echoreplay.util.NbtBytes.serializeBlockState(bs);
                if (nb != null && nb.length > 0) {
                    liveNbt.put(dx + "," + dy + "," + dz, nb);
                }
            }
            if ((i & 511) == 511 && System.nanoTime() >= deadline) break;
        }
        if (phaseCursor >= vol) {
            livePalette = java.util.List.copyOf(capturePal);
            liveCaptured = true;
            capturePalIdx = null;
            capturePal = null;
            return true;
        }
        return false;
    }

    private void beginSnapshot(double seekTargetMs) {
        catchupTargetMs = seekTargetMs;
        phaseCursor = 0;
        phase = Phase.SNAPSHOT;
    }

    /** @return true when the snapshot fully applied. */
    private boolean drainSnapshot() {
        if (virtual || snapshotData.length == 0) return true;
        long deadline = System.nanoTime() + phaseBudgetNanos;
        int sx = snapshotSizeX, sy = snapshotSizeY, sz = snapshotSizeZ;
        int minX = cuboid.min().x(), minY = cuboid.min().y(), minZ = cuboid.min().z();
        int vol = snapshotData.length;
        while (phaseCursor < vol) {
            int i = phaseCursor++;
            BlockData data = blockDataFor(snapshotData[i]);
            if (data != null) {
                int dx = i % sx;
                int dz = (i / sx) % sz;
                int dy = i / (sx * sz);
                try {
                    world.getBlockAt(minX + dx, minY + dy, minZ + dz)
                            .setBlockData(data, false);
                } catch (Exception ignored) {
                }
            }
            if ((i & 1023) == 1023 && System.nanoTime() >= deadline) break;
        }
        return phaseCursor >= vol;
    }

    /** Snapshot blocks done: NBT, entity reset, t=0 spawns, then run/seek. */
    private void finishSnapshot() {
        applySnapNbt();
        resetEntities();
        applyT0Events();
        if (catchupTargetMs >= 0) {
            // D-7: set the catch-up flag for world-mode seeks too — without
            // this, side-effect events (chat/sound/particle/damage) would
            // flood viewers during the catch-up phase.
            catchingUp = true;
            chatSkippedDuringCatchup = 0;
            sfxSkippedDuringCatchup = 0;
            phase = Phase.CATCHUP;
        } else {
            phase = Phase.RUN;
            if (!seekWasPaused) clock.resume();
            seekWasPaused = false;
        }
    }

    /** Fast-apply events up to the seek target within the per-tick budget. */
    private void drainCatchup() {
        double target = catchupTargetMs;
        long deadline = System.nanoTime() + phaseBudgetNanos;
        int n = 0;
        while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() <= target) {
            TimelineEvent ev = timeline.get(appliedIndex);
            appliedIndex++;
            applyEvent(ev);
            if ((++n & 1023) == 0 && System.nanoTime() >= deadline) return;
        }
        catchupTargetMs = -1;
        clock.seekTo(target);
        phase = Phase.RUN;
        // D-7: clear the suppression flag and surface a summary if we skipped anything.
        catchingUp = false;
        if (chatSkippedDuringCatchup > 0 || sfxSkippedDuringCatchup > 0) {
            String summary = "<gray>Catch-up complete";
            if (chatSkippedDuringCatchup > 0) summary += " (" + chatSkippedDuringCatchup + " chat lines skipped)";
            if (sfxSkippedDuringCatchup > 0) summary += " (" + sfxSkippedDuringCatchup + " sfx events skipped)";
            summary += "</gray>";
            for (Player p : tickViewers) p.sendMessage(Text.mm(summary));
            chatSkippedDuringCatchup = 0;
            sfxSkippedDuringCatchup = 0;
        }
        if (!seekWasPaused) clock.resume();
        seekWasPaused = false;
    }

    /** @return true when restore completed (session fully done). */
    private boolean drainRestore() {
        if (liveData.length == 0) {
            phase = Phase.DONE;
            return true;
        }
        long deadline = System.nanoTime() + phaseBudgetNanos;
        int sx = cuboid.xSize(), sy = cuboid.ySize(), sz = cuboid.zSize();
        int minX = cuboid.min().x(), minY = cuboid.min().y(), minZ = cuboid.min().z();
        int vol = liveData.length;
        while (phaseCursor < vol) {
            int i = phaseCursor++;
            BlockData data = liveDataFor(liveData[i]);
            if (data != null) {
                int dx = i % sx;
                int dz = (i / sx) % sz;
                int dy = i / (sx * sz);
                try {
                    world.getBlockAt(minX + dx, minY + dy, minZ + dz)
                            .setBlockData(data, false);
                } catch (Exception ignored) {
                }
            }
            if ((i & 1023) == 1023 && System.nanoTime() >= deadline) break;
        }
        if (phaseCursor < vol) return false;
        liveDataCache.clear();
        for (Map.Entry<String, byte[]> e : liveNbt.entrySet()) {
            String[] parts = e.getKey().split(",");
            try {
                int relX = Integer.parseInt(parts[0]);
                int relY = Integer.parseInt(parts[1]);
                int relZ = Integer.parseInt(parts[2]);
                var tile = world.getBlockAt(minX + relX, minY + relY, minZ + relZ).getState(true);
                dev.idebugger.echoreplay.util.NbtBytes.applyBlockState(tile, e.getValue());
                tile.update(true);
            } catch (Exception ignored) {
            }
        }
        phase = Phase.DONE;
        return true;
    }

    /** Public view of the current playback viewers (for border particles and external use). */
    public List<Player> liveViewersPublic() {
        return tickViewers.isEmpty() ? liveViewers() : tickViewers;
    }

    /** Returns true when the border should render on this tick (throttling). */
    public boolean shouldRenderBorder(int interval) {
        return (borderTickCounter++ % Math.max(1, interval)) == 0;
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
            case TimelineEvent.Death d -> onDeath(d.npcId());
            case TimelineEvent.Move m -> onMove(m);
            case TimelineEvent.Teleport t -> onTeleport(t);
            case TimelineEvent.Velocity v -> onVelocity(v);
            case TimelineEvent.Animation a -> onAnimation(a);
            case TimelineEvent.Chat c -> onChat(c);
            case TimelineEvent.Equipment eq -> onEquipment(eq);
            case TimelineEvent.Pose p -> onPose(p);
            case TimelineEvent.SneakSprint s -> onSneakSprint(s);
            case TimelineEvent.Damage d -> onDamage(d);
            case TimelineEvent.Sound s -> onSound(s);
            case TimelineEvent.Particle p -> onParticle(p);
            case TimelineEvent.Explosion e -> onExplosion(e);
            case TimelineEvent.EntityStatus s -> onEntityStatus(s);
            default -> {}
        }
    }

    private void onBlockBreakAnim(TimelineEvent.BlockBreakAnim b) {
        // D-7: suppress during catch-up — would otherwise fire hundreds of
        // break animations per tick during a seek.
        if (catchingUp) return;
        Integer runtime = stableToRuntime.get(b.breakerNpcId());
        if (runtime == null) runtime = 0;
        int wx = cuboid.min().x() + b.pos().x();
        int wy = cuboid.min().y() + b.pos().y();
        int wz = cuboid.min().z() + b.pos().z();
        var pkt = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation(
                runtime, new com.github.retrooper.packetevents.util.Vector3i(wx, wy, wz),
                (byte) (b.stage() & 0xFF));
        for (Player p : tickViewers) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, pkt);
        }
    }

    private void applyBlockSet(TimelineEvent.BlockSet b) {
        if (virtual) return; // virtual-write path is a TODO note: viewer-only overlay
        int wx = cuboid.min().x() + b.pos().x();
        int wy = cuboid.min().y() + b.pos().y();
        int wz = cuboid.min().z() + b.pos().z();
        // Palette-parsed BlockData, cached at session start: createBlockData
        // re-parses text on every call and was a major per-block cost.
        BlockData data = blockDataFor(b.paletteIndex());
        if (data == null) return;
        // Physics OFF: neighbor updates (BlockPhysicsEvent storms, flowing
        // liquids, falling sand cascades) are pure waste here — every
        // resulting state is already in the recorded stream and the
        // manager cancels physics inside the cuboid anyway.
        try {
            world.getBlockAt(wx, wy, wz).setBlockData(data, false);
        } catch (Exception ignored) {
            return;
        }
        // Also push the change straight to all viewers so the break/update is
        // guaranteed visible even if the viewer's client didn't get a chunk sync.
        Object packetState = packetStateFor(b.paletteIndex());
        if (packetState instanceof com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState wrapped) {
            var change = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange(
                    new com.github.retrooper.packetevents.util.Vector3i(wx, wy, wz), 0);
            change.setBlockState(wrapped);
            for (Player p : tickViewers) {
                com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacket(p, change);
            }
        }
        // Re-apply block-entity NBT (sign text, container contents, respawn
        // anchor charges, etc.) so these blocks update rather than just place.
        if (b.nbt() != null && b.nbt().length > 0) {
            try {
                var tile = world.getBlockAt(wx, wy, wz).getState(true);
                dev.idebugger.echoreplay.util.NbtBytes.applyBlockState(tile, b.nbt());
                tile.update(true);
            } catch (Exception ignored) {
            }
        }
    }

    /** Replay a player respawn (including KeepInventory armor and equipment). */
    private void onPlayerSpawn(TimelineEvent.PlayerSpawn s) {
        if (stableToRuntime.containsKey(s.npcId())) {
            int existing = stableToRuntime.get(s.npcId());
            entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
            for (Player p : tickViewers) {
                fakes.positionSync(p, existing, s.pos(), s.rot(), true);
            }
            // Also replay equipment for respawned player
            replayPlayerEquipment(existing, s);
            return;
        }
        int runtime = fakes.allocateId();
        stableToRuntime.put(s.npcId(), runtime);
        entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
        nameByStable.put(s.npcId(), s.name() != null ? s.name() : "?");
        // Replay equipment for new spawn
        replayPlayerEquipment(runtime, s);
        for (Player p : tickViewers) {
            fakes.spawnPlayer(p, runtime, s.uuid(), s.name(), s.skin(), s.pos(), s.rot());
            recordSpawnedFor(runtime, p);
        }
    }

    /** Replay player equipment slots (0-5) with KeepInventory support. */
    private void replayPlayerEquipment(int runtime, TimelineEvent.PlayerSpawn s) {
        if (s.equipment() == null) return; // mid-recording joiners carry no kit
        int slot = 0;
        for (byte[] itemBytes : s.equipment()) {
            if (itemBytes != null && itemBytes.length > 0) {
                var item = dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(itemBytes);
                if (!item.isEmpty()) {
                    com.github.retrooper.packetevents.protocol.player.EquipmentSlot peSlot =
                            switch (slot) {
                                case 0 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND;
                                case 1 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND;
                                case 2 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS;
                                case 3 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS;
                                case 4 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE;
                                case 5 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET;
                                default -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND;
                            };
                    var peItem = io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(item);
                    var eqPacket = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment(
                            runtime, java.util.List.of(new com.github.retrooper.packetevents.protocol.player.Equipment(peSlot, peItem)));
                    for (Player p : tickViewers) {
                        com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                                .sendPacket(p, eqPacket);
                    }
                }
            }
            slot++;
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
        // Defensive: if a duplicate EntitySpawn slipped in for the same npc,
        // don't create a second orphaned fake entity — just refresh position.
        if (stableToRuntime.containsKey(s.npcId())) {
            int existing = stableToRuntime.get(s.npcId());
            entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
            for (Player p : tickViewers) {
                fakes.positionSync(p, existing, s.pos(), s.rot(), true);
            }
            return;
        }
        int runtime = fakes.allocateId();
        stableToRuntime.put(s.npcId(), runtime);
        entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
        java.util.List<dev.idebugger.echoreplay.model.RecordedMetadata.Entry> spawnMeta =
                dev.idebugger.echoreplay.model.RecordedMetadata.decodeEntries(s.metadata());
        for (Player p : tickViewers) {
            fakes.spawnMob(p, runtime, s.uuid(), type, s.pos(), s.rot());
            if (!spawnMeta.isEmpty()) {
                java.util.List<EntityData<?>> data = new ArrayList<>();
                for (dev.idebugger.echoreplay.model.RecordedMetadata.Entry entry : spawnMeta) {
                    int idx = entry.index();
                    int kind = entry.type();
                    if (kind == dev.idebugger.echoreplay.model.RecordedMetadata.TYPE_BYTE) {
                        data.add(new EntityData<>(idx, EntityDataTypes.BYTE, (byte) entry.intValue()));
                    } else if (kind == dev.idebugger.echoreplay.model.RecordedMetadata.TYPE_BOOLEAN) {
                        data.add(new EntityData<>(idx, EntityDataTypes.BOOLEAN, entry.intValue() != 0));
                    } else if (kind == dev.idebugger.echoreplay.model.RecordedMetadata.TYPE_ITEMSTACK) {
                        byte[] itemBytes = entry.itemBytes();
                        org.bukkit.inventory.ItemStack bukkitItem = dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(itemBytes);
                        var peItem = io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(bukkitItem);
                        data.add(new EntityData<>(idx, EntityDataTypes.ITEMSTACK, peItem));
                    } else {
                        data.add(new EntityData<>(idx, EntityDataTypes.INT, entry.intValue()));
                    }
                }
                fakes.setMetadata(p, runtime, data);
            }
            recordSpawnedFor(runtime, p);
        }
    }

    /** Send head-look only when the head actually turned (halves move packets). */
    private void syncHead(int stableId, int runtime, float headYaw) {
        Float last = runtimeHeadYaw.get(stableId);
        if (last != null && Float.compare(last, headYaw) == 0) return;
        runtimeHeadYaw.put(stableId, headYaw);
        for (Player p : tickViewers) {
            fakes.headLook(p, runtime, headYaw);
        }
    }

    private void onMove(TimelineEvent.Move m) {
        Integer runtime = stableToRuntime.get(m.npcId());
        if (runtime == null) return;
        entityPoses.put(m.npcId(), new EntityPose(m.pos(), m.rot()));
        for (Player p : tickViewers) {
            fakes.positionSync(p, runtime, m.pos(), m.rot(), m.onGround());
        }
        syncHead(m.npcId(), runtime, m.rot().headYaw());
    }

    private void onTeleport(TimelineEvent.Teleport t) {
        Integer runtime = stableToRuntime.get(t.npcId());
        if (runtime == null) return;
        entityPoses.put(t.npcId(), new EntityPose(t.pos(), t.rot()));
        for (Player p : tickViewers) {
            fakes.positionSync(p, runtime, t.pos(), t.rot(), true);
        }
        // Teleports re-anchor the client entity: always refresh the head.
        runtimeHeadYaw.put(t.npcId(), t.rot().headYaw());
        for (Player p : tickViewers) {
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
        for (Player p : tickViewers) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, anim);
        }
    }

    private void onChat(TimelineEvent.Chat c) {
        // D-7: suppress chat during catch-up. A seek from 0:00 to 10:00 would
        // otherwise dump every chat line from those 10 minutes into a single
        // tick, flooding every viewer's chat window.
        if (catchingUp) {
            chatSkippedDuringCatchup++;
            return;
        }
        String name = nameByStable.getOrDefault(c.npcId(), null);
        net.kyori.adventure.text.Component msg;
        try {
            msg = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    .deserialize(c.json());
        } catch (Exception ex) {
            msg = net.kyori.adventure.text.Component.text(c.json());
        }
        net.kyori.adventure.text.Component full;
        if (name == null || name.isEmpty()) {
            full = msg.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE);
        } else {
            full = net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.text("<" + name + ">")
                            .color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .append(net.kyori.adventure.text.Component.space())
                    .append(msg.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .build();
        }
        String json;
        try {
            json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(full);
        } catch (Exception ex) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(msg);
            json = "{\"text\":\"" + (name == null ? "" : "<" + name + "> ") + plain + "\",\"color\":\"white\"}";
        }
        for (Player p : tickViewers) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p,
                            new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(
                                    false, json));
        }
    }

    private void onEquipment(TimelineEvent.Equipment eq) {
        Integer runtime = stableToRuntime.get(eq.npcId());
        if (runtime == null) return;
        var item = dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(eq.item());
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
        for (Player p : tickViewers) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, eqPacket);
        }
    }

    private void onPose(TimelineEvent.Pose p) {
        Integer runtime = stableToRuntime.get(p.npcId());
        if (runtime == null) return;
        runtimePose.put(runtime, toEntityPose(p.pose()));
        pushStance(runtime);
    }

    private void onSneakSprint(TimelineEvent.SneakSprint s) {
        Integer runtime = stableToRuntime.get(s.npcId());
        if (runtime == null) return;
        runtimeFlags.put(runtime, (byte) (s.flags() & 0xFF));
        pushStance(runtime);
    }

    /** Replay a recorded damage hit: fire the client hurt red-flash animation. */
    private void onDamage(TimelineEvent.Damage d) {
        // D-7: suppress damage flashes during catch-up.
        if (catchingUp) return;
        Integer runtime = stableToRuntime.get(d.npcId());
        if (runtime == null) return;
        // yaw 0 = straight-on hurt flash.
        var hurt = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation(runtime, 0f);
        // Also send the full damage event (sets hurt time / red tint on the client)
        // using the shared "mob attack" source so both players and mobs flash.
        var dmg = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent(
                runtime,
                com.github.retrooper.packetevents.protocol.world.damagetype.DamageTypes.MOB_ATTACK,
                0, 0, null);
        for (Player p : tickViewers) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, hurt);
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                    .sendPacket(p, dmg);
        }
    }

    private void onVelocity(TimelineEvent.Velocity v) {
        Integer runtime = stableToRuntime.get(v.npcId());
        if (runtime == null) return;
        for (Player p : tickViewers) {
            fakes.velocity(p, runtime, v.vel());
        }
    }

    private void onSound(TimelineEvent.Sound s) {
        // D-7: suppress during catch-up. The speed guard alone wasn't enough —
        // catch-up runs with the clock paused at the old speed, so the
        // skipSfxAbove guard never triggered exactly when it was needed.
        if (catchingUp) {
            sfxSkippedDuringCatchup++;
            return;
        }
        // Respect speed-sipping: skip ambience when fast-forwarding
        if (clock.speed() > skipSfxAbove) return;
        org.bukkit.Location loc = new org.bukkit.Location(world, s.pos().x(), s.pos().y(), s.pos().z());
        String key = s.key();
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        org.bukkit.SoundCategory cat;
        try {
            cat = org.bukkit.SoundCategory.valueOf(s.category().toUpperCase());
        } catch (Exception ex) {
            cat = org.bukkit.SoundCategory.MASTER;
        }
        for (Player p : tickViewers) {
            try {
                p.playSound(loc, key, cat, s.volume(), s.pitch());
            } catch (Exception ignored) {}
        }
    }

    private void onParticle(TimelineEvent.Particle p) {
        // D-7: suppress during catch-up.
        if (catchingUp) {
            sfxSkippedDuringCatchup++;
            return;
        }
        if (clock.speed() > skipSfxAbove) return;
        String raw = p.particleKey();
        String key = raw.contains(":") ? raw.substring(raw.indexOf(":") + 1) : raw;
        org.bukkit.Particle bukkitPart;
        try {
            bukkitPart = org.bukkit.Particle.valueOf(key.toUpperCase());
        } catch (Exception ex) {
            return;
        }
        org.bukkit.Location loc = new org.bukkit.Location(world, p.pos().x(), p.pos().y(), p.pos().z());
        for (Player viewer : tickViewers) {
            try {
                viewer.spawnParticle(bukkitPart, loc, p.count(), p.dx(), p.dy(), p.dz(), p.speed());
            } catch (Exception ignored) {}
        }
    }

    private void onExplosion(TimelineEvent.Explosion e) {
        // D-7: suppress during catch-up.
        if (catchingUp) return;
        org.bukkit.Location loc = new org.bukkit.Location(world, e.pos().x(), e.pos().y(), e.pos().z());
        for (Player p : tickViewers) {
            try {
                p.spawnParticle(org.bukkit.Particle.EXPLOSION, loc, 1);
                p.playSound(loc, "entity.generic.explode", org.bukkit.SoundCategory.BLOCKS, 1f, 1f);
            } catch (Exception ignored) {}
        }
    }

    private void onEntityStatus(TimelineEvent.EntityStatus s) {
        Integer runtime = stableToRuntime.get(s.npcId());
        if (runtime == null) return;
        for (Player p : tickViewers) {
            fakes.entityStatus(p, runtime, s.status() & 0xFF);
        }
    }

    private static com.github.retrooper.packetevents.protocol.entity.pose.EntityPose toEntityPose(int id) {
        for (com.github.retrooper.packetevents.protocol.entity.pose.EntityPose p
                : com.github.retrooper.packetevents.protocol.entity.pose.EntityPose.values()) {
            if (p.ordinal() == id) return p;
        }
        return com.github.retrooper.packetevents.protocol.entity.pose.EntityPose.STANDING;
    }

    /** Build and broadcast the merged stance metadata (flags + pose). */
    private void pushStance(int runtime) {
        byte flags = (byte) (runtimeFlags.getOrDefault(runtime, (byte) 0)
                & (FLAG_MASK_CROUCHED | FLAG_MASK_SPRINTING | FLAG_MASK_SWIMMING));
        com.github.retrooper.packetevents.protocol.entity.pose.EntityPose pose =
                runtimePose.getOrDefault(runtime,
                        com.github.retrooper.packetevents.protocol.entity.pose.EntityPose.STANDING);
        java.util.List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, flags));
        data.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, pose));
        // NOTE: only base indices 0 and 6 are sent. Do NOT add an eye-height /
        // other-index entry here: non-standard metadata indices for some entity
        // types cause the client to fail decoding -> Network Protocol Error kick.
        for (Player p : tickViewers) {
            fakes.setMetadata(p, runtime, data);
        }
    }

    private void despawn(int stableId) {
        Integer runtime = stableToRuntime.remove(stableId);
        entityPoses.remove(stableId);
        runtimeHeadYaw.remove(stableId);
        if (runtime == null) return;
        destroyFor(runtime);
    }

    /** Replay a recorded death: play the death animation, then destroy lazily. */
    private void onDeath(int stableId) {
        Integer runtime = stableToRuntime.remove(stableId);
        entityPoses.remove(stableId);
        runtimeHeadYaw.remove(stableId);
        nameByStable.remove(stableId);
        if (runtime == null) return;
        for (Player p : tickViewers) {
            fakes.entityStatus(p, runtime, 3); // death status
            // Force the client to render the entity as fallen/dying via pose.
            fakes.setMetadata(p, runtime, java.util.List.of(
                    new EntityData<>(6, EntityDataTypes.ENTITY_POSE,
                            com.github.retrooper.packetevents.protocol.entity.pose.EntityPose.DYING)));
        }
        dyingRuntimes.put(runtime, DEATH_DELAY_TICKS);
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
                dev.idebugger.echoreplay.util.NbtBytes.applyBlockState(tile, e.getValue());
                tile.update(true);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Capture the region's current (pre-playback) block state into local palette /
     * data arrays plus tile-entity NBT, so stopplay can undo world-mode changes.
     */
    private void captureLiveTerrain() {
        int sx = cuboid.xSize(), sy = cuboid.ySize(), sz = cuboid.zSize();
        java.util.Map<String, Integer> palIdx = new java.util.LinkedHashMap<>();
        java.util.List<String> pal = new ArrayList<>();
        palIdx.put("minecraft:air", 0);
        pal.add("minecraft:air");
        int[] data = new int[sx * sy * sz];
        int idx = 0;
        for (int dy = 0; dy < sy; dy++) {
            for (int dz = 0; dz < sz; dz++) {
                for (int dx = 0; dx < sx; dx++) {
                    org.bukkit.block.Block b = world.getBlockAt(
                            cuboid.min().x() + dx, cuboid.min().y() + dy, cuboid.min().z() + dz);
                    String state = b.getBlockData() == null
                            ? "minecraft:air" : b.getBlockData().getAsString(true);
                    Integer pi = palIdx.get(state);
                    if (pi == null) {
                        pi = pal.size();
                        palIdx.put(state, pi);
                        pal.add(state);
                    }
                    data[idx++] = pi;
                    org.bukkit.block.BlockState bs = b.getState();
                    if (bs != null && dev.idebugger.echoreplay.record.Snapshotter.needsNbt(bs)) {
                        byte[] nb = dev.idebugger.echoreplay.util.NbtBytes.serializeBlockState(bs);
                        if (nb != null && nb.length > 0) {
                            liveNbt.put(dx + "," + dy + "," + dz, nb);
                        }
                    }
                }
            }
        }
        livePalette = pal;
        liveData = data;
    }

    /** Re-apply the pre-playback terrain captured when playback started. */
    private void restoreLiveTerrain() {
        if (liveData.length == 0) return;
        int idx = 0;
        for (int dy = 0; dy < cuboid.ySize(); dy++) {
            for (int dz = 0; dz < cuboid.zSize(); dz++) {
                for (int dx = 0; dx < cuboid.xSize(); dx++) {
                    int pi = liveData[idx++];
                    try {
                        world.getBlockAt(cuboid.min().x() + dx, cuboid.min().y() + dy,
                                        cuboid.min().z() + dz)
                                .setBlockData(Bukkit.createBlockData(livePalette.get(pi)), false);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        for (Map.Entry<String, byte[]> e : liveNbt.entrySet()) {
            String[] parts = e.getKey().split(",");
            try {
                int relX = Integer.parseInt(parts[0]);
                int relY = Integer.parseInt(parts[1]);
                int relZ = Integer.parseInt(parts[2]);
                var tile = world.getBlockAt(cuboid.min().x() + relX, cuboid.min().y() + relY,
                                cuboid.min().z() + relZ).getState(true);
                dev.idebugger.echoreplay.util.NbtBytes.applyBlockState(tile, e.getValue());
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
