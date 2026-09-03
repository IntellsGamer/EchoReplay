package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import dev.idebugger.echoreplay.select.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Main-thread per-tick recorder that captures the position of every qualifying
 * non-player entity inside (or near) the cuboid. This covers mobs, items,
 * projectiles, vehicles, etc. regardless of how they got there:
 *  - an entity already present records its spawn once (so anything that walked
 *    or was dropped into the zone mid-recording is captured even if no
 *    EntitySpawnEvent fired inside the cuboid),
 *  - subsequent per-tick movement emits MOVE events so mobs move in the replay.
 *
 * It also captures the pose / stance flags (swimming, gliding, sneaking,
 * sprinting) of every entity including players, emitted as POSE and
 * SNEAK_SPRINT events whenever they change, so those states display in replay.
 */
public final class EntityTickRecorder {

    // Metadata index-0 entity flags (protocol bitmask). With real world values:
    // 0x01 = on fire, 0x02 = sneaking, 0x08 = sprinting, 0x10 = swimming.
    // We must never set 0x01 (fire) here, otherwise sneaking replays burn.
    private static final int FLAG_CROUCHED = 0x02;
    private static final int FLAG_SPRINTING = 0x08;
    private static final int FLAG_SWIMMING = 0x10;

    private final EchoReplay plugin;
    private final Map<java.util.UUID, EntityPose> lastKnown = new HashMap<>();
    private final Map<UUID, Integer> lastPose = new HashMap<>();
    private final Map<UUID, Integer> lastFlags = new HashMap<>();
    private final Map<UUID, org.bukkit.util.Vector> lastVel = new HashMap<>();
    private final Map<java.util.UUID, EntityPose> lastPlayerSeen = new HashMap<>();

    public EntityTickRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    record EntityPose(Vec3d pos, Rotation rot) {}

    /**
     * Called once per server tick from the recording tick loop. All on main thread.
     */
    public void tick() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        World world = s.world();
        Cuboid c = s.cuboid();

        // Book-keeping set of mobs we observed this tick, so we can emit a
        // LEAVE for mobs that were tracked but vanished from the region.
        Map<java.util.UUID, EntityPose> observed = new HashMap<>();

        for (Entity e : world.getNearbyEntities(
                new Location(world, c.min().x(), c.min().y(), c.min().z()),
                c.xSize(), c.ySize(), c.zSize())) {
            boolean isPlayer = e instanceof Player;
            UUID uuid = e.getUniqueId();

            // Pose / stance capture applies to everyone (players + mobs).
            captureStance(s, uuid, e);

            double x = e.getLocation().getX();
            double y = e.getLocation().getY();
            double z = e.getLocation().getZ();
            float yaw = e.getLocation().getYaw();
            float pitch = e.getLocation().getPitch();
            Vec3d pos = new Vec3d(x, y, z);
            Rotation rot = new Rotation(pitch, yaw, yaw);
            observed.put(uuid, new EntityPose(pos, rot));

            if (isPlayer) {
                EntityPose prevPlayer = lastPlayerSeen.get(uuid);
                if (prevPlayer == null) {
                    if (s.markEntitySpawned(uuid)) {
                        int npc = s.npcIdFor(uuid);
                        s.emit(new TimelineEvent.PlayerSpawn(s.mediaMillis(), npc, uuid,
                                e.getName(), null, pos, rot, null, null));
                    }
                    lastPlayerSeen.put(uuid, new EntityPose(pos, rot));
                } else {
                    lastPlayerSeen.put(uuid, new EntityPose(pos, rot));
                }
                continue;
            }

            int npc = s.npcIdFor(uuid);
            EntityPose prev = lastKnown.get(uuid);
            if (prev == null) {
                // First observation -> treat as a spawn into the zone, but only
                // emit if the event listener did not already spawn this entity.
                if (s.markEntitySpawned(uuid)) {
                    s.emit(new TimelineEvent.EntitySpawn(s.mediaMillis(), npc, uuid,
                            e.getType().getKey().toString(), pos, rot, dev.idebugger.echoreplay.model.RecordedMetadata.capture(e)));
                }
                lastKnown.put(uuid, new EntityPose(pos, rot));
            } else {
                // Emit a MOVE when the position/rotation changed meaningfully.
                if (!prev.pos().equals(pos) || !sameRot(prev.rot(), rot)) {
                    s.emit(new TimelineEvent.Move(s.mediaMillis(), npc, pos, rot, true));
                    lastKnown.put(uuid, new EntityPose(pos, rot));
                }
            }
            // Velocity for projectiles (firework rockets, arrows, etc.) so
            // crossbow-launched flight direction and speed replay correctly.
            if (e instanceof org.bukkit.entity.Projectile) {
                org.bukkit.util.Vector vel = e.getVelocity();
                org.bukkit.util.Vector prevVel = lastVel.get(uuid);
                double eps = 0.001;
                boolean changed = prevVel == null
                        || Math.abs(prevVel.getX() - vel.getX()) > eps
                        || Math.abs(prevVel.getY() - vel.getY()) > eps
                        || Math.abs(prevVel.getZ() - vel.getZ()) > eps;
                if (changed) {
                    s.emit(new TimelineEvent.Velocity(s.mediaMillis(), npc,
                            new Vec3d(vel.getX(), vel.getY(), vel.getZ())));
                    lastVel.put(uuid, vel.clone());
                }
            }
        }

