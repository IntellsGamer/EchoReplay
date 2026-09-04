package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.Vec3d;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Allocates entity ids in a high, unused band and spawns/updates/destroys fake
 * entities (packet-only) for viewers. Packets are sent via PacketEvents to each
 * viewer's connection.
 */
public final class FakeEntityTracker {

    /**
     * Fake entity ids allocate downward from just below MAX_VALUE, far above
     * any id a real server assigns (those start at 1 and grow by 1). The band
     * is 1,000,000 ids wide; when it exhausts, {@link #isExhausted()} lets the
     * session skip further spawns instead of wrapping into real entity ids.
     */
    public static final int ID_START = Integer.MAX_VALUE - 1_000_000;
    private final AtomicInteger nextId = new AtomicInteger(ID_START);

    public FakeEntityTracker() {
    }

    public int allocateId() {
        return nextId.getAndDecrement();
    }

    /** True once the high id band is used up (spawning must stop). */
    public boolean isExhausted() {
        return nextId.get() < 0;
    }

    private static void send(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
    }

    public void spawnPlayer(Player viewer, int runtimeId, UUID uuid, String name, PlayerSkin skin,
                            Vec3d pos, Rotation rot) {
        // Use a fresh random UUID rather than the recorded player's UUID. This
        // avoids client-side conflicts when the viewer is also the actor (same
        // UUID already in the local tab list), which otherwise leaves the fake
        // player invisible / non-responsive to move packets.
        UUID fakeUuid = UUID.randomUUID();
        UserProfile profile = new UserProfile(fakeUuid, name);
        if (skin != null && skin.hasValue()) {
            String sig = (skin.signature() != null && !skin.signature().isEmpty()) ? skin.signature() : null;
            profile.setTextureProperties(java.util.List.of(new TextureProperty("textures", skin.value(),
                    sig == null ? "" : sig)));
        }
        WrapperPlayServerPlayerInfoUpdate info = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, true, 0, GameMode.SURVIVAL, null, null));
        send(viewer, info);

        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                runtimeId, fakeUuid, EntityTypes.PLAYER,
                new com.github.retrooper.packetevents.protocol.world.Location(pos.x(), pos.y(), pos.z(),
                        rot.yaw(), rot.pitch()),
                0f, 0, null);
        send(viewer, spawn);
    }

    public void spawnMob(Player viewer, int runtimeId, UUID uuid, EntityType type, Vec3d pos, Rotation rot) {
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                runtimeId, uuid, type,
                new com.github.retrooper.packetevents.protocol.world.Location(pos.x(), pos.y(), pos.z(),
                        rot.yaw(), rot.pitch()),
                0f, 0, null);
        send(viewer, spawn);
    }

    public void destroy(Player viewer, int runtimeId) {
        send(viewer, new WrapperPlayServerDestroyEntities(runtimeId));
    }

    public void positionSync(Player viewer, int runtimeId, Vec3d pos, Rotation rot, boolean onGround) {
        EntityPositionData data = new EntityPositionData(
                new com.github.retrooper.packetevents.util.Vector3d(pos.x(), pos.y(), pos.z()),
                com.github.retrooper.packetevents.util.Vector3d.zero(),
                rot.yaw(), rot.pitch());
        send(viewer, new WrapperPlayServerEntityPositionSync(runtimeId, data, onGround));
    }

    public void headLook(Player viewer, int runtimeId, float headYaw) {
        send(viewer, new WrapperPlayServerEntityHeadLook(runtimeId, headYaw));
    }

    /** Send an entity status/event (e.g. status 2 = hurt, 3 = death animation). */
    public void entityStatus(Player viewer, int runtimeId, int status) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus(
                runtimeId, status));
    }

    /** Send an entity-metadata patch (stance flags / pose / eye height) to a viewer. */
    public void setMetadata(Player viewer, int runtimeId,
                             java.util.List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> data) {
        if (data.isEmpty()) return;
        send(viewer, new WrapperPlayServerEntityMetadata(runtimeId, data));
    }

    public void velocity(Player viewer, int runtimeId, dev.idebugger.echoreplay.model.Vec3d vel) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity(
                runtimeId, new com.github.retrooper.packetevents.util.Vector3d(vel.x(), vel.y(), vel.z())));
    }
}
