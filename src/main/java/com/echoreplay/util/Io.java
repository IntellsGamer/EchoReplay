package com.echoreplay.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian binary primitives used by the recording codec. Minecraft's
 * network protocol is big-endian, but this is our own file format — we choose
 * little-endian and centralize it here.
 */
public final class Io {

    private Io() {}

    public static LeOut leOut(OutputStream os) {
        return new LeOut(os);
    }

    public static LeIn leIn(byte[] data) {
        return new LeIn(new ByteArrayInputStream(data));
    }

    public static LeIn leIn(InputStream in) {
        return new LeIn(in);
    }

    public static void writeVarInt(LeOut out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static int readVarInt(LeIn in) throws IOException {
        int result = 0;
        int shift = 0;
        int raw;
        do {
            if (shift >= 32) {
                throw new IOException("VarInt too big");
            }
            raw = in.readByte() & 0xFF;
            result |= (raw & 0x7F) << shift;
            shift += 7;
        } while ((raw & 0x80) != 0);
        return result;
    }

    public static void writeUtf(LeOut out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readUtf(LeIn in) throws IOException {
        int len = in.readInt();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Little-endian output stream. */
    public static final class LeOut {
        private final OutputStream out;

        LeOut(OutputStream out) {
            this.out = out;
        }

        public void write(byte[] b) throws IOException {
            out.write(b);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        public void flush() throws IOException {
            out.flush();
        }

        public void close() throws IOException {
            out.close();
        }

        public void writeByte(int v) throws IOException {
            out.write(v & 0xFF);
        }

        public void writeBoolean(boolean v) throws IOException {
            out.write(v ? 1 : 0);
        }

        public void writeShort(int v) throws IOException {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
        }

        public void writeInt(int v) throws IOException {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
        }

        public void writeLong(long v) throws IOException {
            for (int i = 0; i < 8; i++) {
                out.write((int) ((v >>> (i * 8)) & 0xFF));
            }
        }

        public void writeFloat(float v) throws IOException {
            writeInt(Float.floatToIntBits(v));
        }

        public void writeDouble(double v) throws IOException {
            writeLong(Double.doubleToLongBits(v));
        }
    }

    /** Little-endian input stream. */
    public static final class LeIn {
        private final InputStream in;

        LeIn(InputStream in) {
            this.in = in;
        }

        public int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new java.io.EOFException();
            return b;
        }

        public boolean readBoolean() throws IOException {
            return readByte() != 0;
        }

        public int readUnsignedByte() throws IOException {
            return readByte() & 0xFF;
        }

        public short readShort() throws IOException {
            int b0 = readByte();
            int b1 = readByte();
            return (short) ((b1 << 8) | b0);
        }

        public int readUnsignedShort() throws IOException {
            return readShort() & 0xFFFF;
        }

        public int readInt() throws IOException {
            int b0 = readByte();
            int b1 = readByte();
            int b2 = readByte();
            int b3 = readByte();
            return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
        }

        public long readLong() throws IOException {
            long res = 0;
            for (int i = 0; i < 8; i++) {
                res |= ((long) readByte()) << (i * 8);
            }
            return res;
        }

        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        public void readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new java.io.EOFException();
                off += n;
            }
        }

        public int available() throws IOException {
            return in.available();
        }
    }
}
