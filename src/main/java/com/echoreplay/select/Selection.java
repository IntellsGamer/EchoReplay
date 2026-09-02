package com.echoreplay.select;

import com.echoreplay.model.BlockPos;
import org.bukkit.World;

/**
 * A player's in-progress selection: a world and zero, one, or two points.
 */
public final class Selection {

    private final World world;
    private BlockPos pos1;
    private BlockPos pos2;

    public Selection(World world) {
        this.world = world;
    }

    public World world() { return world; }
    public BlockPos pos1() { return pos1; }
    public BlockPos pos2() { return pos2; }

    public void setPos1(BlockPos p) { this.pos1 = p; }
    public void setPos2(BlockPos p) { this.pos2 = p; }

    public boolean isComplete() {
        return pos1 != null && pos2 != null;
    }

    public Cuboid cuboid() {
        if (!isComplete()) {
            throw new IllegalStateException("Selection incomplete");
        }
        return Cuboid.of(pos1, pos2, world);
    }

    public boolean contains(BlockPos p) {
        return isComplete() && cuboid().contains(p);
    }

    public void clear() {
        pos1 = null;
        pos2 = null;
    }
}
