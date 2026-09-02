package dev.idebugger.echoreplay.model;

/**
 * Immutable block position using ints. Indices are relative to world origin
 * (absolute coordinates), but relative-position math is provided as needed.
 */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public BlockPos relative(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public int manhattan(BlockPos o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z);
    }
}
