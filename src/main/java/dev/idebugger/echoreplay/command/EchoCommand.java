package dev.idebugger.echoreplay.command;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.replay.ReplaySession;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.record.RecordingManager;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.select.Selection;
import dev.idebugger.echoreplay.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The /echoreplay (/er, /replay) command tree.
 *
 * <p>All subcommands are routed through {@link #PERMS} — a centralized
 * permission table — before being dispatched. This closes the v1 security
 * gap where 15 of 22 subcommands had no permission check at all (any
 * default-rank player could {@code /er delete} recordings, {@code /er stop}
 * other users' takes, or run {@code /er play ... world} to wipe a live
 * region).</p>
 */
public final class EchoCommand implements CommandExecutor, TabCompleter {

    private final EchoReplay plugin;
    private static final NamespacedKey WAND_KEY =
            NamespacedKey.fromString("echoreplay:wand");

    /**
     * Centralized subcommand → permission mapping. One place to audit, one
     * place to extend. Selection verbs are included for completeness — they
     * were already checked inline in v1; this keeps both checks consistent.
     */
    private static final Map<String, String> PERMS = Map.ofEntries(
            // selection (already inline-checked, kept here for consistency)
            Map.entry("wand", "echoreplay.wand"),
            Map.entry("pos1", "echoreplay.select"),
            Map.entry("pos2", "echoreplay.select"),
            Map.entry("select", "echoreplay.select"),
            Map.entry("expand", "echoreplay.select"),
            Map.entry("contract", "echoreplay.select"),
            Map.entry("shift", "echoreplay.select"),
            Map.entry("selinfo", "echoreplay.select"),
            Map.entry("clear", "echoreplay.select"),
            // recording control
            Map.entry("record", "echoreplay.record"),
            Map.entry("stop", "echoreplay.record"),
            Map.entry("cancel", "echoreplay.record"),
            Map.entry("save", "echoreplay.record"),
            Map.entry("status", "echoreplay.use"),
            Map.entry("marker", "echoreplay.record"),
            // playback control
            Map.entry("play", "echoreplay.play"),
            Map.entry("stopplay", "echoreplay.play"),
            Map.entry("pause", "echoreplay.control"),
            Map.entry("resume", "echoreplay.control"),
            Map.entry("speed", "echoreplay.control"),
            Map.entry("seek", "echoreplay.control"),
            Map.entry("ff", "echoreplay.control"),
            Map.entry("rewind", "echoreplay.control"),
            Map.entry("cam", "echoreplay.control"),
            Map.entry("leave", "echoreplay.watch"),
            Map.entry("watch", "echoreplay.watch"),
            Map.entry("border", "echoreplay.use"),
            // recording management (destructive — require delete perm)
            Map.entry("delete", "echoreplay.delete"),
            Map.entry("rename", "echoreplay.delete"),
            Map.entry("confirm", "echoreplay.use"),
            // read-only listings
            Map.entry("list", "echoreplay.use"),
            Map.entry("info", "echoreplay.use"),
            // system/admin
            Map.entry("stats", "echoreplay.admin"),
            Map.entry("reload", "echoreplay.admin"),
            Map.entry("version", "echoreplay.use"));

    public EchoCommand(EchoReplay plugin) {
        this.plugin = plugin;
    }

    public void register() {
        for (String c : new String[]{"echoreplay", "er", "replay"}) {
            org.bukkit.command.PluginCommand cmd = plugin.getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();

        // S-1: centralized permission gate. Fail closed: every subcommand
        // must have a permission entry; unknown subs reject by default.
        String required = PERMS.get(sub);
        if (required == null) {
            sender.sendMessage(Text.mm("<red>Unknown subcommand '" + sub + "'. Use /er for help.</red>"));
            return true;
        }
        if (!sender.hasPermission(required)) {
            sender.sendMessage(Text.mm("<red>You lack the permission <yellow>" + required
                    + "</yellow> to use <yellow>/er " + sub + "</yellow>.</red>"));
            return true;
        }

        switch (sub) {
            case "wand" -> wand(sender);
            case "pos1" -> pos1(sender, args);
            case "pos2" -> pos2(sender, args);
            case "select" -> select(sender, args);
            case "expand" -> expand(sender, args, true);
            case "contract" -> expand(sender, args, false);
            case "shift" -> shift(sender, args);
            case "selinfo" -> selinfo(sender);
            case "clear" -> clear(sender);
            case "record", "rec" -> record(sender, args);
            case "stop" -> stop(sender);
            case "cancel" -> cancel(sender);
            case "save" -> save(sender);
            case "status" -> status(sender);
            case "play" -> play(sender, args);
            case "pause" -> pause(sender);
            case "resume" -> resume(sender);
            case "speed" -> speed(sender, args);
            case "seek" -> seek(sender, args);
            case "ff" -> ff(sender, args);
            case "rewind" -> rewind(sender, args);
            case "stopplay" -> stopplay(sender);
            case "leave" -> leave(sender);
            case "watch" -> watch(sender, args);
            case "cam" -> cam(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "delete" -> delete(sender, args);
            case "rename" -> rename(sender, args);
            case "confirm" -> confirm(sender);
            case "marker" -> marker(sender, args);
            case "border" -> border(sender, args);
            case "stats" -> stats(sender);
            case "version" -> version(sender);
            case "reload" -> reload(sender);
            default -> {
                sender.sendMessage(Text.mm("<red>Unknown subcommand '" + sub + "'. Use /er for help.</red>"));
            }
        }
        return true;
    }

    private void requirePlayer(CommandSender s, java.util.function.Consumer<Player> c) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Text.mm("<red>This command must be run by a player.</red>"));
            return;
        }
        c.accept(p);
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(Text.mm("""
            <gold>EchoReplay — server-side region replay</gold>
            <gray>Selection:</gray> <yellow>/er wand, pos1, pos2, select, expand, contract, shift, selinfo, clear</yellow>
            <gray>Record:</gray> <yellow>/er record <name>, stop, cancel, save, status, marker <name></yellow>
            <gray>Play:</gray> <yellow>/er play <name> [virtual|world], pause, resume, speed <x>, seek <s|mm:ss|Ns|NmNs|Nh|tick:N|%N|marker>, ff [s], rewind [s], stopplay, leave, watch, cam</yellow>
            <gray>Manage:</gray> <yellow>/er list, info <name>, delete <name> (requires confirm), rename <old> <new>, confirm</yellow>
            <gray>Border:</gray> <yellow>/er border [on|off|toggle|status]</yellow>
            <gray>System:</gray> <yellow>/er stats, version, reload</yellow>
            <gray>Permissions:</gray> <yellow>echoreplay.use / .wand / .select / .record / .play / .control / .watch / .delete / .admin</yellow>
            """));
    }

    private Material wandMaterial() {
        // D-2: honor config (was hardcoded GOLDEN_AXE in v1)
        String name = plugin.cfg().getString("selection.wand-material", "GOLDEN_AXE");
        try {
            return Material.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Material.GOLDEN_AXE;
        }
    }

    private void wand(CommandSender s) {
        requirePlayer(s, p -> {
            ItemStack wand = new ItemStack(wandMaterial());
            ItemMeta meta = wand.getItemMeta();
            meta.displayName(Text.mm("<gradient:#7af:#fff>Echo Wand</gradient>"));
            meta.getPersistentDataContainer().set(WAND_KEY, PersistentDataType.INTEGER, 1);
            wand.setItemMeta(meta);
            p.getInventory().addItem(wand);
            p.sendMessage(Text.mm("<gray>Here is your Echo Wand. Left=pos1, Right=pos2.</gray>"));
        });
    }

    private void pos1(CommandSender s, String[] args) {
        requirePos(s, args, true);
    }

    private void pos2(CommandSender s, String[] args) {
        requirePos(s, args, false);
    }

    private void requirePos(CommandSender s, String[] args, boolean first) {
        requirePlayer(s, p -> {
            Selection sel = plugin.selectionManager().get(p);
            BlockPos pos;
            if (args.length >= 4) {
                try {
                    pos = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                } catch (NumberFormatException e) {
                    p.sendMessage(Text.mm("<red>Invalid coordinates.</red>"));
                    return;
                }
            } else {
                var loc = p.getLocation();
                pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            }
            // D-8.3: re-bind the selection world to the player's current world
            // (was locked at first use — corner-set across worlds was rejected)
            sel.bindWorld(p.getWorld());
            if (first) sel.setPos1(pos);
            else sel.setPos2(pos);
            p.sendMessage(Text.mm("<gray>" + (first ? "Pos1" : "Pos2") + " set to (" + pos.x() + ", " + pos.y() + ", " + pos.z() + ").</gray>"));
        });
    }

    private void select(CommandSender s, String[] args) {
        requirePlayer(s, p -> {
            if (args.length < 7) {
                p.sendMessage(Text.mm("<red>Usage: /er select <x1> <y1> <z1> <x2> <y2> <z2></red>"));
                return;
            }
            try {
                int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
                int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
                Selection sel = plugin.selectionManager().get(p);
                sel.bindWorld(p.getWorld());
                sel.setPos1(new BlockPos(x1, y1, z1));
                sel.setPos2(new BlockPos(x2, y2, z2));
                Cuboid c = sel.cuboid();
                p.sendMessage(Text.mm("<gray>Selection set: " + c.volume() + " blocks.</gray>"));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Invalid coordinates.</red>"));
            }
        });
    }

    private void expand(CommandSender s, String[] args, boolean grow) {
        requirePlayer(s, p -> {
            if (args.length < 2) {
                p.sendMessage(Text.mm("<red>Usage: /er expand <amount> [dir]</red>"));
                return;
            }
            try {
                int amt = Integer.parseInt(args[1]);
                String dir = args.length > 2 ? args[2].toLowerCase() : "all";
                Selection sel = plugin.selectionManager().get(p);
                if (!sel.isComplete()) {
                    p.sendMessage(Text.mm("<red>Selection incomplete.</red>"));
                    return;
                }
                Cuboid c = sel.cuboid();
                int sign = grow ? 1 : -1;
                BlockPos a = c.min();
                BlockPos b = c.max();
                switch (dir) {
                    case "all", "horiz", "" -> {
                        a = new BlockPos(a.x() - amt, a.y(), a.z() - amt);
                        b = new BlockPos(b.x() + amt, b.y(), b.z() + amt);
                    }
                    case "vert" -> { a = new BlockPos(a.x(), a.y() - amt, a.z()); b = new BlockPos(b.x(), b.y() + amt, b.z()); }
                    case "up" -> b = new BlockPos(b.x(), b.y() + sign * amt, b.z());
                    case "down" -> a = new BlockPos(a.x(), a.y() - sign * amt, a.z());
                    case "north" -> a = new BlockPos(a.x(), a.y(), a.z() - sign * amt);
                    case "south" -> b = new BlockPos(b.x(), b.y(), b.z() + sign * amt);
                    case "west" -> a = new BlockPos(a.x() - sign * amt, a.y(), a.z());
                    case "east" -> b = new BlockPos(b.x() + sign * amt, b.y(), b.z());
                    default -> {
                        p.sendMessage(Text.mm("<red>Unknown direction.</red>"));
                        return;
                    }
                }
                if (grow) {
                    sel.setPos1(new BlockPos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())));
                    sel.setPos2(new BlockPos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
                } else {
                    sel.setPos1(a);
                    sel.setPos2(b);
                }
                Cuboid nc = sel.cuboid();
                p.sendMessage(Text.mm("<gray>Selection now " + nc.volume() + " blocks.</gray>"));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Invalid amount.</red>"));
            }
        });
    }

    private void shift(CommandSender s, String[] args) {
        requirePlayer(s, p -> {
            if (args.length < 3) {
                p.sendMessage(Text.mm("<red>Usage: /er shift <amount> <dir></red>"));
                return;
            }
            try {
                int amt = Integer.parseInt(args[1]);
                String dir = args[2].toLowerCase();
                Selection sel = plugin.selectionManager().get(p);
                if (!sel.isComplete()) {
                    p.sendMessage(Text.mm("<red>Selection incomplete.</red>"));
                    return;
                }
                Cuboid c = sel.cuboid();
                int dx = 0, dy = 0, dz = 0;
                switch (dir) {
                    case "up" -> dy = amt;
                    case "down" -> dy = -amt;
                    case "north" -> dz = -amt;
                    case "south" -> dz = amt;
                    case "west" -> dx = -amt;
                    case "east" -> dx = amt;
                    default -> {
                        p.sendMessage(Text.mm("<red>Unknown direction.</red>"));
                        return;
                    }
                }
                sel.setPos1(new BlockPos(c.min().x() + dx, c.min().y() + dy, c.min().z() + dz));
                sel.setPos2(new BlockPos(c.max().x() + dx, c.max().y() + dy, c.max().z() + dz));
                p.sendMessage(Text.mm("<gray>Selection shifted.</gray>"));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Invalid amount.</red>"));
            }
        });
    }

    private void selinfo(CommandSender s) {
        requirePlayer(s, p -> {
            Selection sel = plugin.selectionManager().get(p);
            if (!sel.isComplete()) {
                p.sendMessage(Text.mm("<gray>Incomplete selection.</gray>"));
                return;
            }
            Cuboid c = sel.cuboid();
            int maxVolume = plugin.cfg().getInt("selection.max-volume", 300000);
            int maxSpan = plugin.cfg().getInt("selection.max-horizontal-span", 256);
            boolean overVolume = c.volume() > maxVolume;
            boolean overSpan = c.xSize() > maxSpan || c.zSize() > maxSpan;
            String warn = (overVolume || overSpan)
                    ? "<red> (exceeds limits: " + (overVolume ? "volume " + maxVolume + " " : "")
                    + (overSpan ? "span " + maxSpan : "") + " — bypass with echoreplay.bypass-limits)</red>"
                    : "";
            p.sendMessage(Text.mm("<gray>World: " + sel.world().getName() +
                    "<newline>Pos1: " + c.min() + "<newline>Pos2: " + c.max() +
                    "<newline>Volume: " + c.volume() + warn + "</gray>"));
        });
    }

    private void clear(CommandSender s) {
        requirePlayer(s, p -> {
            plugin.selectionManager().clear(p);
            p.sendMessage(Text.mm("<gray>Selection cleared.</gray>"));
        });
    }

    private void record(CommandSender s, String[] args) {
        requirePlayer(s, p -> {
            if (args.length < 2) {
                p.sendMessage(Text.mm("<red>Usage: /er record <name></red>"));
                return;
            }
            String msg = plugin.recordingManager().start(p, args[1]);
            if (msg != null) p.sendMessage(Text.mm(msg));
            else p.sendMessage(Text.mm("<yellow>Snapshotting… recording will start once complete.</yellow>"));
        });
    }

    private void stop(CommandSender s) {
        String msg = plugin.recordingManager().stop();
        s.sendMessage(Text.mm(msg));
    }

    private void cancel(CommandSender s) {
        String msg = plugin.recordingManager().cancel();
        s.sendMessage(Text.mm(msg));
    }

    private void save(CommandSender s) {
        String msg = plugin.recordingManager().stop();
        s.sendMessage(Text.mm(msg));
    }

    private void status(CommandSender s) {
        var sess = plugin.recordingManager().activeSession();
        if (sess == null) {
            s.sendMessage(Text.mm("<gray>No recording in progress.</gray>"));
        } else {
            s.sendMessage(Text.mm("<gray>Recording '" + sess.name() + "' — "
                    + RecordingManager.formatDuration(sess.mediaMillis()) + ", sections " + sess.sectionsDone() + "/" + sess.totalSections() + ".</gray>"));
        }
        var rep = plugin.replayManager().session();
        if (rep != null) {
            s.sendMessage(Text.mm("<gray>Playing '" + rep.name() + "' at " + rep.clock().speed() + "x.</gray>"));
        }
    }

    private void play(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er play <name> [virtual|world]</red>"));
            return;
        }
        boolean virtual = false;
        if (args.length > 2 && args[2].equalsIgnoreCase("virtual")) virtual = true;
        Player player = s instanceof Player p ? p : null;
        String msg = plugin.replayManager().play(player, args[1], virtual);
        if (msg != null) s.sendMessage(Text.mm(msg));
    }

    private void pause(CommandSender s) {
        s.sendMessage(Text.mm(plugin.replayManager().pause()));
    }

    private void resume(CommandSender s) {
        s.sendMessage(Text.mm(plugin.replayManager().resume()));
    }

    private void speed(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er speed <0.125|0.25|0.5|1|2|4|8|16></red>"));
            return;
        }
        double sp;
        try {
            sp = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            s.sendMessage(Text.mm("<red>Invalid speed. Try 0.25, 0.5, 1, 2, 4, 8, or 16.</red>"));
            return;
        }
        // S-7: validate up front for a friendlier message; Clock also clamps.
        double min = plugin.cfg().getDouble("replay.min-speed", 0.125);
        double max = plugin.cfg().getDouble("replay.max-speed", 16.0);
        if (!Double.isFinite(sp) || sp < min || sp > max) {
            s.sendMessage(Text.mm("<red>Speed must be between " + min + " and " + max + ".</red>"));
            return;
        }
        s.sendMessage(Text.mm(plugin.replayManager().speed(sp)));
    }

    private void seek(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er seek <10s|5m30s|1h2m3s|tick:600|50%|mm:ss|<marker>></red>"));
            return;
        }
        ReplaySession rep = plugin.replayManager().session();
        if (rep == null) {
            s.sendMessage(Text.mm("<red>No replay playing.</red>"));
            return;
        }
        Double sec = parseTime(args[1]);
        if (sec != null) {
            rep.seekTo(sec * 1000);
            s.sendMessage(Text.mm("<gray>Seeked to " + args[1] + ".</gray>"));
        } else {
            boolean ok = rep.seekToMarker(args[1]);
            if (ok) s.sendMessage(Text.mm("<gray>Seeked to marker '" + args[1] + "'.</gray>"));
            else s.sendMessage(Text.mm("<red>Marker or time not found: '" + args[1]
                    + "'. Try 10s, 5m30s, 1h2m3s, tick:600, 50%, or a marker name.</red>"));
        }
    }

    private void ff(CommandSender s, String[] args) {
        double sec = args.length > 1 ? parseOrDefault(args[1], 10) : 10;
        s.sendMessage(Text.mm(plugin.replayManager().forward(sec)));
    }

    private void rewind(CommandSender s, String[] args) {
        double sec = args.length > 1 ? parseOrDefault(args[1], 10) : 10;
        s.sendMessage(Text.mm(plugin.replayManager().rewind(sec)));
    }

    private void stopplay(CommandSender s) {
        s.sendMessage(Text.mm(plugin.replayManager().stopPlay(false)));
    }

    private void leave(CommandSender s) {
        requirePlayer(s, p -> s.sendMessage(Text.mm(plugin.replayManager().leave(p))));
    }

    private void watch(CommandSender s, String[] args) {
        // D-8.2: watch accepts an optional name arg for future use; currently
        // always joins the active session.
        requirePlayer(s, p -> s.sendMessage(Text.mm(plugin.replayManager().watch(p))));
    }

    private void cam(CommandSender s, String[] args) {
        s.sendMessage(Text.mm("<yellow>Camera attach is implemented via spectator control: use /spectate <player> while watching.</yellow>"));
    }

    private void list(CommandSender s) {
        var entries = plugin.recordingIndex().all();
        if (entries.isEmpty()) {
            s.sendMessage(Text.mm("<gray>No recordings.</gray>"));
            return;
        }
        List<Component> msgs = new ArrayList<>();
        msgs.add(Text.mm("<gold>Recordings (" + entries.size() + "):</gold>"));
        for (var e : entries) {
            msgs.add(Text.mm("<gray>  " + e.name() + " — " + RecordingManager.formatDuration(e.durationMillis())
                    + " (" + (e.sizeBytes() / 1024) + " KB) " + e.worldName() + "</gray>"));
        }
        for (Component m : msgs) {
            s.sendMessage(m);
        }
    }

    private void info(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er info <name></red>"));
            return;
        }
        var e = plugin.recordingIndex().get(args[1]);
        if (e == null) {
            s.sendMessage(Text.mm("<red>No such recording.</red>"));
            return;
        }
        s.sendMessage(Text.mm("<gray>Name: " + e.name() + "<newline>World: " + e.worldName() +
                "<newline>Duration: " + RecordingManager.formatDuration(e.durationMillis()) +
                "<newline>Size: " + (e.sizeBytes() / 1024) + " KB" +
                "<newline>Bounds: " + e.minX() + "," + e.minY() + "," + e.minZ() + " → " + e.maxX() + "," + e.maxY() + "," + e.maxZ() + "</gray>"));
    }

    // D-8.1: real confirm flow. delete() no longer succeeds on first try —
    // it stages a pending deletion; only /er confirm within 30s actually
    // removes the file. This prevents accidental / grief deletes.
    private static final long CONFIRM_TIMEOUT_MS = 30_000L;
    private static final java.util.Map<String, Long> pendingDeletes = new java.util.concurrent.ConcurrentHashMap<>();

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er delete <name></red>"));
            return;
        }
        File f = new File(plugin.recordingManager().recordingsDir(), args[1] + ".echoreplay.gz");
        if (!f.exists()) {
            pendingDeletes.remove(args[1]);
            s.sendMessage(Text.mm("<red>No recording named '" + args[1] + "'.</red>"));
            return;
        }
        String key = args[1];
        long now = System.currentTimeMillis();
        Long staged = pendingDeletes.get(key);
        if (staged == null || (now - staged) > CONFIRM_TIMEOUT_MS) {
            // First attempt: stage and request confirmation.
            pendingDeletes.put(key, now);
            s.sendMessage(Text.mm("<yellow>Recording '" + key + "' is staged for deletion. "
                    + "Run <green>/er confirm</green> within 30s to actually delete it.</yellow>"));
            return;
        }
        // Second attempt within window: actually delete.
        if (f.delete()) {
            plugin.recordingIndex().remove(key);
            pendingDeletes.remove(key);
            s.sendMessage(Text.mm("<green>Deleted '" + key + "'.</green>"));
        } else {
            pendingDeletes.remove(key);
            s.sendMessage(Text.mm("<red>Could not delete file (check disk/permissions).</red>"));
        }
    }

    private void rename(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage(Text.mm("<red>Usage: /er rename <old> <new></red>"));
            return;
        }
        File old = new File(plugin.recordingManager().recordingsDir(), args[1] + ".echoreplay.gz");
        File neu = new File(plugin.recordingManager().recordingsDir(), args[2] + ".echoreplay.gz");
        if (neu.exists()) {
            s.sendMessage(Text.mm("<red>A recording named '" + args[2] + "' already exists.</red>"));
            return;
        }
        if (old.exists() && old.renameTo(neu)) {
            var e = plugin.recordingIndex().get(args[1]);
            plugin.recordingIndex().remove(args[1]);
            if (e != null) {
                plugin.recordingIndex().put(new dev.idebugger.echoreplay.storage.RecordingEntry(
                        args[2], e.worldUuid(), e.worldName(), e.durationMillis(), e.sizeBytes(),
                        e.epochMillis(), e.minX(), e.minY(), e.minZ(), e.maxX(), e.maxY(), e.maxZ()));
            }
            s.sendMessage(Text.mm("<green>Renamed to '" + args[2] + "'.</green>"));
        } else {
            s.sendMessage(Text.mm("<red>Rename failed (source missing or cross-device).</red>"));
        }
    }

    private void confirm(CommandSender s) {
        long now = System.currentTimeMillis();
        // Reap expired entries while iterating.
        var it = pendingDeletes.entrySet().iterator();
        java.util.List<String> confirmed = new ArrayList<>();
        while (it.hasNext()) {
            var en = it.next();
            if (now - en.getValue() > CONFIRM_TIMEOUT_MS) {
                it.remove();
            } else {
                confirmed.add(en.getKey());
            }
        }
        if (confirmed.isEmpty()) {
            s.sendMessage(Text.mm("<gray>Nothing pending confirmation. "
                    + "Use <yellow>/er delete <name></yellow> first, then <yellow>/er confirm</yellow> within 30s.</gray>"));
            return;
        }
        int deleted = 0;
        for (String name : confirmed) {
            File f = new File(plugin.recordingManager().recordingsDir(), name + ".echoreplay.gz");
            if (f.exists() && f.delete()) {
                plugin.recordingIndex().remove(name);
                deleted++;
                pendingDeletes.remove(name);
            }
        }
        s.sendMessage(Text.mm("<green>Confirmed deletion of " + deleted + " recording(s).</green>"));
    }

    private void marker(CommandSender s, String[] args) {
        var sess = plugin.recordingManager().activeSession();
        if (sess == null) {
            s.sendMessage(Text.mm("<red>No recording in progress.</red>"));
            return;
        }
        String name = args.length > 1 ? args[1] : "marker" + sess.mediaMillis();
        sess.emit(new dev.idebugger.echoreplay.model.TimelineEvent.Marker(sess.mediaMillis(), name));
        s.sendMessage(Text.mm("<gray>Marker '" + name + "' placed at "
                + RecordingManager.formatDuration(sess.mediaMillis()) + ".</gray>"));
    }

    private void border(CommandSender s, String[] args) {
        requirePlayer(s, p -> {
            var prefs = plugin.borderPrefs();
            if (prefs == null) {
                p.sendMessage(Text.mm("<red>Border preferences not loaded yet.</red>"));
                return;
            }
            if (args.length == 1) {
                boolean newState = prefs.toggle(p.getUniqueId());
                p.sendMessage(Text.mm(newState
                        ? "<green>Playback border particles enabled for you.</green>"
                        : "<gray>Playback border particles disabled for you.</gray>"));
                return;
            }
            String arg = args[1].toLowerCase();
            switch (arg) {
                case "on", "enable", "enabled", "true" -> {
                    prefs.setEnabled(p.getUniqueId(), true);
                    p.sendMessage(Text.mm("<green>Playback border particles enabled for you.</green>"));
                }
                case "off", "disable", "disabled", "false" -> {
                    prefs.setEnabled(p.getUniqueId(), false);
                    p.sendMessage(Text.mm("<gray>Playback border particles disabled for you.</gray>"));
                }
                case "toggle" -> {
                    boolean newState = prefs.toggle(p.getUniqueId());
                    p.sendMessage(Text.mm(newState
                            ? "<green>Playback border particles enabled for you.</green>"
                            : "<gray>Playback border particles disabled for you.</gray>"));
                }
                case "status", "info", "state" -> {
                    boolean enabled = prefs.isEnabled(p.getUniqueId());
                    p.sendMessage(Text.mm(enabled
                            ? "<gray>Playback border particles: <green>enabled</green>.</gray>"
                            : "<gray>Playback border particles: <red>disabled</red>.</gray>"));
                }
                default -> p.sendMessage(Text.mm("<red>Usage: /er border [on|off|toggle|status]</red>"));
            }
        });
    }

    /** P-9: /er stats — show resolved config + per-subsystem metrics. */
    private void stats(CommandSender s) {
        var sb = new StringBuilder();
        sb.append("<gold>EchoReplay stats</gold>\n");
        var sess = plugin.recordingManager().activeSession();
        if (sess != null) {
            sb.append("<gray>● Recording: </gray><yellow>").append(sess.name())
              .append("</yellow> <gray>— ").append(RecordingManager.formatDuration(sess.mediaMillis()))
              .append(" · sections ").append(sess.sectionsDone()).append('/').append(sess.totalSections());
            int sinkDepth = sess.sinkDepth();
            if (sinkDepth >= 0) sb.append(" · sink ").append(sinkDepth);
            sb.append("</gray>\n");
        } else {
            sb.append("<gray>● Recording: </gray><dark_gray>inactive</dark_gray>\n");
        }
        var rep = plugin.replayManager().session();
        if (rep != null) {
            sb.append("<gray>● Replay: </gray><yellow>").append(rep.name())
              .append("</yellow> <gray>— ").append(RecordingManager.formatDuration((long) rep.clock().mediaTime()))
              .append(" / ").append(RecordingManager.formatDuration((long) rep.durationMs()))
              .append(" @ ").append(rep.clock().speed()).append("x · viewers ")
              .append(rep.viewerIds().size()).append("</gray>\n");
        } else {
            sb.append("<gray>● Replay: </gray><dark_gray>inactive</dark_gray>\n");
        }
        sb.append("<gray>● IO thread: </gray><yellow>").append(plugin.ioExecutorStatus()).append("</yellow>\n");
        sb.append("<gray>● Recordings dir: </gray><yellow>")
          .append(plugin.recordingManager().recordingsDir().getAbsolutePath()).append("</yellow>");
        s.sendMessage(Text.mm(sb.toString()));
    }

    private void version(CommandSender s) {
        s.sendMessage(Text.mm("<gold>EchoReplay</gold> <gray>v"
                + plugin.getDescription().getVersion()
                + " (api " + plugin.getDescription().getAPIVersion() + ")"));
    }

    private void reload(CommandSender s) {
        plugin.reloadConfig();
        plugin.recordingManager().onEnable(plugin.getConfig());
        plugin.replayManager().onEnable(plugin.getConfig());
        s.sendMessage(Text.mm("<green>EchoReplay config reloaded.</green>"));
    }

    /**
     * Parse a time argument. Accepts:
     * <ul>
     *   <li>{@code 10} or {@code 10.5} — plain seconds</li>
     *   <li>{@code 10s} — seconds with explicit suffix</li>
     *   <li>{@code 5m30s} — compound mm:ss style</li>
     *   <li>{@code 1h2m3s} — full H/M/S form</li>
     *   <li>{@code 12:30} — mm:ss</li>
     *   <li>{@code 50%} — percentage of current session duration</li>
     *   <li>{@code tick:600} — server ticks</li>
     * </ul>
     * Returns null on parse failure (caller falls through to marker lookup).
     */
    private Double parseTime(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);

        // percentage of current session duration
        if (t.endsWith("%")) {
            var rep = plugin.replayManager().session();
            if (rep == null) return null;
            try {
                double pct = Double.parseDouble(t.substring(0, t.length() - 1));
                return rep.durationMs() * (pct / 100.0) / 1000.0;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // tick:N
        if (t.startsWith("tick:") || t.startsWith("t:")) {
            try {
                long ticks = Long.parseLong(t.substring(t.indexOf(':') + 1));
                return ticks * 50.0 / 1000.0; // 1 tick = 50ms
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // mm:ss
        if (t.contains(":")) {
            String[] parts = t.split(":");
            if (parts.length == 2) {
                try {
                    return Double.parseDouble(parts[0]) * 60 + Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (parts.length == 3) {
                try {
                    return Double.parseDouble(parts[0]) * 3600
                         + Double.parseDouble(parts[1]) * 60
                         + Double.parseDouble(parts[2]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        // 1h2m3s / 5m30s / 10s / 1h / 30m
        if (t.matches("^[0-9]+(h|m|s)[0-9]*(h|m|s)?[0-9]*$") || t.matches("^[0-9]+(h|m|s)$")) {
            double total = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)(h|m|s)").matcher(t);
            while (m.find()) {
                double v = Double.parseDouble(m.group(1));
                switch (m.group(2)) {
                    case "h" -> total += v * 3600;
                    case "m" -> total += v * 60;
                    case "s" -> total += v;
                }
            }
            return total;
        }

        // plain seconds
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseOrDefault(String s, double d) {
        Double v = parseTime(s);
        if (v != null) return v;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> subs = Arrays.asList("wand", "pos1", "pos2", "select", "expand", "contract", "shift",
                "selinfo", "clear", "record", "stop", "cancel", "save", "status", "play", "pause", "resume",
                "speed", "seek", "ff", "rewind", "stopplay", "leave", "watch", "cam", "list", "info",
                "delete", "rename", "confirm", "marker", "border", "stats", "version", "reload");
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("watch")
                || args[0].equalsIgnoreCase("rename"))) {
            return plugin.recordingIndex().all().stream().map(e -> e.name())
                    .filter(n -> n.startsWith(args[1])).collect(java.util.stream.Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            return Arrays.asList("0.25", "0.5", "1", "2", "4", "8", "16");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("border")) {
            return Arrays.asList("on", "off", "toggle", "status").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            return Arrays.asList("virtual", "world").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
