package dev.idebugger.echoreplay.select;

import dev.idebugger.echoreplay.model.BlockPos;
import org.bukkit.World;

/**
 * A player's in-progress selection: a world and zero, one, or two points.
 *
 * <p>D-8.3: the bound world now follows the player's current world whenever
 * a corner is set, mirroring WorldEdit's behavior. v1 bound the world at
 * first use and then refused cross-world corner sets with "Selection must
 * be in your current world" — the only fix was the undocumented /er clear.</p>
 */
public final class Selection {

    private World world;
    private BlockPos pos1;
    private BlockPos pos2;

    public Selection(World world) {
        this.world = world;
    }

    public World world() { return world; }
    public BlockPos pos1() { return pos1; }
    public BlockPos pos2() { return pos2; }

    /** Re-bind the selection to a world (used when a player sets a corner in a new world). */
    public void bindWorld(World w) {
        // If both corners are unset, this is a fresh selection — just bind.
        // If at least one corner exists and the world actually changed, reset
        // both corners: a cross-world cuboid is meaningless and silently
        // keeping the old corners would mislead the user.
        if (w == null) return;
        if (world == null || !world.getUID().equals(w.getUID())) {
            if (pos1 != null || pos2 != null) {
                // world changed mid-selection: drop the old corners to avoid
                // a confusing half-cross-world selection
                pos1 = null;
                pos2 = null;
            }
            world = w;
        }
    }

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
