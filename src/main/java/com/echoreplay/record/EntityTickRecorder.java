package com.echoreplay.record;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.Rotation;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.model.Vec3d;
import com.echoreplay.select.Cuboid;
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
 */
public final class EntityTickRecorder {

    private final EchoReplayPlugin plugin;
    private final Map<java.util.UUID, EntityPose> lastKnown = new HashMap<>();

    public EntityTickRecorder(EchoReplayPlugin plugin) {
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

        // Book-keeping set of entities we observed this tick, so we can emit a
        // LEAVE for entities that were tracked but vanished from the region.
        Map<java.util.UUID, EntityPose> observed = new HashMap<>();

        for (Entity e : world.getNearbyEntities(
                new Location(world, c.min().x(), c.min().y(), c.min().z()),
                c.xSize(), c.ySize(), c.zSize())) {
            if (e instanceof Player) continue;
            UUID uuid = e.getUniqueId();
            double x = e.getLocation().getX();
            double y = e.getLocation().getY();
            double z = e.getLocation().getZ();
            float yaw = e.getLocation().getYaw();
            float pitch = e.getLocation().getPitch();
            Vec3d pos = new Vec3d(x, y, z);
            Rotation rot = new Rotation(pitch, yaw, yaw);
            observed.put(uuid, new EntityPose(pos, rot));

            int npc = s.npcIdFor(uuid);
            EntityPose prev = lastKnown.get(uuid);
            if (prev == null) {
                // First observation -> treat as a spawn into the zone. This is
                // what lets mobs (or anything) entering the area be recorded.
                s.emit(new TimelineEvent.EntitySpawn(s.mediaMillis(), npc, uuid,
                        e.getType().getKey().toString(), pos, rot, null));
                lastKnown.put(uuid, new EntityPose(pos, rot));
            } else {
                // Emit a MOVE when the position/rotation changed meaningfully.
                if (!prev.pos().equals(pos) || !sameRot(prev.rot(), rot)) {
                    s.emit(new TimelineEvent.Move(s.mediaMillis(), npc, pos, rot, true));
                    lastKnown.put(uuid, new EntityPose(pos, rot));
                }
            }
        }

        // Emit LEAVE for tracked entities no longer present in the region.
        if (!lastKnown.isEmpty()) {
            java.util.Iterator<Map.Entry<java.util.UUID, EntityPose>> it = lastKnown.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<java.util.UUID, EntityPose> en = it.next();
                if (!observed.containsKey(en.getKey())) {
                    int npc = s.npcIdFor(en.getKey());
                    long t = s.mediaMillis();
                    s.emit(new TimelineEvent.EntityLeave(t, npc));
                    it.remove();
                }
            }
        }
    }

    private static boolean sameRot(Rotation a, Rotation b) {
        return Math.abs(a.yaw() - b.yaw()) < 0.5f && Math.abs(a.pitch() - b.pitch()) < 0.5f;
    }

    /** Clear per-entity state when a recording starts. */
    public void reset() {
        lastKnown.clear();
    }
}
