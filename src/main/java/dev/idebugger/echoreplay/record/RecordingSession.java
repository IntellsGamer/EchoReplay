package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.util.PalettedStorage;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents one live recording. Freezes a cuboid, maintains an EventSink, and
 * tracks entity UUID -> stable npcId mapping for the duration of the take.
 */
public final class RecordingSession {

    private final World world;
    private final Cuboid cuboid;
    private final String name;
    private final UUID recorderUuid;
    private final String recorderName;
    private final long startedMillis = System.currentTimeMillis();
    private final AtomicLong mediaClock = new AtomicLong(0);
    private final EventSink sink = new EventSink();
    private final AtomicInteger npcIdAlloc = new AtomicInteger(1);
    private final Map<UUID, Integer> idMap = new HashMap<>();
    // UUIDs that already had an EntitySpawn emitted (dedupes the event-listener
    // path against the per-tick recorder path so a given entity only ever gets
    // one spawn event).
    private final java.util.Set<UUID> entitySpawned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // global palette over the whole recording
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private final java.util.List<String> paletteList = new java.util.ArrayList<>();

    // last known player equipment before death, keyed by UUID
    private final Map<UUID, java.util.List<byte[]>> lastPlayerEquipment = new HashMap<>();

    // initial snapshot block storage (filled during SNAPSHOTTING on main thread)
    private PalettedStorage snapshotStorage;
    private final Map<String, byte[]> snapshotNbt = new java.util.LinkedHashMap<>();

    // relative positions of every state change are 16-bit; snapshot must fit.
    private volatile State state = State.SNAPSHOTTING;
    private final AtomicInteger sectionsDone = new AtomicInteger(0);
    private int totalSections;
    private int blocksPerTick = 8000;

    public enum State { SNAPSHOTTING, RECORDING, FINALIZING, CANCELLED }

    public RecordingSession(World world, Cuboid cuboid, String name, UUID recorderUuid, String recorderName) {
        this.world = world;
        this.cuboid = cuboid;
        this.name = name;
        this.recorderUuid = recorderUuid;
        this.recorderName = recorderName;
        paletteIndex.put("minecraft:air", 0);
        paletteList.add("minecraft:air");
    }

    public World world() { return world; }
    public Cuboid cuboid() { return cuboid; }
    public String name() { return name; }
    public UUID recorderUuid() { return recorderUuid; }
    public String recorderName() { return recorderName; }
    public EventSink sink() { return sink; }
    public State state() { return state; }
    public long startedMillis() { return startedMillis; }
    public long mediaMillis() { return mediaClock.get(); }
    public int totalSections() { return totalSections; }
    public int sectionsDone() { return sectionsDone.get(); }
    /** P-9: depth of the unflushed event sink, for /er stats. -1 if session not active. */
    public int sinkDepth() { return sink.size(); }

    public void setTotalSections(int t) { this.totalSections = t; }
    public void setBlocksPerTick(int b) { this.blocksPerTick = b; }
    public int blocksPerTick() { return blocksPerTick; }

    public void setRecording() { this.state = State.RECORDING; }
    public void setFinalizing() { this.state = State.FINALIZING; }
    public void setCancelled() { this.state = State.CANCELLED; }
    public void markSection() { sectionsDone.incrementAndGet(); }

    /** Lazily initialize (on main thread) the snapshot storage for this take. */
    public synchronized PalettedStorage snapshotStorage() {
        if (snapshotStorage == null) {
            snapshotStorage = new PalettedStorage(cuboid.xSize(), cuboid.ySize(), cuboid.zSize());
        }
        return snapshotStorage;
    }

    public synchronized void putSnapshotNbt(BlockPos rel, byte[] nbt) {
        snapshotNbt.put(rel.x() + "," + rel.y() + "," + rel.z(), nbt);
    }

    public Map<String, byte[]> snapshotNbt() {
        return snapshotNbt;
    }

    /** Register or look up a stable npcId for a UUID. */
    public synchronized int npcIdFor(UUID uuid) {
        Integer id = idMap.get(uuid);
        if (id == null) {
            id = npcIdAlloc.getAndIncrement();
            idMap.put(uuid, id);
        }
        return id;
    }

    /**
     * Returns true when this is the first EntitySpawn emission for the uuid
     * across all recorders, so a single hot spawn produces exactly one spawn
     * event (and subsequent tick-recordings only emit moves).
     */
    public boolean markEntitySpawned(UUID uuid) {
        return entitySpawned.add(uuid);
    }

    public void unmarkEntitySpawned(UUID uuid) {
        entitySpawned.remove(uuid);
    }

    /** Clear the per-entity spawn bookkeeping when a fresh recording starts. */
    public void clearEntitySpawned() {
        entitySpawned.clear();
    }

    /**
     * Intern a block state string into the recording palette, returning the
     * palette index to reference in BlockSet events.
     */
    public synchronized int paletteIndex(String stateString) {
        Integer idx = paletteIndex.get(stateString);
        if (idx == null) {
            idx = paletteList.size();
            paletteIndex.put(stateString, idx);
            paletteList.add(stateString);
        }
        return idx;
    }

    public synchronized String paletteState(int index) {
        return paletteList.get(index);
    }

    public java.util.List<String> snapshotPalette() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(paletteList));
    }

    /** Emit an event with current media time. */
    public void emit(TimelineEvent e) {
        sink.add(e);
    }

    public synchronized void cachePlayerEquipment(UUID uuid, java.util.List<byte[]> equipment) {
        lastPlayerEquipment.put(uuid, equipment);
    }

    public synchronized java.util.List<byte[]> takeCachedPlayerEquipment(UUID uuid) {
        java.util.List<byte[]> out = lastPlayerEquipment.remove(uuid);
        return out != null ? out : java.util.Collections.emptyList();
    }

    public void advanceClock(long deltaMs) {
        mediaClock.addAndGet(deltaMs);
    }
}
