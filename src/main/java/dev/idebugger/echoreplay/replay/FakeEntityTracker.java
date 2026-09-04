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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Allocates entity ids in a high, unused band and spawns/updates/destroys fake
 * entities (packet-only) for viewers. Packets are sent via PacketEvents to each
 * viewer's connection.
 *
 * <p>S-9: every fake-player spawn now registers the fake UUID per runtime id;
 * {@link #destroy(Player, int)} for a player runtime also sends
 * {@link WrapperPlayServerPlayerInfoRemove} so the fake tab entry is removed.
 * v1 left fake tab entries accumulating forever (30 entries per viewer after a
 * 10-min PvP recording — the only way out was relog).</p>
 */
public final class FakeEntityTracker {

    /** Start allocating downward from MAX to avoid collisions with live entities. */
    public static final int ID_START = Integer.MAX_VALUE - 1000;
    private final AtomicInteger nextId = new AtomicInteger(ID_START);

    /**
     * Tracks which fake UUID belongs to which runtime id, so destroy() can
     * also send the REMOVE_PLAYER info update. Multiple viewers see the same
     * fake UUID per runtime id (one fake entry per spawn, removed on destroy).
     */
    private final Map<Integer, UUID> playerUuidByRuntime = new ConcurrentHashMap<>();

    public FakeEntityTracker() {
    }

    public int allocateId() {
        return nextId.getAndDecrement();
    }

    /** Forget the runtime→UUID mapping. Call when a session ends. */
    public void reset() {
        playerUuidByRuntime.clear();
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
        playerUuidByRuntime.put(runtimeId, fakeUuid);
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
        // S-9: also remove the fake tab entry that spawnPlayer created.
        // Without this, the fake player lingers in the viewer's tab list
        // forever (until they relog). One REMOVE_PLAYER per viewer, per
        // fake spawn — matches the ADD_PLAYER sent at spawn time.
        UUID fakeUuid = playerUuidByRuntime.get(runtimeId);
        if (fakeUuid != null) {
            send(viewer, new WrapperPlayServerPlayerInfoRemove(java.util.List.of(fakeUuid)));
        }
    }

    /**
     * Cleanup hook called when a session ends: forgets the runtime→UUID
     * mappings so a future session can reuse runtime ids without confusion.
     * (Destroy packets must already have been sent for the viewers to see
     * the entities gone — this is bookkeeping only.)
     */
    public void forgetRuntime(int runtimeId) {
        playerUuidByRuntime.remove(runtimeId);
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
                             List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> data) {
        if (data.isEmpty()) return;
        send(viewer, new WrapperPlayServerEntityMetadata(runtimeId, data));
    }

    public void velocity(Player viewer, int runtimeId, dev.idebugger.echoreplay.model.Vec3d vel) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity(
                runtimeId, new com.github.retrooper.packetevents.util.Vector3d(vel.x(), vel.y(), vel.z())));
    }
}
