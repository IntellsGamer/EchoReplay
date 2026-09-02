package com.echoreplay.record;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.Rotation;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.model.Vec3d;
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

    private final EchoReplayPlugin plugin;

    public MovementRecorder(EchoReplayPlugin plugin) {
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
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            User user = event.getUser();
            if (user.getUUID() == null) return;
            Player p = plugin.getServer().getPlayer(user.getUUID());
            if (p == null) return;
            if (!s.world().getUID().equals(p.getWorld().getUID())) return;
            var loc = p.getLocation();
            if (!s.cuboid().contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) return;
            int npc = s.npcIdFor(p.getUniqueId());
            s.emit(new TimelineEvent.Move(s.mediaMillis(), npc,
                    new Vec3d(loc.getX(), loc.getY(), loc.getZ()),
                    new Rotation(loc.getPitch(), loc.getYaw(), loc.getYaw()),
                    p.isOnGround()));
        }
    }
}
