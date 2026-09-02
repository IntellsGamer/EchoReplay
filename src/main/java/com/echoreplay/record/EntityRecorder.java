package com.echoreplay.record;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.EntitySnapshot;
import com.echoreplay.model.Rotation;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.model.Vec3d;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;

/**
 * Records entity spawn / move / death for the active recording. Player entities
 * are handled by ConnectionRecorder; this handles mobs, items, projectiles.
 */
public final class EntityRecorder implements Listener {

    private final EchoReplayPlugin plugin;

    public EntityRecorder(EchoReplayPlugin plugin) {
        this.plugin = plugin;
    }

    private RecordingSession session() {
        return plugin.recordingManager().activeSession();
    }

    private boolean inCuboid(RecordingSession s, Entity e) {
        return e.getWorld().getUID().equals(s.world().getUID())
                && s.cuboid().contains(e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(EntitySpawnEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (!inCuboid(s, ent)) return;
        int npc = s.npcIdFor(ent.getUniqueId());
        s.emit(new TimelineEvent.EntitySpawn(s.mediaMillis(), npc, ent.getUniqueId(),
                ent.getType().getKey().toString(),
                new Vec3d(ent.getLocation().x(), ent.getLocation().y(), ent.getLocation().z()),
                new Rotation(ent.getLocation().getPitch(), ent.getLocation().getYaw(), ent.getLocation().getYaw()),
                null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (!ent.getWorld().getUID().equals(s.world().getUID())) return;
        int npc = s.npcIdFor(ent.getUniqueId());
        s.emit(new TimelineEvent.Death(s.mediaMillis(), npc));
        s.emit(new TimelineEvent.EntityLeave(s.mediaMillis(), npc));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (e.getCause() == EntityRemoveEvent.Cause.DEATH) return;
        if (!ent.getWorld().getUID().equals(s.world().getUID())) return;
        int npc = s.npcIdFor(ent.getUniqueId());
        s.emit(new TimelineEvent.EntityLeave(s.mediaMillis(), npc));
    }
}
