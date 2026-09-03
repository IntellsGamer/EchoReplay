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
 *   - passive ageable mobs:     metadata index 15, INT var-int, negative = baby
 *   - zombie-family mobs:       metadata index 15, BYTE (1 = baby, 0 = adult)
 *   - slime / magma cube size:  metadata index 16, INT var-int, 1..127 = size
 *
 * The metadata TYPE (INT vs BYTE) matters: zombies crash the client if given an
 * INT at index 15, so each entry records which PacketEvents type to send.
 *
 * Blob layout (DataOutputStream):
 *   byte count, then count x (byte index, byte type, int value),
 *   type: 1 = INT, 2 = BYTE
 */
public final class RecordedMetadata {

    public static final int AGE_INDEX = 15;
    public static final int SLIME_SIZE_INDEX = 16;

    public static final int TYPE_INT = 1;
    public static final int TYPE_BYTE = 2;

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
            if (e instanceof org.bukkit.entity.Slime slime) {
                out.writeByte(SLIME_SIZE_INDEX);
                out.writeByte(TYPE_INT);
                out.writeInt(slime.getSize());
                count++;
            } else if (e instanceof org.bukkit.entity.Zombie) {
                // Undead ageables store baby-ness as a BYTE boolean at index 15.
                // Sending an INT here crashes the client, so always use TYPE_BYTE.
                out.writeByte(AGE_INDEX);
                out.writeByte(TYPE_BYTE);
                out.writeInt(1);
                count++;
            } else if (e instanceof org.bukkit.entity.Ageable a && !a.isAdult()) {
                out.writeByte(AGE_INDEX);
                out.writeByte(TYPE_INT);
                out.writeInt(-24000);
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

    /** Returns the list of [index, type, value] metadata entries to apply. */
    public static List<int[]> decode(byte[] blob) {
        List<int[]> out = new ArrayList<>();
        if (blob == null || blob.length < 1) return out;
        int count = blob[0] & 0xFF;
        int p = 1;
        for (int i = 0; i < count; i++) {
            if (p + 6 > blob.length) break;
            int index = blob[p] & 0xFF;
            int type = blob[p + 1] & 0xFF;
            int value = ((blob[p + 2] & 0xFF) << 24)
                    | ((blob[p + 3] & 0xFF) << 16)
                    | ((blob[p + 4] & 0xFF) << 8)
                    | (blob[p + 5] & 0xFF);
            out.add(new int[]{index, type, value});
            p += 6;
        }
        return out;
    }
}