        // Emit LEAVE for tracked mobs no longer present in the region.
        if (!lastKnown.isEmpty()) {
            java.util.Iterator<Map.Entry<java.util.UUID, EntityPose>> it = lastKnown.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<java.util.UUID, EntityPose> en = it.next();
                if (!observed.containsKey(en.getKey())) {
                    int npc = s.npcIdFor(en.getKey());
                    long t = s.mediaMillis();
                    s.emit(new TimelineEvent.EntityLeave(t, npc));
                    it.remove();
                    lastVel.remove(en.getKey());
                    lastPose.remove(en.getKey());
                    lastFlags.remove(en.getKey());
                }
            }
        }

        // Emit LEAVE for tracked players no longer present in the region.
        if (!lastPlayerSeen.isEmpty()) {
            java.util.Iterator<Map.Entry<java.util.UUID, EntityPose>> it = lastPlayerSeen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<java.util.UUID, EntityPose> en = it.next();
                if (!observed.containsKey(en.getKey())) {
                    int npc = s.npcIdFor(en.getKey());
                    long t = s.mediaMillis();
                    s.emit(new TimelineEvent.PlayerLeave(t, npc, 1));
                    it.remove();
                }
            }
        }
    }

    /** Detect and emit POSE / SNEAK_SPRINT for the entity's current stance. */
    private void captureStance(RecordingSession s, UUID uuid, Entity e) {
        // Only living entities have pose / flag metadata accessors. Non-living
        // entities (items, projectiles, vehicles) must be excluded, otherwise
        // sending pose metadata to them on replay causes Network Protocol Errors.
        if (!(e instanceof org.bukkit.entity.LivingEntity le)) return;
        int pose = 0; // STANDING
        int flags = 0;
        if (le.isGliding()) {
            pose = 1; // FALL_FLYING
        } else if (le.isSwimming() || e.isInWater()) {
            pose = 3; // SWIMMING
        }
        if (le.isSneaking()) {
            flags |= FLAG_CROUCHED;
            if (pose == 0) pose = 5; // CROUCHING
        }
        if (le.isInWater() || le.isSwimming()) {
            flags |= FLAG_SWIMMING;
        }
        if (e instanceof Player p && p.isSprinting()) {
            flags |= FLAG_SPRINTING;
        }

        int npc = s.npcIdFor(uuid);
        Integer prevPose = lastPose.get(uuid);
        if (prevPose == null || prevPose != pose) {
            lastPose.put(uuid, pose);
            s.emit(new TimelineEvent.Pose(s.mediaMillis(), npc, pose));
        }
        Integer prevFlags = lastFlags.get(uuid);
        if (prevFlags == null || prevFlags != flags) {
            lastFlags.put(uuid, flags);
            s.emit(new TimelineEvent.SneakSprint(s.mediaMillis(), npc, flags));
        }
    }

    private static boolean sameRot(Rotation a, Rotation b) {
        return Math.abs(a.yaw() - b.yaw()) < 0.5f && Math.abs(a.pitch() - b.pitch()) < 0.5f;
    }

    /** Clear per-entity state when a recording starts. */
    public void reset() {
        lastKnown.clear();
        lastPose.clear();
        lastFlags.clear();
        lastVel.clear();
        lastPlayerSeen.clear();
    }
}