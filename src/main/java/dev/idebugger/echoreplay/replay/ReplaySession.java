package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.Vec3d;
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
    private final boolean driveWorldTime;
    private final boolean liveBackup;
    private final boolean forceSpectator;
    private volatile boolean entityIdExhaustedLogged = false;

    // Late joiners (mid-playback /er watch, or players who walk into the
    // auto-watch radius) are synced to the current state by snapshot-spawning
    // every live entity (current state, live runtime id) plus streaming the
    // past block changes in virtual mode, targeting only them.
    // value = next block-event index to apply (virtual mode only).
    private final Map<UUID, Integer> pendingSyncs = new HashMap<>();
    // Per late-joiner: which stable ids have already been snapshot-spawned.
    private final Map<UUID, java.util.Set<Integer>> syncedStablesFor = new HashMap<>();
    // Last spawn event per stable id, so a late joiner can be given the
    // entity's skin/equipment/type instead of re-deriving it.
    private final Map<Integer, TimelineEvent.PlayerSpawn> playerSpawnByStable = new HashMap<>();
    private final Map<Integer, TimelineEvent.EntitySpawn> entitySpawnByStable = new HashMap<>();
    // True while fast-applying events out of live time (seek catch-up):
    // transient effects (sound, particle, chat, damage flash) are skipped so
    // a 10-minute seek does not replay a decade of chat and SFX at once.
    private volatile boolean silentApply = false;
    // Viewers force-switched to spectator: their previous gamemode, restored
    // when they leave or playback stops.
    private final Map<UUID, org.bukkit.GameMode> forcedModes = new HashMap<>();

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
        driveWorldTime = plugin.cfg().getBoolean("replay.drive-world-time", false);
        liveBackup = plugin.cfg().getBoolean("replay.backup-live-cuboid", true);
        forceSpectator = plugin.cfg().getBoolean("replay.force-spectator", false);
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
        }
        if (forceSpectator && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            forcedModes.put(p.getUniqueId(), p.getGameMode());
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
        }
        // If playback is already running, catch this viewer up to the current
        // state (entities always; blocks in virtual mode).
        if (started && phase == Phase.RUN) {
            pendingSyncs.put(p.getUniqueId(), 0);
        }
    }

    public void removeViewer(Player p) {
        viewers.remove(p.getUniqueId());
        pendingSyncs.remove(p.getUniqueId());
        syncedStablesFor.remove(p.getUniqueId());
        // If they were spectating, put their own state back (inventory/vitals
        // are still settable during the quit event — teleport is a no-op then)
        // and hand the place back to the fake for the remaining viewers.
        // Paused players are already restored — just drop their auto re-possess
        // (a relog is a fresh session; they spectate manually again).
        Integer stable = spectateStable.remove(p.getUniqueId());
        SpectateSave save = spectateSave.remove(p.getUniqueId());
        lastAppliedInventoryHash.remove(p.getUniqueId());
        if (stable != null) {
            restoreSpectate(p, save);
            maybeRespawnSpectatedFake(stable);
        }
        for (Set<UUID> set : pausedSpectate.values()) set.remove(p.getUniqueId());
        restoreForcedMode(p);
        resetViewerSky(p);
    }

    private void restoreForcedMode(Player p) {
        org.bukkit.GameMode prev = forcedModes.remove(p.getUniqueId());
        if (prev != null && p.isOnline() && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            p.setGameMode(prev);
        }
    }

    public boolean isViewer(Player p) {
        return viewers.contains(p.getUniqueId());
    }

    public List<UUID> viewerIds() {
        return new ArrayList<>(viewers);
    }

    // --- Camera: per-player live follow of a recorded entity -------------
    private final Map<UUID, Integer> camStable = new HashMap<>();
    private final Map<UUID, org.bukkit.GameMode> camPrevMode = new HashMap<>();

    /** Start following a recorded entity by name. Returns false if not live. */
    public boolean startCamera(Player p, String name) {
        Integer stable = null;
        for (Map.Entry<Integer, String> e : nameByStable.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(name)) {
                stable = e.getKey();
                break;
            }
        }
        if (stable == null) return false;
        camStable.put(p.getUniqueId(), stable);
        if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            camPrevMode.put(p.getUniqueId(), p.getGameMode());
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
        }
        return true;
    }

    /** Stop following and restore the player's previous gamemode if needed. */
    public boolean stopCamera(Player p) {
        if (camStable.remove(p.getUniqueId()) == null) return false;
        org.bukkit.GameMode prev = camPrevMode.remove(p.getUniqueId());
        if (prev != null && p.isOnline() && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            p.setGameMode(prev);
        }
        return true;
    }

    public boolean isCameraman(Player p) {
        return camStable.containsKey(p.getUniqueId());
    }

    /** Names of currently live recorded entities (for tab completion). */
    public List<String> liveEntityNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String n : nameByStable.values()) {
            if (n != null) out.add(n);
        }
        return new ArrayList<>(out);
    }

    /** Teleport each cameraman to the live position of the entity they follow.
     *  Every tick by design: recording samples every packet, so playback must
     *  drive every tick for perfect real-time motion (no deadbands). */
    public void driveCameras() {
        if (camStable.isEmpty()) return;
        for (java.util.Iterator<Map.Entry<UUID, Integer>> it = camStable.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Integer> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                camPrevMode.remove(e.getKey());
                continue;
            }
            EntityPose pose = entityPoses.get(e.getValue());
            if (pose == null) continue; // target died — will be re-located on respawn
            try {
                org.bukkit.Location loc = new org.bukkit.Location(world,
                        pose.pos().x(), pose.pos().y() + 1.0, pose.pos().z(),
                        pose.rot().yaw(), 0f);
                teleportSpectator(p, loc);
            } catch (Exception ignored) {
            }
        }
    }

    /** Release every camera and restore gamemodes (called when playback stops). */
    private void releaseCameras() {
        for (Map.Entry<UUID, org.bukkit.GameMode> cm : camPrevMode.entrySet()) {
            Player p = Bukkit.getPlayer(cm.getKey());
            if (p != null && p.isOnline() && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                p.setGameMode(cm.getValue());
            }
        }
        camStable.clear();
        camPrevMode.clear();
    }

    // --- First-person spectate: become a recorded player ------------------
    // The target's fake entity is destroyed and the REAL player is driven
    // instead: every tick they are teleported to the recorded pose, and
    // recorded vitals / inventory / equipment updates are applied to their
    // real player state. Their own state is saved and restored on
    // stopspectate (or when playback ends / the target dies or leaves).
    private record SpectateSave(org.bukkit.Location loc, float health, int food,
                                 float saturation, org.bukkit.GameMode mode, int heldSlot,
                                 byte[][] inventory) {}
    private final Map<UUID, Integer> spectateStable = new HashMap<>();
    private final Map<UUID, SpectateSave> spectateSave = new HashMap<>();
    /** Stable id -> real players PAUSED on it: restored + told the target
     *  left the region; they are re-possessed automatically on the target's
     *  next PlayerSpawn (re-entry). Only {@code stopspectate} cancels this. */
    private final Map<Integer, Set<UUID>> pausedSpectate = new HashMap<>();
    // Live caches so spectating can be started mid-playback with the current
    // recorded state (cleared on entity reset).
    private record Vitals(float health, int food, float saturation) {}
    private final Map<Integer, Vitals> lastVitalsByStable = new HashMap<>();
    private final Map<Integer, byte[][]> lastInventoryByStable = new HashMap<>();
    private final Map<Integer, byte[][]> lastEquipmentByStable = new HashMap<>();
    private final Map<Integer, Integer> lastGameModeByStable = new HashMap<>();
    private final Map<Integer, Integer> lastHeldSlotByStable = new HashMap<>();
    // Spectate driver dedup: last inventory hash applied per spectator (skips
    // identical full-inventory rewrites, which flicker the client).
    private final Map<UUID, Integer> lastAppliedInventoryHash = new HashMap<>();

    /**
     * Become the recorded player {@code name}: destroys their fake entity and
     * drives the real player (pose + vitals + inventory) from the recording.
     * Returns false when no live recorded player has that name.
     */
    public boolean startSpectate(Player p, String name) {
        Integer stable = null;
        for (Map.Entry<Integer, String> e : nameByStable.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(name)) {
                stable = e.getKey();
                break;
            }
        }
        // Must still be alive (entityPoses is cleared on death/leave).
        if (stable == null || !entityPoses.containsKey(stable)) return false;
        if (spectateStable.containsKey(p.getUniqueId())) return true;
        return possess(p, stable);
    }

    /** Take over the recorded player's place. Shared by the command and by
     *  automatic re-possess when a paused target re-enters the region. */
    private boolean possess(Player p, int stable) {
        // Manual spectate also cancels any pending auto re-possess.
        Set<UUID> pausedHere = pausedSpectate.get(stable);
        if (pausedHere != null) pausedHere.remove(p.getUniqueId());
        // Cam would fight the spectate driver over the same player's teleports.
        stopCamera(p);
        if (!viewers.contains(p.getUniqueId())) viewers.add(p.getUniqueId());

        // Save the player's real state for restoration.
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int ownHeld = 0;
        try {
            ownHeld = inv.getHeldItemSlot();
        } catch (Exception ignored) {
        }
        spectateSave.put(p.getUniqueId(), new SpectateSave(
                p.getLocation().clone(), (float) p.getHealth(), p.getFoodLevel(),
                p.getSaturation(), p.getGameMode(), ownHeld,
                dev.idebugger.echoreplay.record.EntityTickRecorder.serializeInventory(inv)));
        spectateStable.put(p.getUniqueId(), stable);

        // Destroy the fake so the real player takes its place.
        Integer runtime = stableToRuntime.remove(stable);
        if (runtime != null) destroyFor(runtime);

        // Mirror the recorded player's gamemode (saved + restored on stop).
        // State is read from the timeline itself, not just the applied-event
        // caches: spectating right after /er play (clock ~0, nothing applied
        // yet) must still see creative, not the survival fallback. When the
        // recording carries no gamemode at all, keep the player's own mode.
        Integer recModeVal = recordedModeValue(stable);
        org.bukkit.GameMode recMode;
        if (recModeVal != null && recModeVal >= 0 && recModeVal <= 3) {
            org.bukkit.GameMode gm = null;
            try {
                gm = org.bukkit.GameMode.getByValue(recModeVal);
            } catch (Exception ignored) {
            }
            // Falls back to survival only for unknown values; every real mode
            // (survival/creative/adventure/spectator) mirrors as-is.
            recMode = gm != null ? gm : org.bukkit.GameMode.SURVIVAL;
        } else if (recModeVal != null) {
            recMode = org.bukkit.GameMode.SURVIVAL;
        } else {
            recMode = p.getGameMode();
        }
        if (p.getGameMode() != recMode) {
            try {
                p.setGameMode(recMode);
            } catch (Exception ignored) {
            }
        }

        // Apply the recorded player's current state.
        EntityPose pose = entityPoses.get(stable);
        if (pose != null) {
            try {
                teleportSpectator(p, new org.bukkit.Location(world, pose.pos().x(), pose.pos().y(), pose.pos().z(),
                        pose.rot().headYaw(), pose.rot().pitch()));
            } catch (Exception ignored) {}
        }
        Vitals vitals = lastVitalsByStable.get(stable);
        if (vitals != null) {
            applyVitalsToPlayer(p, vitals.health(), vitals.food(), vitals.saturation());
        }
        byte[][] recInv = lastInventoryByStable.get(stable);
        if (recInv != null) {
            applyInventoryToPlayer(p, recInv);
        } else {
            byte[][] eq = lastEquipmentByStable.get(stable);
            if (eq != null) {
                Integer heldNow = recordedHeldSlot(stable);
                applyInventoryToPlayer(p, equipmentAsInventory(eq, heldNow != null ? heldNow : 0));
            }
        }
        Integer held = recordedHeldSlot(stable);
        if (held != null) {
            applyHeldSlot(p, held);
        }
        return true;
    }

    /** Latest recorded gamemode value for a stable at or before the current
     *  playback position. Reads the applied-event cache first, then scans the
     *  already-passed timeline so spectating mid-playback (or right at the
     *  start) still mirrors correctly. Null when the recording has none. */
    private Integer recordedModeValue(int stable) {
        Integer cached = lastGameModeByStable.get(stable);
        if (cached != null) return cached;
        Integer found = null;
        for (int i = 0; i < appliedIndex && i < timeline.size(); i++) {
            TimelineEvent ev = timeline.get(i);
            if (ev instanceof TimelineEvent.GameMode g && g.npcId() == stable) found = g.mode();
        }
        if (found != null) lastGameModeByStable.put(stable, found);
        return found;
    }

    /** Latest recorded held slot for a stable at or before the current
     *  playback position (same backfill as gamemode). */
    private Integer recordedHeldSlot(int stable) {
        Integer cached = lastHeldSlotByStable.get(stable);
        if (cached != null) return cached;
        Integer found = null;
        for (int i = 0; i < appliedIndex && i < timeline.size(); i++) {
            TimelineEvent ev = timeline.get(i);
            if (ev instanceof TimelineEvent.HeldSlot h && h.npcId() == stable) found = h.slot();
        }
        if (found != null) lastHeldSlotByStable.put(stable, found);
        return found;
    }

    /** Recorded gamemode for event-time application (cache only; the event
     *  itself is the latest truth at that point). */
    private org.bukkit.GameMode recordedMode(int stable) {
        Integer m = lastGameModeByStable.get(stable);
        if (m != null && m >= 0 && m <= 3) {
            try {
                org.bukkit.GameMode gm = org.bukkit.GameMode.getByValue(m);
                if (gm != null) return gm;
            } catch (Exception ignored) {
            }
        }
        return org.bukkit.GameMode.SURVIVAL;
    }

    /** Apply a recorded held hotbar slot (0-8) to a spectating player. */
    private static void applyHeldSlot(Player p, int slot) {
        if (slot < 0 || slot > 8) return;
        try {
            if (p.getInventory().getHeldItemSlot() != slot) {
                p.getInventory().setHeldItemSlot(slot);
            }
        } catch (Exception ignored) {
        }
    }

    /** Stop spectating: restore the player's saved state and re-spawn the fake. */
    public boolean stopSpectate(Player p) {
        lastAppliedInventoryHash.remove(p.getUniqueId());
        Integer stable = spectateStable.remove(p.getUniqueId());
        if (stable != null) {
            SpectateSave save = spectateSave.remove(p.getUniqueId());
            restoreSpectate(p, save);
            maybeRespawnSpectatedFake(stable);
            return true;
        }
        // Paused (target left the region): stopspectate OVERRIDES the pending
        // auto re-possess.
        for (var it = pausedSpectate.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Set<UUID>> en = it.next();
            if (en.getValue().remove(p.getUniqueId())) {
                if (en.getValue().isEmpty()) it.remove();
                return true;
            }
        }
        return false;
    }

    /** Tell live spectators their target died — spectate CONTINUES through
     *  the death (no damage to the real player) and snaps to the respawn. */
    private void notifySpectatorsOfDeath(int stable) {
        String name = nameByStable.getOrDefault(stable, "?");
        for (Map.Entry<UUID, Integer> e : spectateStable.entrySet()) {
            if (e.getValue() != stable) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                p.sendMessage(Text.mm("<gray>💀 <aqua>" + name + "</aqua> <gray>died — you stay with them. "
                        + "You'll snap to their respawn."));
            }
        }
    }

    /** Target left the region (or the server): restore every spectator on it
     *  and park them as paused — they are re-possessed automatically when the
     *  target's PlayerSpawn arrives again (re-entry). */
    private void pauseSpectateForStable(int stable) {
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(spectateStable.entrySet())) {
            if (e.getValue() != stable) continue;
            spectateStable.remove(e.getKey());
            SpectateSave save = spectateSave.remove(e.getKey());
            lastAppliedInventoryHash.remove(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            restoreSpectate(p, save);
            pausedSpectate.computeIfAbsent(stable, k -> new HashSet<>()).add(e.getKey());
            if (p != null && p.isOnline()) {
                p.sendMessage(Text.mm("<gray>⚠ <aqua>" + nameByStable.getOrDefault(stable, "?")
                        + "</aqua> <gray>left the region — spectate paused. You'll be re-possessed "
                        + "when they come back (or <yellow>/er stopspectate</yellow> <gray>to end it)."));
            }
        }
    }

    /** Target re-entered the region: re-possess every paused spectator. */
    private void repossessPaused(int stable, String spawnName) {
        Set<UUID> paused = pausedSpectate.remove(stable);
        if (paused == null || paused.isEmpty()) return;
        String name = spawnName != null ? spawnName : nameByStable.getOrDefault(stable, "?");
        for (UUID id : paused) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !viewers.contains(id)) continue;
            if (possess(p, stable)) {
                p.sendMessage(Text.mm("<gray>✓ <aqua>" + name + "</aqua> <gray>is back in the region — "
                        + "spectating again."));
            }
        }
    }

    /** Re-spawn a player's fake after an in-region death+respawn (no new
     *  PlayerSpawn arrives — poses just resume). Skips the real players who
     *  currently possess this stable, so they don't see a duplicate self. */
    private void respawnPlayerAt(int stable, Vec3d pos, Rotation rot) {
        TimelineEvent.PlayerSpawn spawn = playerSpawnByStable.get(stable);
        if (spawn == null || stableToRuntime.containsKey(stable)) return;
        if (fakes.isExhausted()) {
            warnIdExhausted();
            return;
        }
        int runtime = fakes.allocateId();
        stableToRuntime.put(stable, runtime);
        entityPoses.put(stable, new EntityPose(pos, rot));
        runtimeHeadYaw.put(stable, rot.headYaw());
        Set<UUID> possessors = new HashSet<>();
        for (Map.Entry<UUID, Integer> e : spectateStable.entrySet()) {
            if (e.getValue() == stable) possessors.add(e.getKey());
        }
        for (Player p : tickViewers) {
            if (possessors.contains(p.getUniqueId())) continue;
            fakes.spawnPlayer(p, runtime, spawn.uuid(), spawn.name(), spawn.skin(), pos, rot);
            recordSpawnedFor(runtime, p);
        }
        byte[][] eq = lastEquipmentByStable.get(stable);
        if (eq != null) sendEquipmentBytes(runtime, eq);
        pushStance(runtime);
        for (Player p : tickViewers) {
            fakes.headLook(p, runtime, rot.headYaw());
        }
    }

    /** Put a real player back to their saved state (null-safe, online-checked). */
    private void restoreSpectate(Player p, SpectateSave save) {
        if (save == null || p == null || !p.isOnline()) return;
        try {
            if (save.inventory() != null) applyInventoryToPlayer(p, save.inventory());
            applyHeldSlot(p, save.heldSlot());
            p.setFoodLevel(save.food());
            p.setSaturation(save.saturation());
            // Spectate owns the gamemode while active (it mirrors the
            // recording), so always put the saved mode back on stop.
            if (save.mode() != null && p.getGameMode() != save.mode()) {
                p.setGameMode(save.mode());
            }
            applyVitalsToPlayer(p, save.health(), save.food(), save.saturation());
            p.teleport(save.loc());
        } catch (Exception ignored) {}
    }

    public boolean isSpectating(Player p) {
        return spectateStable.containsKey(p.getUniqueId());
    }

    /** Re-spawn the fake of a spectated stable when nobody spectates it anymore. */
    private void maybeRespawnSpectatedFake(int stable) {
        for (Integer s : spectateStable.values()) {
            if (s.intValue() == stable) return; // another real player still spectates it
        }
        EntityPose pose = entityPoses.get(stable);
        TimelineEvent.PlayerSpawn spawn = playerSpawnByStable.get(stable);
        if (pose == null || spawn == null) return; // target died/left — nothing to re-spawn
        if (stableToRuntime.containsKey(stable) || fakes.isExhausted()) return;
        int runtime = fakes.allocateId();
        stableToRuntime.put(stable, runtime);
        List<Player> targets = liveViewers();
        for (Player p : targets) {
            fakes.spawnPlayer(p, runtime, spawn.uuid(), spawn.name(), spawn.skin(), pose.pos(), pose.rot());
            recordSpawnedFor(runtime, p);
        }
        byte[][] eq = lastEquipmentByStable.get(stable);
        if (eq != null) sendEquipmentBytes(runtime, eq);
        pushStance(runtime);
    }

    /**
     * Release every spectate (playback stopping or entity reset). No fake
     * re-spawn here: the session is ending or rebuilding its entities anyway.
     */
    private void releaseSpectates() {
        for (UUID id : new ArrayList<>(spectateStable.keySet())) {
            spectateStable.remove(id);
            SpectateSave save = spectateSave.remove(id);
            lastAppliedInventoryHash.remove(id);
            restoreSpectate(Bukkit.getPlayer(id), save);
        }
        lastAppliedInventoryHash.clear();
        // Paused spectators were already restored when paused.
        pausedSpectate.clear();
    }

    /** Teleport every real player to the live recorded pose of their target.
     *  Every tick by design: recording samples every movement packet, so the
     *  driver must follow every tick for perfect real-time motion. Any
     *  deadband/interval here reads as lagged-video jumps. */
    private void driveSpectators() {
        if (spectateStable.isEmpty()) return;
        for (java.util.Iterator<Map.Entry<UUID, Integer>> it = spectateStable.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Integer> e = it.next();
            UUID uuid = e.getKey();
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                it.remove();
                spectateSave.remove(uuid);
                lastAppliedInventoryHash.remove(uuid);
                continue;
            }
            EntityPose pose = entityPoses.get(e.getValue());
            if (pose == null) continue; // target died / left — stay put;
                                        // spectate resumes on respawn / re-entry
            try {
                // Body follows the motion, view follows the recorded head.
                teleportSpectator(p, new org.bukkit.Location(world,
                        pose.pos().x(), pose.pos().y(), pose.pos().z(),
                        pose.rot().headYaw(), pose.rot().pitch()));
            } catch (Exception ignored) {}
            // Pin health/hunger to the recording every tick: outside mobs and
            // hazards can still hit the real body, and with damage cancelled
            // (see ReplayManager guards) plus this lock, hits do nothing.
            Vitals v = lastVitalsByStable.get(e.getValue());
            if (v != null) applyVitalsToPlayer(p, v.health(), v.food(), v.saturation());
        }
    }

    /** Ordered sync teleport (keeps per-tick motion exact) + fall-distance
     *  reset so anticheats (e.g. Nyx Fly) don't flag driven movement. */
    private static void teleportSpectator(Player p, org.bukkit.Location loc) {
        try {
            p.teleport(loc);
        } catch (Exception ignored) {}
        try {
            p.setFallDistance(0);
        } catch (Exception ignored) {}
    }

    /** Cosmetic vitals: never actually hurts (clamped above lethal). Skips
     *  redundant sets — every setHealth/setFood call re-sends attributes and
     *  contributes to client stutter. */
    private void applyVitalsToPlayer(Player p, float health, int food, float saturation) {
        try {
            float h = Math.max(1.0f, Math.min(health, (float) p.getMaxHealth()));
            if (Math.abs((float) p.getHealth() - h) > 0.05f) p.setHealth(h);
            int f = Math.max(0, Math.min(20, food));
            if (p.getFoodLevel() != f) p.setFoodLevel(f);
            float s = Math.max(0f, Math.min(20f, saturation));
            if (Math.abs(p.getSaturation() - s) > 0.05f) p.setSaturation(s);
        } catch (Exception ignored) {}
    }

    /** Apply a 41-slot recorded inventory to a real player. Skips re-applying
     *  an identical snapshot (full setContents/armor rewrites flicker the
     *  client inventory). */
    private void applyInventoryToPlayer(Player p, byte[][] slots) {
        if (slots == null) return;
        try {
            int hash = inventoryHash(slots);
            Integer last = lastAppliedInventoryHash.get(p.getUniqueId());
            if (last != null && last == hash) return;
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            org.bukkit.inventory.ItemStack[] main = new org.bukkit.inventory.ItemStack[36];
            int n = Math.min(slots.length, 41);
            for (int i = 0; i < 36 && i < n; i++) {
                main[i] = dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(slots[i]);
            }
            inv.setContents(main);
            if (n > 40) {
                inv.setArmorContents(new org.bukkit.inventory.ItemStack[]{
                        dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(slots[36]),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(slots[37]),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(slots[38]),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(slots[39])});
                inv.setItemInOffHand(dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(slots[40]));
            }
            lastAppliedInventoryHash.put(p.getUniqueId(), hash);
        } catch (Exception ignored) {}
    }

    private static int inventoryHash(byte[][] slots) {
        int h = 1;
        for (byte[] s : slots) h = 31 * h + java.util.Arrays.hashCode(s);
        return h;
    }

    /** Map a 6-slot equipment array (main/off/boots/legs/chest/helmet) to the 41-slot layout.
     *  The main-hand item lives in the recorded held hotbar slot, not slot 0. */
    private static byte[][] equipmentAsInventory(byte[][] eq, int heldSlot) {
        byte[][] out = new byte[41][];
        for (int i = 0; i < 41; i++) out[i] = new byte[0];
        if (eq == null) return out;
        int main = (heldSlot >= 0 && heldSlot <= 8) ? heldSlot : 0;
        if (eq.length > 0 && eq[0] != null) out[main] = eq[0];       // main hand -> held hotbar slot
        if (eq.length > 1 && eq[1] != null) out[40] = eq[1];       // offhand
        if (eq.length > 2 && eq[2] != null) out[36] = eq[2];       // boots
        if (eq.length > 3 && eq[3] != null) out[37] = eq[3];       // leggings
        if (eq.length > 4 && eq[4] != null) out[38] = eq[4];       // chestplate
        if (eq.length > 5 && eq[5] != null) out[39] = eq[5];       // helmet
        return out;
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

    /** Refresh the per-tick viewer cache. Called once per tick, not per event. */
    private void refreshViewers() {
        tickViewers = liveViewers();
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
                drainViewerSyncs();
                if (!clock.paused()) {
                    driveCameras();
                    driveSpectators();
                }
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

    /**
     * Bring late-joining viewers up to the current playback state, targeting
     * ONLY them (temporarily swapping {@code tickViewers}). Two parts:
     *  1. Entity snapshot — fresh spawn + equipment + current pose/stance for
     *     every currently-live entity (reusing the live runtime id, so the
     *     joiner sees exactly what the other viewers see; no shared state is
     *     mutated, and spawns that happen mid-sync are picked up on the next
     *     pass via the per-viewer done-set).
     *  2. Virtual mode only — stream the past block changes up to the current
     *     cursor (world mode's terrain is physically correct). No historical
     *     chat/SFX/particles are replayed to the joiner.
     * Budgeted across ticks like the other streaming phases.
     */
    private void drainViewerSyncs() {
        if (pendingSyncs.isEmpty()) return;
        long deadline = System.nanoTime() + phaseBudgetNanos;
        for (java.util.Iterator<Map.Entry<UUID, Integer>> it = pendingSyncs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                syncedStablesFor.remove(en.getKey());
                continue;
            }
            List<Player> savedViewers = tickViewers;
            tickViewers = List.of(p);
            try {
                syncEntitiesFor(p);
                // Virtual mode: stream past block changes to this viewer.
                if (virtual && en.getValue() < appliedIndex) {
                    int n = 0;
                    while (en.getValue() < appliedIndex) {
                        TimelineEvent ev = timeline.get(en.getValue());
                        en.setValue(en.getValue() + 1);
                        if (ev instanceof TimelineEvent.BlockSet bs) {
                            applyBlockSet(bs);
                        } else if (ev instanceof TimelineEvent.MultiBlock mb) {
                            for (TimelineEvent.BlockSet b : mb.blocks()) applyBlockSet(b);
                        }
                        if ((++n & 511) == 0 && System.nanoTime() >= deadline) break;
                    }
                }
            } finally {
                tickViewers = savedViewers;
            }
            if (!virtual || en.getValue() >= appliedIndex) {
                it.remove();
                syncedStablesFor.remove(p.getUniqueId());
            }
        }
    }

    /** Snapshot-spawn every currently-live entity this viewer has not seen yet. */
    private void syncEntitiesFor(Player p) {
        java.util.Set<Integer> done = syncedStablesFor.computeIfAbsent(p.getUniqueId(), k -> new java.util.HashSet<>());
        for (Map.Entry<Integer, Integer> e : stableToRuntime.entrySet()) {
            int stable = e.getKey();
            if (done.contains(stable)) continue;
            int runtime = e.getValue();
            EntityPose pose = entityPoses.get(stable);
            TimelineEvent.PlayerSpawn ps = playerSpawnByStable.get(stable);
            TimelineEvent.EntitySpawn es = entitySpawnByStable.get(stable);
            dev.idebugger.echoreplay.model.Vec3d pos = pose != null ? pose.pos() : (ps != null ? ps.pos() : es.pos());
            dev.idebugger.echoreplay.model.Rotation rot = pose != null ? pose.rot() : (ps != null ? ps.rot() : es.rot());
            if (ps != null) {
                fakes.spawnPlayer(p, runtime, ps.uuid(), ps.name(), ps.skin(), pos, rot);
                replayPlayerEquipment(runtime, ps);
            } else if (es != null) {
                com.github.retrooper.packetevents.protocol.entity.type.EntityType type = null;
                String key = es.typeKey();
                int slash = key.indexOf(':');
                try {
                    type = com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
                            .getByName(slash >= 0 ? key.substring(slash + 1) : key);
                } catch (Exception ignored) {
                }
                if (type == null) {
                    done.add(stable); // unknown type — was never spawned anyway
                    continue;
                }
                fakes.spawnMob(p, runtime, es.uuid(), type, pos, rot);
                applySpawnMetadata(p, runtime, es);
            } else {
                done.add(stable); // no spawn record — skip
                continue;
            }
            fakes.teleport(p, runtime, pos, rot.yaw(), rot.pitch(), true);
            fakes.headLook(p, runtime, rot.headYaw());
            pushStance(runtime);
            done.add(stable);
            recordSpawnedFor(runtime, p);
        }
    }

    /** Re-apply an entity's recorded spawn metadata (baby/slime/firework). */
    private void applySpawnMetadata(Player p, int runtime, TimelineEvent.EntitySpawn s) {
        java.util.List<dev.idebugger.echoreplay.model.RecordedMetadata.Entry> spawnMeta =
                dev.idebugger.echoreplay.model.RecordedMetadata.decodeEntries(s.metadata());
        if (spawnMeta.isEmpty()) return;
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
        // Any viewer sync in flight is now stale: re-sync from zero afterwards.
        pendingSyncs.replaceAll((k, v) -> 0);
        if (virtual) {
            // No terrain to rebuild: reset entities and stream the fast-apply
            // through CATCHUP (budgeted across ticks — a long recording must
            // not freeze the tick thread).
            resetEntities();
            appliedIndex = 0;
            catchupTargetMs = targetMs;
            clock.pause();
            phase = Phase.CATCHUP;
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
        if (liveBackup) {
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
        pendingSyncs.clear();
        releaseSpectates();
        releaseCameras();
        // Restore any viewers we switched to spectator.
        for (Map.Entry<UUID, org.bukkit.GameMode> fm : forcedModes.entrySet()) {
            Player p = Bukkit.getPlayer(fm.getKey());
            if (p != null && p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                p.setGameMode(fm.getValue());
            }
        }
        forcedModes.clear();
        // Notify viewers that playback ended; personal sky goes back to real.
        for (Player p : liveViewers()) {
            p.sendMessage(Text.mm("<gray>Playback ended.</gray>"));
            resetViewerSky(p);
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
        fakes.clear();
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
        fakes.clear();
        entityPoses.clear();
        nameByStable.clear();
        runtimeFlags.clear();
        runtimePose.clear();
        runtimeHeadYaw.clear();
        dyingRuntimes.clear();
        playerSpawnByStable.clear();
        entitySpawnByStable.clear();
        lastVitalsByStable.clear();
        lastInventoryByStable.clear();
        lastEquipmentByStable.clear();
        lastGameModeByStable.clear();
        lastHeldSlotByStable.clear();
        releaseSpectates();
        pendingSyncs.replaceAll((k, v) -> 0);
        syncedStablesFor.clear();
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
            if (bs != null && dev.idebugger.echoreplay.record.Snapshotter.needsNbt(bs.getType())) {
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
        silentApply = true; // skip sound/chat/particle spam for the seeked span
        while (appliedIndex < timeline.size() && timeline.get(appliedIndex).tickMillis() <= target) {
            TimelineEvent ev = timeline.get(appliedIndex);
            appliedIndex++;
            applyEvent(ev);
            if ((++n & 1023) == 0 && System.nanoTime() >= deadline) {
                catchupTargetMs = target; // resume where we left off next tick
                silentApply = false;
                return;
            }
        }
        silentApply = false;
        catchupTargetMs = -1;
        clock.seekTo(target);
        phase = Phase.RUN;
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
            case TimelineEvent.WorldTime w -> onWorldTime(w);
            case TimelineEvent.Weather w -> onWeather(w);
            case TimelineEvent.PlayerVitals v -> onPlayerVitals(v);
            case TimelineEvent.PlayerInventory v -> onPlayerInventory(v);
            case TimelineEvent.GameMode g -> onPlayerGameMode(g);
            case TimelineEvent.HeldSlot h -> onPlayerHeldSlot(h);
            default -> {}
        }
    }

    /** Record vitals; apply to a real player who is spectating this npc. */
    private void onPlayerVitals(TimelineEvent.PlayerVitals v) {
        lastVitalsByStable.put(v.npcId(), new Vitals(v.health(), v.foodLevel(), v.saturation()));
        Player sp = spectatorOf(v.npcId());
        if (sp != null) applyVitalsToPlayer(sp, v.health(), v.foodLevel(), v.saturation());
    }

    /** Record the full inventory; apply to a real player who is spectating this npc. */
    private void onPlayerInventory(TimelineEvent.PlayerInventory v) {
        lastInventoryByStable.put(v.npcId(), v.slots());
        Player sp = spectatorOf(v.npcId());
        if (sp != null) applyInventoryToPlayer(sp, v.slots());
    }

    /** Record gamemode; mirror onto a real player who is spectating this npc. */
    private void onPlayerGameMode(TimelineEvent.GameMode g) {
        lastGameModeByStable.put(g.npcId(), g.mode());
        Player sp = spectatorOf(g.npcId());
        if (sp != null) {
            org.bukkit.GameMode mode = recordedMode(g.npcId());
            if (sp.getGameMode() != mode) {
                try {
                    sp.setGameMode(mode);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Record held hotbar slot; mirror onto a real player spectating this npc. */
    private void onPlayerHeldSlot(TimelineEvent.HeldSlot h) {
        lastHeldSlotByStable.put(h.npcId(), h.slot());
        Player sp = spectatorOf(h.npcId());
        if (sp != null) applyHeldSlot(sp, h.slot());
    }

    /** The first online real player spectating this stable, or null. */
    private Player spectatorOf(int stableId) {
        for (Map.Entry<UUID, Integer> e : spectateStable.entrySet()) {
            if (e.getValue() == stableId) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline()) return p;
            }
        }
        return null;
    }

    /** Drive each viewer's client-side time-of-day from the recording.
     *  Never touches the real world — purely visual per-player time. */
    private void onWorldTime(TimelineEvent.WorldTime w) {
        if (!driveWorldTime || virtual) return;
        for (Player p : tickViewers) {
            if (p == null || !p.isOnline()) continue;
            try {
                p.setPlayerTime(w.time(), false);
            } catch (Exception ignored) {
            }
        }
    }

    /** Drive each viewer's client-side weather from the recording.
     *  Never touches the real world — purely visual per-player weather. */
    private void onWeather(TimelineEvent.Weather w) {
        if (!driveWorldTime || virtual) return;
        org.bukkit.WeatherType type = (w.rainStrength() > 0 || w.thunderStrength() > 0)
                ? org.bukkit.WeatherType.DOWNFALL
                : org.bukkit.WeatherType.CLEAR;
        for (Player p : tickViewers) {
            if (p == null || !p.isOnline()) continue;
            try {
                p.setPlayerWeather(type);
            } catch (Exception ignored) {
            }
        }
    }

    /** Return one viewer to the world's real time and weather. */
    private static void resetViewerSky(Player p) {
        if (p == null || !p.isOnline()) return;
        try {
            p.resetPlayerTime();
        } catch (Exception ignored) {
        }
        try {
            p.resetPlayerWeather();
        } catch (Exception ignored) {
        }
    }

    private void onBlockBreakAnim(TimelineEvent.BlockBreakAnim b) {
        if (silentApply) return;
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
        int wx = cuboid.min().x() + b.pos().x();
        int wy = cuboid.min().y() + b.pos().y();
        int wz = cuboid.min().z() + b.pos().z();
        // Palette-parsed BlockData, cached at session start: createBlockData
        // re-parses text on every call and was a major per-block cost.
        BlockData data = blockDataFor(b.paletteIndex());
        if (data == null) return;
        if (!virtual) {
            // Physics OFF: neighbor updates (BlockPhysicsEvent storms, flowing
            // liquids, falling sand cascades) are pure waste here — every
            // resulting state is already in the recorded stream and the
            // manager cancels physics inside the cuboid anyway.
            try {
                world.getBlockAt(wx, wy, wz).setBlockData(data, false);
            } catch (Exception ignored) {
                return;
            }
            // Re-apply block-entity NBT (sign text, container contents,
            // respawn anchor charges, etc.) so these blocks update rather
            // than just place.
            if (b.nbt() != null && b.nbt().length > 0) {
                try {
                    var tile = world.getBlockAt(wx, wy, wz).getState(true);
                    dev.idebugger.echoreplay.util.NbtBytes.applyBlockState(tile, b.nbt());
                    tile.update(true);
                } catch (Exception ignored) {
                }
            }
        }
        // Push the change straight to all viewers (the ONLY path in virtual
        // mode, where no world write happens) so the break/update is
        // guaranteed visible even if the viewer's client didn't get a chunk
        // sync.
        Object packetState = packetStateFor(b.paletteIndex());
        if (packetState instanceof com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState wrapped) {
            var change = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange(
                    new com.github.retrooper.packetevents.util.Vector3i(wx, wy, wz), 0);
            change.setBlockState(wrapped);
            for (Player p : tickViewers) {
                com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacket(p, change);
            }
        }
    }

    /** Replay a player spawn/respawn (including KeepInventory armor and equipment). */
    private void onPlayerSpawn(TimelineEvent.PlayerSpawn s) {
        playerSpawnByStable.put(s.npcId(), s);
        byte[][] eq = s.equipment() != null
                ? s.equipment().toArray(new byte[0][]) : null;
        lastEquipmentByStable.put(s.npcId(), eq != null ? eq : new byte[6][]);
        Integer existing = stableToRuntime.get(s.npcId());
        if (existing != null) {
            entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
            for (Player p : tickViewers) {
                fakes.teleport(p, existing, s.pos(), s.rot().yaw(), s.rot().pitch(), true);
            }
            // Also replay equipment for respawned player
            replayPlayerEquipment(existing, s);
            repossessPaused(s.npcId(), s.name());
            return;
        }
        if (fakes.isExhausted()) {
            warnIdExhausted();
            return;
        }
        int runtime = fakes.allocateId();
        stableToRuntime.put(s.npcId(), runtime);
        entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
        nameByStable.put(s.npcId(), s.name() != null ? s.name() : "?");
        // Spawn FIRST, then equipment: the client drops equipment packets for
        // entity ids it does not know yet.
        for (Player p : tickViewers) {
            fakes.spawnPlayer(p, runtime, s.uuid(), s.name(), s.skin(), s.pos(), s.rot());
            recordSpawnedFor(runtime, p);
        }
        replayPlayerEquipment(runtime, s);
        // Re-entry into the region: hand the place back to paused spectators.
        repossessPaused(s.npcId(), s.name());
    }

    private void warnIdExhausted() {
        if (entityIdExhaustedLogged) return;
        entityIdExhaustedLogged = true;
        EchoReplay.getPlugin(EchoReplay.class).getLogger()
                .warning("Fake-entity id band exhausted — new entities will be invisible for the rest of this replay.");
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
        entitySpawnByStable.put(s.npcId(), s);
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
                fakes.teleport(p, existing, s.pos(), s.rot().yaw(), s.rot().pitch(), true);
            }
            return;
        }
        if (fakes.isExhausted()) {
            warnIdExhausted();
            return;
        }
        int runtime = fakes.allocateId();
        stableToRuntime.put(s.npcId(), runtime);
        entityPoses.put(s.npcId(), new EntityPose(s.pos(), s.rot()));
        for (Player p : tickViewers) {
            fakes.spawnMob(p, runtime, s.uuid(), type, s.pos(), s.rot());
            applySpawnMetadata(p, runtime, s);
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
        // Pose is recorded even when no fake exists (spectate destroyed it,
        // or the id band was exhausted): first-person drivers read it.
        entityPoses.put(m.npcId(), new EntityPose(m.pos(), m.rot()));
        Integer runtime = stableToRuntime.get(m.npcId());
        if (runtime == null) {
            // A player can respawn in-region after death WITHOUT a new
            // PlayerSpawn — poses simply resume. Re-spawn the fake so the
            // scene (and any first-person spectator) stays continuous.
            if (playerSpawnByStable.containsKey(m.npcId())) {
                respawnPlayerAt(m.npcId(), m.pos(), m.rot());
            }
            return;
        }
        for (Player p : tickViewers) {
            fakes.move(p, runtime, m.pos(), m.rot().yaw(), m.rot().pitch(), m.onGround());
        }
        syncHead(m.npcId(), runtime, m.rot().headYaw());
    }

    private void onTeleport(TimelineEvent.Teleport t) {
        entityPoses.put(t.npcId(), new EntityPose(t.pos(), t.rot()));
        Integer runtime = stableToRuntime.get(t.npcId());
        if (runtime == null) {
            if (playerSpawnByStable.containsKey(t.npcId())) {
                respawnPlayerAt(t.npcId(), t.pos(), t.rot());
            }
            return;
        }
        for (Player p : tickViewers) {
            fakes.teleport(p, runtime, t.pos(), t.rot().yaw(), t.rot().pitch(), true);
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
        if (silentApply) return; // no historical chat burst on seek
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
        // Cache the live 6-slot equipment so spectating can start mid-play
        // with the correct gear and fake re-spawns stay accurate.
        byte[][] cached = lastEquipmentByStable.get(eq.npcId());
        if (cached == null) {
            cached = new byte[6][];
            for (int i = 0; i < 6; i++) cached[i] = new byte[0];
            lastEquipmentByStable.put(eq.npcId(), cached);
        }
        if (eq.slot() >= 0 && eq.slot() < 6) cached[eq.slot()] = eq.item();

        // First-person spectate: the gear change lands in the recorded hotbar slot.
        Player sp = spectatorOf(eq.npcId());
        if (sp != null) {
            applyEquipmentSlotToPlayer(sp, eq.npcId(), eq.slot(), eq.item());
        }

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

    /** Apply one recorded equipment slot (0-5) to a real player's inventory.
     *  Slot 0 (main hand) is written into the <em>recorded</em> held hotbar
     *  slot — never the spectator's currently selected one — so a main-hand
     *  update arriving before its held-slot update can't corrupt the wrong
     *  hotbar slot. */
    private void applyEquipmentSlotToPlayer(Player p, int stableId, int slot, byte[] item) {
        try {
            var stack = dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(item);
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            switch (slot) {
                case 0 -> {
                    Integer held = lastHeldSlotByStable.get(stableId);
                    int target = (held != null && held >= 0 && held <= 8) ? held : inv.getHeldItemSlot();
                    if (target < 0 || target > 8) target = inv.getHeldItemSlot();
                    inv.setItem(target, stack);
                }
                case 1 -> inv.setItemInOffHand(stack);
                default -> {
                    org.bukkit.inventory.ItemStack[] armor = inv.getArmorContents();
                    org.bukkit.inventory.ItemStack[] a = armor != null ? armor.clone() : new org.bukkit.inventory.ItemStack[4];
                    int ai = slot - 2; // 2=boots 3=legs 4=chest 5=helmet
                    if (ai >= 0 && ai < 4) {
                        a[ai] = stack;
                        inv.setArmorContents(a);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** Send all six cached equipment slots of a (re-)spawned fake to viewers. */
    private void sendEquipmentBytes(int runtime, byte[][] eq) {
        for (int i = 0; i < eq.length && i < 6; i++) {
            if (eq[i] == null || eq[i].length == 0) continue;
            var item = dev.idebugger.echoreplay.record.EquipmentRecorder.deserializeItem(eq[i]);
            if (item.isEmpty()) continue;
            var peItem = io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(item);
            com.github.retrooper.packetevents.protocol.player.EquipmentSlot peSlot = switch (i) {
                case 0 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND;
                case 1 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND;
                case 2 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS;
                case 3 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS;
                case 4 -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE;
                default -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET;
            };
            var eqPacket = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment(
                    runtime, java.util.List.of(
                            new com.github.retrooper.packetevents.protocol.player.Equipment(peSlot, peItem)));
            for (Player p : tickViewers) {
                com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager()
                        .sendPacket(p, eqPacket);
            }
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
        if (silentApply) return;
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
        // Skip during seek catch-up (no SFX burst) and when fast-forwarding.
        if (silentApply || clock.speed() > skipSfxAbove) return;
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
        if (silentApply || clock.speed() > skipSfxAbove) return;
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
        if (silentApply) return;
        org.bukkit.Location loc = new org.bukkit.Location(world, e.pos().x(), e.pos().y(), e.pos().z());
        for (Player p : tickViewers) {
            try {
                // EXPLOSION_EMITTER is the renamed (1.20.2+) big burst; the old
                // EXPLOSION name is now the small fireball trail.
                p.spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, loc, 1);
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
        playerSpawnByStable.remove(stableId);
        entitySpawnByStable.remove(stableId);
        lastVitalsByStable.remove(stableId);
        lastInventoryByStable.remove(stableId);
        lastEquipmentByStable.remove(stableId);
        // Leaving the region PAUSES spectate (restore + notice, auto
        // re-possess on re-entry) instead of ending it.
        pauseSpectateForStable(stableId);
        if (runtime == null) return;
        destroyFor(runtime);
    }

    /** Replay a recorded death: play the death animation, then destroy lazily.
     *  Death does NOT end spectate — the real player stays (harmless, no
     *  damage) and snaps to the target's respawn; the spawn + state caches
     *  are kept so onMove can re-spawn the fake when poses resume. */
    private void onDeath(int stableId) {
        Integer runtime = stableToRuntime.remove(stableId);
        entityPoses.remove(stableId);
        runtimeHeadYaw.remove(stableId);
        entitySpawnByStable.remove(stableId);
        notifySpectatorsOfDeath(stableId);
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
        runtimeFlags.remove(runtimeId);
        runtimePose.remove(runtimeId);
        fakes.forget(runtimeId);
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
