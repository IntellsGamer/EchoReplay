package com.echoreplay.storage;

import java.util.UUID;

public record RecordingEntry(
        String name,
        UUID worldUuid,
        String worldName,
        long durationMillis,
        long sizeBytes,
        long epochMillis,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ
) {
}
