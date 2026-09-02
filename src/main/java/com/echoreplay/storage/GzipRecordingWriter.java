package com.echoreplay.storage;

import com.echoreplay.util.Io;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Streaming writer of the .echoreplay.gz v1 format.
 *
 * Usage: writeHeaderAndSnapshot(...) once on the IO thread, then repeatedly
 * appendTimelineEvent(...) from the IO thread. close() finishes the stream.
 * Not thread-safe; the caller serializes access.
 */
public final class GzipRecordingWriter implements AutoCloseable {

    private final GZIPOutputStream gzip;
    private final DataOutputStream out;

    public GzipRecordingWriter(java.io.OutputStream raw) throws IOException {
        this.gzip = new GZIPOutputStream(raw);
        this.out = new DataOutputStream(gzip);
        // magic
        out.writeByte('E');
        out.writeByte('C');
        out.writeByte('H');
        out.writeByte('O');
        // format u16 LE
        byte[] fmt = { (byte) (RecordingFormat.FORMAT & 0xFF), (byte) ((RecordingFormat.FORMAT >> 8) & 0xFF) };
        out.write(fmt);
        // flags u16 LE = 0
        out.writeByte(0);
        out.writeByte(0);
    }

    /**
     * Write the meta section header with the given cuboid ranges.
     * Format version 1 uses explicit configured values for the header fields.
     */
    public void writeMeta(String serverVersion, java.util.UUID worldUuid, String worldName,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          long epochMillis, java.util.UUID recorderUuid, String recorderName,
                          long durationMillis, String name) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        Io.writeUtf(b, serverVersion);
        Io.writeUtf(b, worldUuid.toString());
        Io.writeUtf(b, worldName);
        b.writeInt(minX);
        b.writeInt(minY);
        b.writeInt(minZ);
        b.writeInt(maxX);
        b.writeInt(maxY);
        b.writeInt(maxZ);
        b.writeLong(epochMillis);
        Io.writeUtf(b, recorderUuid.toString());
        Io.writeUtf(b, recorderName);
        b.writeLong(durationMillis);
        Io.writeUtf(b, name);
        b.flush();
        writeSection(RecordingFormat.SEC_META, bos.toByteArray());
    }

    public void writePalette(java.util.List<String> palette) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeInt(palette.size());
        for (String s : palette) {
            Io.writeUtf(b, s);
        }
        b.flush();
        writeSection(RecordingFormat.SEC_PALETTE, bos.toByteArray());
    }

    /**
     * @param sizeX sizeY sizeZ dimensions of the stored dense grid
     * @param data  palette indices, row-major
     * @param bitsPerEntry bits used per entry (authoritative)
     */
    public void writeBlocks(int sizeX, int sizeY, int sizeZ, int[] data, int bitsPerEntry) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeInt(sizeX);
        b.writeInt(sizeY);
        b.writeInt(sizeZ);
        b.writeByte(bitsPerEntry);
        int total = data.length;
        long mask = (1L << bitsPerEntry) - 1;
        int perLong = Math.min(64 / bitsPerEntry, 32);
        int longCount = (total + perLong - 1) / perLong;
        b.writeInt(longCount);
        for (int li = 0; li < longCount; li++) {
            long packed = 0;
            int start = li * perLong;
            for (int i = 0; i < perLong; i++) {
                int idx = start + i;
                if (idx >= total) break;
                packed |= ((long) (data[idx] & mask)) << (i * bitsPerEntry);
            }
            b.writeLong(packed);
        }
        b.flush();
        writeSection(RecordingFormat.SEC_BLOCKS, bos.toByteArray());
    }

    /** @param entries each is [relX, relY, relZ, nbtLen, nbt...] already packed */
    public void writeBlockNbt(byte[] entries) throws IOException {
        writeSection(RecordingFormat.SEC_BLOCK_NBT, entries);
    }

    public void writeEntities(byte[] entries) throws IOException {
        writeSection(RecordingFormat.SEC_ENTITIES, entries);
    }

    public void appendTimelineEvent(long tickMillis, byte[] body, byte typeId) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeLong(tickMillis);
        b.writeByte(typeId);
        b.writeShort(body.length);
        b.write(body);
        b.flush();
        writeSection(RecordingFormat.SEC_TIMELINE, bos.toByteArray());
    }

    public void close() throws IOException {
        out.flush();
        gzip.finish();
        gzip.close();
    }

    private void writeSection(int type, byte[] body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeInt(type);
        b.writeInt(body.length);
        b.write(body);
        b.flush();
        writeRaw(bos.toByteArray());
    }

    private void writeRaw(byte[] chunk) throws IOException {
        out.write(chunk);
    }
}
