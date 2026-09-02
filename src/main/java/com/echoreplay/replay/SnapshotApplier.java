package com.echoreplay.replay;

import com.echoreplay.select.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Applies a recording's initial snapshot blocks to the world (world-mode).
 * Physics is suppressed via setBlockData(data, false) so water/sand do not
 * self-apply extra updates that were not recorded.
 */
public final class SnapshotApplier {

    private SnapshotApplier() {}

    /** @return number of blocks set */
    public static int applyToWorld(World world, Cuboid cuboid, int[] data, int sizeX, int sizeY, int sizeZ,
                                   java.util.List<String> palette) {
        int count = 0;
        int idx = 0;
        for (int dy = 0; dy < sizeY; dy++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    int pi = data[idx];
                    idx++;
                    String state = palette.get(pi);
                    BlockData blockData = Bukkit.createBlockData(state);
                    world.getBlockAt(cuboid.min().x() + dx, cuboid.min().y() + dy, cuboid.min().z() + dz)
                            .setBlockData(blockData, false);
                    count++;
                }
            }
        }
        return count;
    }
}
