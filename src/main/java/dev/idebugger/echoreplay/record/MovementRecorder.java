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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents listener capturing player movement during recording.
 * Runs on Netty IO threads, so it touches NO Bukkit API here (player/world
 * lookups and locations are main-thread state): positions come purely from
 * packets, and region membership comes from the main-thread tick recorder.
 * Events are only emitted when the pose changed beyond epsilon, which cuts
 * the 20-40 packets/sec/client firehose down to genuine movement.
 * No-op when no recording is active.
 */
public final class MovementRecorder extends PacketListenerAbstract {

    // Sub-millimetre / sub-degree deltas are dropped (absolute values are
    // stored, so nothing can drift).
    private static final double POS_EPS = 1e-4;
    private static final float ROT_EPS = 0.1f;

    private final EchoReplay plugin;
    private final Map<UUID, LastMove> lastSent = new ConcurrentHashMap<>();

    private record LastMove(double x, double y, double z, float yaw, float pitch) {}

    public MovementRecorder(EchoReplay plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
    }

    private RecordingSession session() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) {
            if (!lastSent.isEmpty()) lastSent.clear();
            return null;
        }
        return s;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        RecordingSession s = session();
        if (s == null) return;
        User user = event.getUser();
        UUID uuid = user == null ? null : user.getUUID();
        if (uuid == null) return;
        // Region membership is maintained on the main thread (no Bukkit here).
        if (!plugin.recordingManager().entityTickRecorder().isInRegion(uuid)) return;

        var pt = event.getPacketType();
        boolean wantPos = pt == PacketType.Play.Client.PLAYER_POSITION
                || pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
        boolean wantRot = pt == PacketType.Play.Client.PLAYER_ROTATION
                || pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
        if (!wantPos && !wantRot) return;

        double x, y, z;
        float yaw, pitch;
        boolean onGround;
        try {
            if (pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation(event);
                var pos = w.getPosition();
                x = pos.x; y = pos.y; z = pos.z;
                yaw = w.getYaw();
                pitch = w.getPitch();
                onGround = w.isOnGround();
            } else if (pt == PacketType.Play.Client.PLAYER_POSITION) {
                var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition(event);
                var pos = w.getPosition();
                x = pos.x; y = pos.y; z = pos.z;
                LastMove prev = lastSent.get(uuid);
                yaw = prev != null ? prev.yaw() : 0f;
                pitch = prev != null ? prev.pitch() : 0f;
                onGround = w.isOnGround();
            } else {
                var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation(event);
                yaw = w.getYaw();
                pitch = w.getPitch();
                onGround = w.isOnGround();
                LastMove prev = lastSent.get(uuid);
                if (prev == null) return; // no position baseline yet
                x = prev.x(); y = prev.y(); z = prev.z();
            }
        } catch (Exception e) {
            return; // never touch Bukkit from Netty; drop undecodable packets
        }
        LastMove prev = lastSent.get(uuid);
        if (prev != null
                && Math.abs(prev.x() - x) < POS_EPS
                && Math.abs(prev.y() - y) < POS_EPS
                && Math.abs(prev.z() - z) < POS_EPS
                && Math.abs(prev.yaw() - yaw) < ROT_EPS
                && Math.abs(prev.pitch() - pitch) < ROT_EPS) {
            return;
        }
        lastSent.put(uuid, new LastMove(x, y, z, yaw, pitch));
        int npc = s.npcIdFor(uuid);
        s.emit(new TimelineEvent.Move(s.mediaMillis(), npc,
                new Vec3d(x, y, z),
                new Rotation(pitch, yaw, yaw),
                onGround));
    }
}
