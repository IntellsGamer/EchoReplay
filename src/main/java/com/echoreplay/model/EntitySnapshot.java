package com.echoreplay.model;

import java.util.UUID;

/**
 * Immutable snapshot of an entity for the file's ENTITIES section and for
 * fake-entity reconstruction. Textures is the Minecraft textures property
 * (value+signature) used for NPC skins.
 *
 * @param poseInDegrees serialized as raw bytes (kept as byte[] to stay off NMS).
 */
public record EntitySnapshot(
        UUID uuid,
        int stableId,
        String typeKey,
        Vec3d pos,
        float pitch,
        float yaw,
        float headYaw,
        String name,
        String texturesValue,
        String texturesSignature,
        byte[] poseInDegrees
) {
}
