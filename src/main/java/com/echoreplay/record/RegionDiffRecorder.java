package com.echoreplay.record;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.BlockPos;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.select.Cuboid;
import com.echoreplay.util.NbtBytes;
import com.echoreplay.util.PalettedStorage;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Periodically diffs the entire cuboid against the last seen snapshot so that
 * EVERY block change is recorded, including ones that never trigger a Bukkit
 * event the WorldListener registered for: player breaks whose BlockBreakEvent
 * is cancelled/replaced, explosions, end crystals, plugin block writes,
 * obsidian/portal formation, redstone, etc.
 *
 * The scan is spread across ticks (budget) and runs on the main thread (Bukkit
 * block data is not thread safe), mirroring the initial snapshot approach. It
 * compares the live block state string against the previous scan and emits a
 * BlockSet for each difference so playback reflects reality regardless of how
 * the change happened.
 */
public final class RegionDiffRecorder {

    private final EchoReplayPlugin plugin;

    // last-seen state per cuboid cell (interns independently of the writer palette)
    private PalettedStorage lastSeen;
    private int nextIndex = 0;   // linear cursor through the cuboid volume
    private int scanIntervalTicks = 20;  // how often a full pass is triggered
    private int counter = 0;

    public RegionDiffRecorder(EchoReplayPlugin plugin) {
        this.plugin = plugin;
    }

    public void configure(int intervalTicks) {
        this.scanIntervalTicks = Math.max(1, intervalTicks);
    }

    public void reset(RecordingSession s) {
        Cuboid c = s.cuboid();
        PalettedStorage last = new PalettedStorage(c.xSize(), c.ySize(), c.zSize());
        // Prime lastSeen from the already-captured initial snapshot so the first
        // diff pass does not re-emit the whole region as "changes". The snapshot
        // storage stores indices into the *recording* palette, so resolve each
        // stored index back to a state string via the session palette.
        PalettedStorage snap = s.snapshotStorage();
        if (snap != null) {
            for (int dy = 0; dy < c.ySize(); dy++) {
                for (int dz = 0; dz < c.zSize(); dz++) {
                    for (int dx = 0; dx < c.xSize(); dx++) {
                        int idx = snap.get(dx, dy, dz);
                        try {
                            String st = s.paletteState(idx);
                            last.set(dx, dy, dz, last.ensure(st));
                        } catch (Exception ignored) {
                            last.set(dx, dy, dz, last.ensure("minecraft:air"));
                        }
                    }
                }
            }
        }
        lastSeen = last;
        nextIndex = 0;
        counter = 0;
    }

    /** Called each recording tick on the main thread. */
    public void tick() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (lastSeen == null) {
            reset(s);
        }
        counter++;
        if (counter < scanIntervalTicks) return;
        counter = 0;
        runPass(s);
    }

    private void runPass(RecordingSession s) {
        Cuboid c = s.cuboid();
        World world = s.world();
        int sizeX = c.xSize(), sizeY = c.ySize(), sizeZ = c.zSize();
        int volume = sizeX * sizeY * sizeZ;
        int budget = plugin.cfg().getInt("recording.scan-blocks-per-tick", 8000);

        int scanned = 0;
        while (scanned < budget && nextIndex < volume) {
            int idx = nextIndex++;
            int dy = idx / (sizeX * sizeZ);
            int dz = (idx / sizeX) % sizeZ;
            int dx = idx % sizeX;

            int wx = c.min().x() + dx;
            int wy = c.min().y() + dy;
            int wz = c.min().z() + dz;

            Block block = world.getBlockAt(wx, wy, wz);
            String state = block.getBlockData() == null ? "minecraft:air" : block.getBlockData().getAsString(true);
            int prev = lastSeen.get(dx, dy, dz);
            int newIdx = lastSeen.ensure(state);

            if (newIdx != prev) {
                lastSeen.set(dx, dy, dz, newIdx);
                int pi = s.paletteIndex(state);
                byte[] nbt = captureNbt(block, state);
                s.emit(new TimelineEvent.BlockSet(s.mediaMillis(),
                        new BlockPos(dx, dy, dz), pi, nbt));
            }
            scanned++;
        }

        // reset cursor for the next full pass once we've walked the whole region
        if (nextIndex >= volume) {
            nextIndex = 0;
        }
    }

    private static byte[] captureNbt(Block block, String state) {
        if (state == null || state.equals("minecraft:air")) return null;
        try {
            var tile = block.getState(true);
            if (tile == null) return null;
            byte[] nb = NbtBytes.serializeBlockState(tile);
            return (nb != null && nb.length > 0) ? nb : null;
        } catch (Exception e) {
            return null;
        }
    }
}
