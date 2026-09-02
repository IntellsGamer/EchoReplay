package dev.idebugger.echoreplay.storage;

import dev.idebugger.echoreplay.select.Cuboid;

import java.util.UUID;

/**
 * Human/metadata header describing a recording. Sits in the META section and
 * mirrors into the on-disk index.
 */
public record RecordingHeader(
        int format,
        String serverVersion,
        UUID worldUuid,
        String worldName,
        Cuboid cuboid,
        long epochMillis,
        UUID recorderUuid,
        String recorderName,
        long durationMillis,
        long sizeBytes,
        String name
) {
}
