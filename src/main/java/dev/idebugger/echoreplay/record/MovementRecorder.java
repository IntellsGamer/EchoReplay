package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;

/**
 * PacketEvents listener capturing high-rate player movement during recording.
 * No-op when no recording is active.
 */
public final class MovementRecorder extends PacketListenerAbstract {

    private final EchoReplay plugin;

    public MovementRecorder(EchoReplay plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
    }

    private RecordingSession session() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return null;
        return s;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        RecordingSession s = session();
        if (s == null) return;
        User user = event.getUser();
        if (user.getUUID() == null) return;
        Player p = plugin.getServer().getPlayer(user.getUUID());
        if (p == null) return;
        if (!s.world().getUID().equals(p.getWorld().getUID())) return;
        if (!s.cuboid().contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) return;

        var pt = event.getPacketType();
        if (pt == PacketType.Play.Client.PLAYER_POSITION
                || pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            double x, y, z;
            float yaw, pitch;
            try {
                if (pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                    var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation(event);
                    var pos = w.getPosition();
                    x = pos.x; y = pos.y; z = pos.z;
                    yaw = w.getYaw();
                    pitch = w.getPitch();
                } else {
                    var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition(event);
                    var pos = w.getPosition();
                    x = pos.x; y = pos.y; z = pos.z;
                    var loc = p.getLocation();
                    yaw = loc.getYaw();
                    pitch = loc.getPitch();
                }
            } catch (Exception e) {
                // fall back to server-side location
                var loc = p.getLocation();
                x = loc.getX(); y = loc.getY(); z = loc.getZ();
                yaw = loc.getYaw(); pitch = loc.getPitch();
            }
            int npc = s.npcIdFor(p.getUniqueId());
            s.emit(new TimelineEvent.Move(s.mediaMillis(), npc,
                    new Vec3d(x, y, z),
                    new Rotation(pitch, yaw, yaw),
                    p.isOnGround()));
        } else if (pt == PacketType.Play.Client.PLAYER_ROTATION) {
            int npc = s.npcIdFor(p.getUniqueId());
            var loc = p.getLocation();
            s.emit(new TimelineEvent.Move(s.mediaMillis(), npc,
                    new Vec3d(loc.getX(), loc.getY(), loc.getZ()),
                    new Rotation(loc.getPitch(), loc.getYaw(), loc.getYaw()),
                    p.isOnGround()));
        }
    }
}
