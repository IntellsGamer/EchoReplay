package dev.idebugger.echoreplay.storage;

import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.util.Io;

import java.io.IOException;
import java.util.UUID;

/**
 * Decodes the SEC_META body into typed values. Body layout (little-endian):
 * utf serverVersion, utf worldUuid, utf worldName, int minX, int minY, int minZ,
 * int maxX, int maxY, int maxZ, long epochMillis, utf recorderUuid, utf
 * recorderName, long durationMillis, utf name.
 */
public final class MetaParser {

    private MetaParser() {}

    public record Parsed(String serverVersion, UUID worldUuid, String worldName, Cuboid cuboid,
                         long epochMillis, UUID recorderUuid, String recorderName,
                         long durationMillis, String name) {}

    public static Parsed parse(byte[] body) throws IOException {
        Io.LeIn in = Io.leIn(body);
        String serverVersion = Io.readUtf(in);
        UUID worldUuid = UUID.fromString(Io.readUtf(in));
        String worldName = Io.readUtf(in);
        int minX = in.readInt();
        int minY = in.readInt();
        int minZ = in.readInt();
        int maxX = in.readInt();
        int maxY = in.readInt();
        int maxZ = in.readInt();
        long epoch = in.readLong();
        UUID recorderUuid = UUID.fromString(Io.readUtf(in));
        String recorderName = Io.readUtf(in);
        long duration = in.readLong();
        String name = Io.readUtf(in);
        return new Parsed(serverVersion, worldUuid, worldName,
                new Cuboid(new dev.idebugger.echoreplay.model.BlockPos(minX, minY, minZ),
                        new dev.idebugger.echoreplay.model.BlockPos(maxX, maxY, maxZ)),
                epoch, recorderUuid, recorderName, duration, name);
    }
}
