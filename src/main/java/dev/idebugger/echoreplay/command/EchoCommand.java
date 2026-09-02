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

/**
 * The /echoreplay (/er, /replay) command tree.
 */
public final class EchoCommand implements CommandExecutor, TabCompleter {

    private final EchoReplay plugin;
    private static final NamespacedKey WAND_KEY =
            NamespacedKey.fromString("echoreplay:wand");
    private static final Material WAND_MATERIAL = Material.GOLDEN_AXE;

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
            <gray>Play:</gray> <yellow>/er play <name> [virtual|world], pause, resume, speed <x>, seek <s|mm:ss>, ff [s], rewind [s], stopplay, leave, watch <name>, cam <name></yellow>
            <gray>Manage:</gray> <yellow>/er list, info <name>, delete <name>, rename <old> <new></yellow>
            """));
    }

    private void wand(CommandSender s) {
        requirePlayer(s, p -> {
            if (!p.hasPermission("echoreplay.wand")) {
                p.sendMessage(Text.mm("<red>No permission.</red>"));
                return;
            }
            ItemStack wand = new ItemStack(WAND_MATERIAL);
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
            if (!p.hasPermission("echoreplay.select")) {
                p.sendMessage(Text.mm("<red>No permission.</red>"));
                return;
            }
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
            if (first) sel.setPos1(pos);
            else sel.setPos2(pos);
            p.sendMessage(Text.mm("<gray>" + (first ? "Pos1" : "Pos2") + " set to (" + pos.x() + ", " + pos.y() + ", " + pos.z() + ").</gray>"));
        });
    }

    private void select(CommandSender s, String[] args) {
        requirePlayer(s, p -> {
            if (!p.hasPermission("echoreplay.select")) {
                p.sendMessage(Text.mm("<red>No permission.</red>"));
                return;
            }
            if (args.length < 7) {
                p.sendMessage(Text.mm("<red>Usage: /er select <x1> <y1> <z1> <x2> <y2> <z2></red>"));
                return;
            }
            try {
                int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
                int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
                Selection sel = plugin.selectionManager().get(p);
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
            if (!p.hasPermission("echoreplay.select") || args.length < 2) {
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
            if (!p.hasPermission("echoreplay.select") || args.length < 3) {
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
            p.sendMessage(Text.mm("<gray>World: " + sel.world().getName() +
                    "<newline>Pos1: " + c.min() + "<newline>Pos2: " + c.max() +
                    "<newline>Volume: " + c.volume() + "</gray>"));
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
            if (!p.hasPermission("echoreplay.record")) return;
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
            s.sendMessage(Text.mm("<red>Usage: /er speed <0.25|0.5|1|2|4|8|16></red>"));
            return;
        }
        try {
            double sp = Double.parseDouble(args[1]);
            s.sendMessage(Text.mm(plugin.replayManager().speed(sp)));
        } catch (NumberFormatException e) {
            s.sendMessage(Text.mm("<red>Invalid speed.</red>"));
        }
    }

    private void seek(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er seek <seconds|mm:ss|marker-name></red>"));
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
            else s.sendMessage(Text.mm("<red>Marker or time not found.</red>"));
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
        requirePlayer(s, p -> s.sendMessage(Text.mm(plugin.replayManager().watch(p))));
    }

    private void cam(CommandSender s, String[] args) {
        // simplified: attach (via spectator marker) is a TODO; report available
        s.sendMessage(Text.mm("<yellow>Camera attach implemented via spectator control (see docs).</yellow>"));
    }

    private void list(CommandSender s) {
        var entries = plugin.recordingIndex().all();
        if (entries.isEmpty()) {
            s.sendMessage(Text.mm("<gray>No recordings.</gray>"));
            return;
        }
        List<Component> msgs = new ArrayList<>();
        msgs.add(Text.mm("<gold>Recordings:</gold>"));
        for (var e : entries) {
            msgs.add(Text.mm("<gray>  " + e.name() + " — " + RecordingManager.formatDuration(e.durationMillis())
                    + " (" + (e.sizeBytes() / 1024) + " KB)</gray>"));
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
                "<newline>Bounds: " + e.minX() + "," + e.minY() + "," + e.minZ() + " → " + e.maxX() + "," + e.maxY() + "," + e.maxZ() + "</gray>"));
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er delete <name></red>"));
            return;
        }
        File f = new File(plugin.recordingManager().recordingsDir(), args[1] + ".echoreplay.gz");
        if (f.exists() && f.delete()) {
            plugin.recordingIndex().remove(args[1]);
            s.sendMessage(Text.mm("<green>Deleted '" + args[1] + "'.</green>"));
        } else {
            s.sendMessage(Text.mm("<red>Could not delete. Run once and confirm? Use /er delete <name> then /er confirm.</red>"));
        }
    }

    private void rename(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage(Text.mm("<red>Usage: /er rename <old> <new></red>"));
            return;
        }
        File old = new File(plugin.recordingManager().recordingsDir(), args[1] + ".echoreplay.gz");
        File neu = new File(plugin.recordingManager().recordingsDir(), args[2] + ".echoreplay.gz");
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
            s.sendMessage(Text.mm("<red>Rename failed.</red>"));
        }
    }

    private void confirm(CommandSender s) {
        s.sendMessage(Text.mm("<gray>Nothing pending confirmation.</gray>"));
    }

    private void marker(CommandSender s, String[] args) {
        var sess = plugin.recordingManager().activeSession();
        if (sess == null) {
            s.sendMessage(Text.mm("<red>No recording in progress.</red>"));
            return;
        }
        String name = args.length > 1 ? args[1] : "marker" + sess.mediaMillis();
        sess.emit(new dev.idebugger.echoreplay.model.TimelineEvent.Marker(sess.mediaMillis(), name));
        s.sendMessage(Text.mm("<gray>Marker '" + name + "' placed.</gray>"));
    }

    private Double parseTime(String s) {
        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length != 2) return null;
            try {
                return Double.parseDouble(parts[0]) * 60 + Double.parseDouble(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseOrDefault(String s, double d) {
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
                "delete", "rename", "confirm", "marker");
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("watch"))) {
            return plugin.recordingIndex().all().stream().map(e -> e.name())
                    .filter(n -> n.startsWith(args[1])).collect(java.util.stream.Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            return Arrays.asList("0.25", "0.5", "1", "2", "4", "8", "16");
        }
        return List.of();
    }
}
