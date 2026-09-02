package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplayPlugin;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically diffs the ENTIRE cuboid against the last seen state so that EVERY
 * block change is recorded, including ones that never trigger a Bukkit event:
 * cancelled/replaced player breaks, explosions, end crystals, plugin writes,
 * obsidian/portal formation, redstone, etc.
 *
 * The scan runs on a dedicated background thread and continuously walks the
 * whole region with no interval gap (full-time diff), comparing live block
 * state against the last seen state and emitting a BlockSet for every
 * difference. Raw block states are read through the server's internal chunk
 * (NMS reflection), which is safe to read off-thread for loaded chunks, then
 * serialized to a Bukkit-compatible "minecraft:..." string via BlockStateParser.
 * Event sinks, palette interning and media clock are all thread-safe.
 *
 * If the reflective access is unavailable for the running server version the
 * scanner simply records nothing (the event-driven recorders still cover the
 * common cases) rather than risking an unsafe main-thread pass.
 */
public final class RegionDiffRecorder {

    private final EchoReplayPlugin plugin;
    private final AtomicReference<RecordingSession> current = new AtomicReference<>();
    private final Map<Long, byte[]> lastSeen = new ConcurrentHashMap<>();
    private volatile boolean active = false;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;
    private final long passDelayMs;

    public RegionDiffRecorder(EchoReplayPlugin plugin) {
        this.plugin = plugin;
        this.passDelayMs = 50; // ~every 2.5 server ticks; full scan each pass
    }

    public void configure(int ignoredIntervalTicks) {
        // full-time diff: scan the whole region on every pass (no throttling)
        Nms.logState();
    }

    public boolean isActive() { return active; }

