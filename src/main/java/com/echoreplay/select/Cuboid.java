package com.echoreplay.select;

import com.echoreplay.model.BlockPos;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Inclusive cuboid between two corners. Order-agnostic, clamped to world
 * min/max build height.
 */
public record Cuboid(BlockPos min, BlockPos max) {

    public static Cuboid of(BlockPos a, BlockPos b) {
        int minX = Math.min(a.x(), b.x());
        int minY = Math.min(a.y(), b.y());
        int minZ = Math.min(a.z(), b.z());
        int maxX = Math.max(a.x(), b.x());
        int maxY = Math.max(a.y(), b.y());
        int maxZ = Math.max(a.z(), b.z());
        return new Cuboid(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    public static Cuboid of(BlockPos a, BlockPos b, World world) {
        Cuboid c = of(a, b);
        int minY = Math.max(world.getMinHeight(), c.min().y());
        int maxY = Math.min(world.getMaxHeight() - 1, c.max().y());
        return new Cuboid(new BlockPos(c.min().x(), minY, c.min().z()),
                new BlockPos(c.max().x(), maxY, c.max().z()));
    }

    public long volume() {
        long sx = (long) max.x() - min.x() + 1;
        long sy = (long) max.y() - min.y() + 1;
        long sz = (long) max.z() - min.z() + 1;
        return sx * sy * sz;
    }

    public int xSize() { return max.x() - min.x() + 1; }
    public int ySize() { return max.y() - min.y() + 1; }
    public int zSize() { return max.z() - min.z() + 1; }

    public boolean contains(int x, int y, int z) {
        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }

    public boolean contains(BlockPos p) {
        return contains(p.x(), p.y(), p.z());
    }

    public boolean contains(double x, double y, double z) {
        return x >= min.x() && x <= max.x() + 1
                && y >= min.y() && y <= max.y() + 1
                && z >= min.z() && z <= max.z() + 1;
    }

    /**
     * Split into a list of maximal non-empty sections (16x16x16-aligned chunks
     * intersected with the cuboid) so snapshot can be budgeted per tick.
     */
    public List<Section> sections() {
        List<Section> out = new ArrayList<>();
        int minSX = Math.floorDiv(min.x(), 16);
        int maxSX = Math.floorDiv(max.x(), 16);
        int minSY = Math.floorDiv(min.y(), 16);
        int maxSY = Math.floorDiv(max.y(), 16);
        int minSZ = Math.floorDiv(min.z(), 16);
        int maxSZ = Math.floorDiv(max.z(), 16);
        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sz = minSZ; sz <= maxSZ; sz++) {
                for (int sy = minSY; sy <= maxSY; sy++) {
                    int cx0 = Math.max(min.x(), sx * 16);
                    int cx1 = Math.min(max.x(), sx * 16 + 15);
                    int cy0 = Math.max(min.y(), sy * 16);
                    int cy1 = Math.min(max.y(), sy * 16 + 15);
                    int cz0 = Math.max(min.z(), sz * 16);
                    int cz1 = Math.min(max.z(), sz * 16 + 15);
                    out.add(new Section(cx0, cx1, cy0, cy1, cz0, cz1));
                }
            }
        }
        return out;
    }

    public record Section(int x0, int x1, int y0, int y1, int z0, int z1) {
        public long volume() {
            return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        }
    }
}
