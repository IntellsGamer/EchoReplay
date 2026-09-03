package dev.idebugger.echoreplay.model;

import org.bukkit.entity.Entity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact, version-independent encoding of a small set of replay-critical
 * entity metadata values captured at spawn time on the recording server and
 * re-applied to fake entities during playback.
 *
 * Only the fields that CANNOT be inferred from the entity type alone are stored
 * (currently baby-age and slime/magma-cube size):
 *   - baby:      metadata index 15, var-int, negative = baby
 *   - slime size: metadata index 16, var-int, 1..127 = size
 *
 * Blob layout (DataOutputStream):
 *   byte count, then count x (byte index, int value)
 */
public final class RecordedMetadata {

    public static final int AGE_INDEX = 15;
    public static final int SLIME_SIZE_INDEX = 16;

    private RecordedMetadata() {}

    /**
     * Captures baby-ness / slime-size from a live entity, or {@code null} when
     * neither applies (keeps a normal adult spawn compact).
     */
    public static byte[] capture(Entity e) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int count = 0;
        try {
            if (e instanceof org.bukkit.entity.Ageable a && !a.isAdult()) {
                out.writeByte(AGE_INDEX);
                out.writeInt(-24000);
                count++;
            }
            if (e instanceof org.bukkit.entity.Slime slime) {
                out.writeByte(SLIME_SIZE_INDEX);
                out.writeInt(slime.getSize());
                count++;
            }
        } catch (IOException ignored) {
            return null;
        }
        if (count == 0) return null;
        byte[] body = bos.toByteArray();
        byte[] blob = new byte[body.length + 1];
        blob[0] = (byte) count;
        System.arraycopy(body, 0, blob, 1, body.length);
        return blob;
    }

    /** Returns the list of [index, int value] metadata entries to apply. */
    public static List<int[]> decode(byte[] blob) {
        List<int[]> out = new ArrayList<>();
        if (blob == null || blob.length < 1) return out;
        int count = blob[0] & 0xFF;
        int p = 1;
        for (int i = 0; i < count; i++) {
            if (p + 5 > blob.length) break;
            int index = blob[p] & 0xFF;
            int value = ((blob[p + 1] & 0xFF) << 24)
                    | ((blob[p + 2] & 0xFF) << 16)
                    | ((blob[p + 3] & 0xFF) << 8)
                    | (blob[p + 4] & 0xFF);
            out.add(new int[]{index, value});
            p += 5;
        }
        return out;
    }
}