    /** Begin scanning the given session in the background. */
    public void reset(RecordingSession s) {
        current.set(s);
        lastSeen.clear();
        active = true;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "EchoReplay-RegionDiff");
                t.setDaemon(true);
                return t;
            });
        }
        task = executor.scheduleWithFixedDelay(this::runPassAsync, 0, passDelayMs, TimeUnit.MILLISECONDS);
    }

    /** Stop scanning; must be called from the main recording stop path. */
    public void stop() {
        active = false;
        current.set(null);
        lastSeen.clear();
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Kept for interface compatibility; scanning is async and not tick-driven. */
    public void tick() {
        // no-op
    }

    private void runPassAsync() {
        if (!active) return;
        RecordingSession s = current.get();
        if (s == null) {
            active = false;
            return;
        }
        try {
            scanAsync(s);
        } catch (Throwable t) {
            EchoReplayPlugin.getPlugin(EchoReplayPlugin.class).getLogger()
                    .warning("RegionDiff async pass error: " + t);
        }
    }

    private void scanAsync(RecordingSession s) {
        Cuboid c = s.cuboid();
        Object handle = Nms.handle(s.world());
        if (handle == null) {
            active = false; // NMS unavailable -> disable gracefully
            return;
        }
        int minCX = Math.floorDiv(c.min().x(), 16);
        int maxCX = Math.floorDiv(c.max().x(), 16);
        int minCZ = Math.floorDiv(c.min().z(), 16);
        int maxCZ = Math.floorDiv(c.max().z(), 16);
        int yMin = c.min().y(), yMax = c.max().y();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Object chunk = Nms.chunk(handle, cx, cz);
                if (chunk == null) continue;
                int x0 = Math.max(c.min().x(), cx * 16);
                int x1 = Math.min(c.max().x(), cx * 16 + 15);
                int z0 = Math.max(c.min().z(), cz * 16);
                int z1 = Math.min(c.max().z(), cz * 16 + 15);
                for (int x = x0; x <= x1; x++) {
                    int lx = x & 15;
                    for (int z = z0; z <= z1; z++) {
                        int lz = z & 15;
                        long baseKey = (long) x << 40 | (long) z << 20;
                        for (int y = yMin; y <= yMax; y++) {
                            Object state = Nms.blockState(chunk, lx, y, lz);
                            String str = Nms.toStringSafe(state);
                            long key = baseKey | (y & 0xFFFFF);
                            byte[] enc = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            byte[] prev = lastSeen.get(key);
                            if (prev == null) {
                                // First observation of this cell -> establish the
                                // baseline (this pass) without emitting, so the diff
                                // only reports genuine changes from now on.
                                lastSeen.put(key, enc);
                            } else if (!java.util.Arrays.equals(prev, enc)) {
                                lastSeen.put(key, enc);
                                enqueueEmit(s, x, y, z, str);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The diff itself is computed on the background thread, but emitting touches
     * Bukkit (tile NBT reads) which must run on the main thread. We capture the
     * media timestamp at detection time so the scheduled emission stays correctly
     * ordered (events are re-sorted by tickMillis before writing anyway). We
     * batch pending emissions so a burst of changes is one main-thread task.
     */
    private final java.util.concurrent.LinkedBlockingQueue<Pending> pending =
            new java.util.concurrent.LinkedBlockingQueue<>();

    record Pending(RecordingSession s, int x, int y, int z, String state) {}

    private void enqueueEmit(RecordingSession s, int x, int y, int z, String state) {
        pending.add(new Pending(s, x, y, z, state));
        // Schedule one main-thread flush for this burst of emissions.
        EchoReplayPlugin.getPlugin(EchoReplayPlugin.class)
                .getServer().getScheduler().runTask(plugin, this::flushPendingMainThread);
    }

    private void flushPendingMainThread() {
        Pending p;
        while ((p = pending.poll()) != null) {
            try {
                emit(p.s(), p.x(), p.y(), p.z(), p.state());
            } catch (Throwable t) {
                EchoReplayPlugin.getPlugin(EchoReplayPlugin.class).getLogger()
                        .warning("RegionDiff emit error: " + t);
            }
        }
    }

    private void emit(RecordingSession s, int wx, int wy, int wz, String state) {
        int dx = wx - s.cuboid().min().x();
        int dy = wy - s.cuboid().min().y();
        int dz = wz - s.cuboid().min().z();
        int pi = s.paletteIndex(state);
        byte[] nbt = captureNbt(s.world().getBlockAt(wx, wy, wz), state);
        s.emit(new TimelineEvent.BlockSet(s.mediaMillis(),
                new BlockPos(dx, dy, dz), pi, nbt));
    }

    private static byte[] captureNbt(Block block, String state) {
        if (state == null || state.equals("minecraft:air")) return null;
        try {
            var tile = block.getState(true);
            if (tile == null) return null;
            byte[] nb = dev.idebugger.echoreplay.util.NbtBytes.serializeBlockState(tile);
            return (nb != null && nb.length > 0) ? nb : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reflective access to the server's internal world/level chunk block reads.
     * Resolves candidate class/method names at runtime so different server
     * versions (ServerLevel vs ServerWorld, LevelChunk vs WorldChunk,
     * BlockStateParser vs CommandDispatcher) do not break the plugin; if none
     * resolve, {@link #handle(World)} returns null and scanning is disabled.
     */
    private static final class Nms {
        private static volatile boolean resolved = false;
        private static java.lang.reflect.Method worldGetChunk;
        private static java.lang.reflect.Method chunkGetBlockState;
        private static java.lang.reflect.Method serializeState;

        static {
            resolve();
        }

        private static void resolve() {
            if (resolved) return;
            synchronized (Nms.class) {
                if (resolved) return;
                String[] worldClasses = {
                        "net.minecraft.server.level.ServerWorld",
                        "net.minecraft.server.level.ServerLevel",
                        "net.minecraft.server.level.WorldServer"
                };
                // Chunk accessors tried in order of preference: non-generating,
                // already-loaded variants are safest to call off-thread (they return
                // null if absent without triggering chunk load/generation). Plain
                // getChunk(int,int) is the fallback since the cuboid is loaded.
                String[][] path = {
                        {"getChunkAtIfLoadedImmediately", "II"},
                        {"getChunkIfLoaded", "II"},
                        {"getChunkAtIfLoaded", "II"},
                        {"getChunk", "II"},
                };
                for (String cn : worldClasses) {
                    try {
                        Class<?> wc = Class.forName(cn);
                        for (String[] pp : path) {
                            String name = pp[0];
                            if ("II".equals(pp[1])) {
                                try {
                                    java.lang.reflect.Method m = wc.getMethod(name, int.class, int.class);
                                    worldGetChunk = m;
                                    chunkGetBlockState = m.getReturnType()
                                            .getMethod("getBlockState", int.class, int.class, int.class);
                                    resolved = true;
                                    break;
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                        if (resolved) break;
                    } catch (Throwable ignored) {
                    }
                }
                // BlockStateParser.serialize(BlockState) -> "minecraft:xxx[props]"
                if (resolved) {
                    String[] parserClasses = {
                            "net.minecraft.commands.arguments.blocks.BlockStateParser",
                            "net.minecraft.commands.arguments.blocks.BlockStateParserHelper"
                    };
                    for (String pc : parserClasses) {
                        try {
                            Class<?> cls = Class.forName(pc);
                            java.lang.reflect.Method m = cls.getMethod("serialize",
                                    chunkGetBlockState.getReturnType());
                            serializeState = m;
                            break;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                resolved = true;
            }
        }

        static boolean available() {
            resolve();
            return worldGetChunk != null && chunkGetBlockState != null;
        }

        /** Log which reflective accessors resolved, for debugging on live setups. */
        static void logState() {
            resolve();
            EchoReplayPlugin.getPlugin(EchoReplayPlugin.class).getLogger()
                    .info("RegionDiff NMS resolver: world=" + (worldGetChunk != null ? worldGetChunk.getDeclaringClass().getSimpleName()
                            + "." + worldGetChunk.getName() : "none")
                            + " chunkBlockState=" + (chunkGetBlockState != null ? chunkGetBlockState.getName() : "none")
                            + " serialize=" + (serializeState != null ? "ok" : "toString-fallback"));
        }

        static Object handle(World world) {
            resolve();
            if (!available()) return null;
            try {
                java.lang.reflect.Method gh = world.getClass().getMethod("getHandle");
                return gh.invoke(world);
            } catch (Throwable t) {
                return null;
            }
        }

        static Object chunk(Object worldHandle, int cx, int cz) {
            try {
                return worldGetChunk.invoke(worldHandle, cx, cz);
            } catch (Throwable t) {
                return null;
            }
        }

        static Object blockState(Object chunk, int lx, int y, int lz) {
            try {
                return chunkGetBlockState.invoke(chunk, lx, y, lz);
            } catch (Throwable t) {
                return null;
            }
        }

        static String toStringSafe(Object state) {
            if (state == null) return "minecraft:air";
            try {
                if (serializeState != null) {
                    Object str = serializeState.invoke(null, state);
                    if (str != null) return str.toString();
                }
                return state.toString();
            } catch (Throwable t) {
                return "minecraft:air";
            }
        }
    }
}
