package com.echoreplay.record;

import com.echoreplay.select.Cuboid;
import com.echoreplay.util.PalettedStorage;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the current block state of a cuboid into a {@link PalettedStorage} with
 * 1:1 BlockData fidelity. Must run on the main thread (Paper BlockData is not
 * thread-safe).
 */
public final class Snapshotter {

    private Snapshotter() {}

    public record Snapshot(PalettedStorage storage, Map<String, byte[]> blockNbt) {}

    /**
     * Full synchronous snapshot of the cuboid. For large regions prefer
     * {@link #snapshotSection} streamed over ticks.
     */
    public static Snapshot snapshot(World world, Cuboid cuboid) {
        PalettedStorage storage = new PalettedStorage(cuboid.xSize(), cuboid.ySize(), cuboid.zSize());
        Map<String, byte[]> nbtMap = new LinkedHashMap<>();
        int minY = cuboid.min().y();
        int minX = cuboid.min().x();
        int minZ = cuboid.min().z();
        for (int dx = 0; dx < cuboid.xSize(); dx++) {
            for (int dy = 0; dy < cuboid.ySize(); dy++) {
                for (int dz = 0; dz < cuboid.zSize(); dz++) {
                    int wx = minX + dx;
                    int wy = minY + dy;
                    int wz = minZ + dz;
                    Block block = world.getBlockAt(wx, wy, wz);
                    BlockData data = block.getBlockData();
                    int idx = storage.ensure(data.getAsString(true));
                    storage.set(dx, dy, dz, idx);
                    BlockState state = block.getState();
                    if (needsNbt(state.getType())) {
                        byte[] nb = com.echoreplay.util.NbtBytes.serializeBlockState(state);
                        if (nb != null && nb.length > 0) {
                            nbtMap.put(key(dx, dy, dz), nb);
                        }
                    }
                }
            }
        }
        return new Snapshot(storage, nbtMap);
    }

    public static boolean needsNbt(Material type) {
        return switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX, FURNACE, BLAST_FURNACE,
                 SMOKER, DROPPER, DISPENSER, HOPPER, BREWING_STAND, LECTERN, CAMPFIRE,
                 SOUL_CAMPFIRE, DECORATED_POT, VAULT, CRAFTER, JUKEBOX,
                 OAK_SIGN, SPRUCE_SIGN, BIRCH_SIGN, JUNGLE_SIGN, ACACIA_SIGN, DARK_OAK_SIGN,
                 MANGROVE_SIGN, CHERRY_SIGN, BAMBOO_SIGN, CRIMSON_SIGN, WARPED_SIGN,
                 OAK_WALL_SIGN, SPRUCE_WALL_SIGN, BIRCH_WALL_SIGN, JUNGLE_WALL_SIGN,
                 ACACIA_WALL_SIGN, DARK_OAK_WALL_SIGN, MANGROVE_WALL_SIGN, CHERRY_WALL_SIGN,
                 BAMBOO_WALL_SIGN, CRIMSON_WALL_SIGN, WARPED_WALL_SIGN,
                 OAK_HANGING_SIGN, SPRUCE_HANGING_SIGN, BIRCH_HANGING_SIGN, JUNGLE_HANGING_SIGN,
                 ACACIA_HANGING_SIGN, DARK_OAK_HANGING_SIGN, MANGROVE_HANGING_SIGN, CHERRY_HANGING_SIGN,
                 BAMBOO_HANGING_SIGN, CRIMSON_HANGING_SIGN, WARPED_HANGING_SIGN,
                 PLAYER_HEAD, PLAYER_WALL_HEAD, SKELETON_SKULL, SKELETON_WALL_SKULL,
                 WITHER_SKELETON_SKULL, WITHER_SKELETON_WALL_SKULL, ZOMBIE_HEAD, ZOMBIE_WALL_HEAD,
                 CREEPER_HEAD, CREEPER_WALL_HEAD, DRAGON_HEAD, DRAGON_WALL_HEAD, PIGLIN_HEAD,
                 PIGLIN_WALL_HEAD -> true;
            default -> false;
        };
    }

    public static String key(int dx, int dy, int dz) {
        return dx + "," + dy + "," + dz;
    }
}
