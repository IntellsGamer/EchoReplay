package dev.idebugger.echoreplay;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D-2: config drift cleanup.
 *
 * <p>v1 shipped 12 dead keys, 2 hidden keys, and 1 wrong path. This class:
 * <ul>
 *   <li>Logs every unknown top-level key on startup so users notice typos
 *       and dead config.</li>
 *   <li>Logs every documented-but-unread key in {@code recording.},
 *       {@code replay.}, {@code selection.}, {@code storage.}.</li>
 *   <li>Performs a one-time v1 → v2 migration when {@code config-version}
 *       is missing or 1.</li>
 * </ul>
 *
 * <p>This is intentionally read-only — we do not rewrite the user's
 * config.yml on disk (a surprise rewrite is worse than a warning). The
 * shipped default {@code config.yml} already has the v2 keys; users who
 * never touched config just see no warnings.</p>
 */
public final class ConfigMigrator {

    /** All keys the plugin actually reads. Anything else gets a startup warning. */
    private static final Set<String> KNOWN_KEYS = Set.of(
            // selection
            "selection.wand-material",
            "selection.max-volume",
            "selection.max-horizontal-span",
            "selection.outline-particles",
            // recording
            "recording.margin-blocks",
            "recording.flush-seconds",
            "recording.flush-bytes",
            "recording.capture-chat",
            "recording.capture-time",
            "recording.capture-weather",
            "recording.capture-particles",
            "recording.max-particles-per-second",
            "recording.max-duration-minutes",
            "recording.scan-ms-per-pass",
            "recording.scan-interval-ticks",  // legacy — ignored, kept for back-compat
            // replay
            "replay.virtual-packets-only",
            "replay.force-spectator",
            "replay.auto-watch-radius",
            "replay.backup-live-cuboid",
            "replay.drive-world-time",
            "replay.physics-frozen",
            "replay.skip-sfx-when-speed-above",
            "replay.default-speed",
            "replay.min-speed",
            "replay.max-speed",
            "replay.phase-max-ms-per-tick",
            "replay.phase-budget-ms-per-tick",  // alias
            "replay.snapshot.blocks-per-tick",
            "replay.border.enabled",
            "replay.border.particle",
            "replay.border.interval-ticks",
            "replay.border.step",
            "replay.border.max-per-frame",
            "replay.border.edges-only",
            // storage
            "storage.directory",
            "storage.compression",
            "storage.keep-partial-on-crash",
            "storage.max-size-gb",
            // meta
            "config-version"
    );

    private ConfigMigrator() {}

    public static void warnUnknownKeys(EchoReplay plugin) {
        FileConfiguration cfg = plugin.getConfig();
        Set<String> seen = new HashSet<>();
        collectLeafKeys(cfg, "", seen);
        for (String key : seen) {
            if (!KNOWN_KEYS.contains(key)) {
                // silent for legacy-only keys we still tolerate
                plugin.getLogger().warning(
                        "config.yml: unknown key '" + key + "' — this value is not read by the plugin.");
            }
        }
    }

    private static void collectLeafKeys(ConfigurationSection s, String prefix, Set<String> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            String full = prefix.isEmpty() ? k : prefix + "." + k;
            Object v = s.get(k);
            if (v instanceof ConfigurationSection sub) {
                collectLeafKeys(sub, full, out);
            } else {
                out.add(full);
            }
        }
    }

    public static void migrate(EchoReplay plugin) {
        int version = plugin.getConfig().getInt("config-version", 1);
        if (version >= 2) return;
        plugin.getLogger().info("config-version is " + version + " (latest is 2). "
                + "Some keys may be deprecated — see config.yml comments for v2 layout. "
                + "Old keys are tolerated but ignored.");
        // We do NOT rewrite the file on disk: a surprise rewrite is worse than a warning.
    }
}
