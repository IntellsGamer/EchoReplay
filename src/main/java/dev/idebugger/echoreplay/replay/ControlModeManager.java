package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visible VCR controls: replaces the player's hotbar (slots 0-8) with
 * right-clickable control items (pause, resume, stop, rewind, fast-forward,
 * speed, restart, leave, help).
 *
 * <p>Modal and locked: while enabled the hotbar cannot be rearranged or
 * dropped (inventory clicks/drags and control-item drops are cancelled, death
 * drops are filtered). The previous hotbar is saved and restored when control
 * mode ends: explicit off, playback stop/end, replay leave, quit, respawn
 * handling, or server shutdown.
 */
public final class ControlModeManager implements Listener {

    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_REWIND = "rewind";
    public static final String ACTION_FF = "ff";
    public static final String ACTION_SPEED = "speed";
    public static final String ACTION_RESTART = "restart";
    public static final String ACTION_LEAVE = "leave";
    public static final String ACTION_STATUS = "status";

    private static final double[] SPEEDS = {0.5, 1.0, 2.0, 4.0, 8.0};

    private final EchoReplay plugin;
    private final ReplayManager replays;
    private final NamespacedKey controlKey;

    private record SavedHotbar(ItemStack[] hotbar, int heldSlot) {}

    private final Map<UUID, SavedHotbar> saved = new HashMap<>();

    public ControlModeManager(EchoReplay plugin, ReplayManager replays) {
        this.plugin = plugin;
        this.replays = replays;
        this.controlKey = new NamespacedKey(plugin, "control");
    }

    public boolean isEnabled(Player p) {
        return p != null && saved.containsKey(p.getUniqueId());
    }

    public boolean hasAny() {
        return !saved.isEmpty();
    }

    /** Per-tick safety net: no session left means no controls. */
    public void tick() {
        if (!saved.isEmpty() && replays.session() == null) clearAll();
    }

    public String toggle(Player p) {
        return isEnabled(p) ? setEnabled(p, false) : setEnabled(p, true);
    }

    /**
     * @return MiniMessage result for the command sender.
     */
    public String setEnabled(Player p, boolean on) {
        if (p == null) return "<red>No player.</red>";
        if (on) {
            if (isEnabled(p)) return "<gray>Controls already on.</gray>";
            if (replays.session() == null) return "<red>No replay playing.</red>";
            ItemStack[] hot = new ItemStack[9];
            for (int i = 0; i < 9; i++) {
                try {
                    ItemStack it = p.getInventory().getItem(i);
                    hot[i] = it == null ? null : it.clone();
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: control save failed slot " + i, e);
                    hot[i] = null;
                }
            }
            int held = 0;
            try {
                held = p.getInventory().getHeldItemSlot();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: control save held slot failed", e);
            }
            saved.put(p.getUniqueId(), new SavedHotbar(hot, held));
            giveControls(p);
            return "<green>Controls on — right-click the hotbar items. <gray>/er control off to restore.</gray>";
        } else {
            if (!isEnabled(p)) return "<gray>Controls already off.</gray>";
            restore(p);
            return "<gray>Controls off — hotbar restored.</gray>";
        }
    }

    public String statusText(Player p) {
        StringBuilder sb = new StringBuilder("<gold>Controls: ")
                .append(isEnabled(p) ? "<green>ON</green>" : "<gray>OFF</gray>")
                .append("</gold><newline><gray>0 Pause | 1 Resume | 2 Stop | 3 -10s | 4 +10s")
                .append("<newline>5 Speed | 6 Restart | 7 Leave | 8 Help")
                .append("<newline>Hotbar is locked while on; stopping playback, leaving,")
                .append(" quitting or server stop restores it.</gray>");
        return sb.toString();
    }

    /** Restore everyone (playback stop/end, server shutdown). */
    public void clearAll() {
        if (saved.isEmpty()) return;
        for (UUID id : new ArrayList<>(saved.keySet())) {
            try {
                Player p = org.bukkit.Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    restore(p);
                } else {
                    saved.remove(id);
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: control clearAll failed", e);
                saved.remove(id);
            }
        }
    }

    /** Silent disable for one player (leave/quit paths). */
    public void disable(Player p) {
        if (p == null) return;
        if (!saved.containsKey(p.getUniqueId())) return;
        restore(p);
    }

    private void restore(Player p) {
        SavedHotbar s = saved.remove(p.getUniqueId());
        if (s == null) return;
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack it = s.hotbar()[i];
                p.getInventory().setItem(i, it == null ? null : it.clone());
            }
            int held = Math.max(0, Math.min(8, s.heldSlot()));
            p.getInventory().setHeldItemSlot(held);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control restore failed for " + p.getName(), e);
        }
    }

    private void giveControls(Player p) {
        p.getInventory().setItem(0, controlItem(Material.REDSTONE_BLOCK, ACTION_PAUSE,
                "<red>Pause</red>", "Right-click: pause playback"));
        p.getInventory().setItem(1, controlItem(Material.EMERALD_BLOCK, ACTION_RESUME,
                "<green>Resume</green>", "Right-click: resume playback"));
        p.getInventory().setItem(2, controlItem(Material.BARRIER, ACTION_STOP,
                "<red>Stop</red>", "Right-click: stop playback"));
        p.getInventory().setItem(3, controlItem(Material.ARROW, ACTION_REWIND,
                "<yellow>Rewind 10s</yellow>", "Right-click: back 10 seconds"));
        p.getInventory().setItem(4, controlItem(Material.SPECTRAL_ARROW, ACTION_FF,
                "<yellow>Forward 10s</yellow>", "Right-click: forward 10 seconds"));
        p.getInventory().setItem(5, controlItem(Material.SUGAR, ACTION_SPEED,
                "<aqua>Speed</aqua>", "Right-click: cycle 0.5-1-2-4-8x"));
        p.getInventory().setItem(6, controlItem(Material.RECOVERY_COMPASS, ACTION_RESTART,
                "<gold>Restart</gold>", "Right-click: seek to start"));
        p.getInventory().setItem(7, controlItem(Material.OAK_DOOR, ACTION_LEAVE,
                "<gray>Leave</gray>", "Right-click: leave the replay"));
        p.getInventory().setItem(8, controlItem(Material.BOOK, ACTION_STATUS,
                "<white>Help</white>", "Right-click: show controls"));
    }

    /** Re-apply controls without re-saving (respawn path). */
    private void ensureControls(Player p) {
        if (!isEnabled(p)) return;
        try {
            ItemStack cur = p.getInventory().getItem(0);
            if (isControlItem(cur)) return;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control ensure check failed", e);
        }
        giveControls(p);
    }

    private ItemStack controlItem(Material mat, String action, String nameMm, String loreMm) {
        ItemStack it = new ItemStack(mat);
        it.setAmount(1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm(nameMm));
            meta.lore(List.of(Text.mm("<gray>" + loreMm + "</gray>")));
            meta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, action);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean isControlItem(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return false;
            String v = meta.getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
            return v != null && !v.isEmpty();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control tag check failed", e);
            return false;
        }
    }

    private String actionOf(ItemStack it) {
        if (it == null) return null;
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return null;
            return meta.getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control action read failed", e);
            return null;
        }
    }

    private void runAction(Player p, String action) {
        if (action == null) return;
        try {
            switch (action) {
                case ACTION_PAUSE -> p.sendMessage(Text.mm(replays.pause()));
                case ACTION_RESUME -> p.sendMessage(Text.mm(replays.resume()));
                case ACTION_STOP -> {
                    p.sendMessage(Text.mm(replays.stopPlay(false)));
                    disable(p);
                }
                case ACTION_REWIND -> p.sendMessage(Text.mm(replays.rewind(10)));
                case ACTION_FF -> p.sendMessage(Text.mm(replays.forward(10)));
                case ACTION_SPEED -> {
                    ReplaySession s = replays.session();
                    double cur = s != null ? s.clock().speed() : 1.0;
                    double next = SPEEDS[0];
                    for (double v : SPEEDS) {
                        if (v > cur + 1e-9) {
                            next = v;
                            break;
                        }
                    }
                    p.sendMessage(Text.mm(replays.speed(next)));
                }
                case ACTION_RESTART -> {
                    ReplaySession s = replays.session();
                    if (s == null) {
                        p.sendMessage(Text.mm("<red>No replay playing.</red>"));
                    } else {
                        s.seekTo(0);
                        p.sendMessage(Text.mm("<gray>Seeked to start.</gray>"));
                    }
                }
                case ACTION_LEAVE -> {
                    p.sendMessage(Text.mm(replays.leave(p)));
                    disable(p);
                }
                case ACTION_STATUS -> p.sendMessage(Text.mm(statusText(p)));
                default -> { /* unknown tag: ignore */ }
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control action failed " + action, e);
        }
    }

    // ---- events: locked hotbar ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isEnabled(p)) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK
                && a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;
        String action = actionOf(e.getItem());
        if (action == null) {
            // Holding a non-control item while flagged should not happen
            // (hotbar is locked), but never hijack foreign items.
            return;
        }
        e.setCancelled(true);
        runAction(p, action);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isEnabled(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isEnabled(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isControlItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (isEnabled(p) || isControlItem(e.getMainHandItem()) || isControlItem(e.getOffHandItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        // Controls must never scatter on the ground; saved hotbar stays stored
        // and is restored on exit/respawn, so no loss and no duplication.
        try {
            e.getDrops().removeIf(this::isControlItem);
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control death filter failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!saved.containsKey(p.getUniqueId())) return;
        if (replays.session() == null) {
            disable(p);
            return;
        }
        // Inventory is empty/cleared after a non-keep death: re-apply controls
        // without touching the saved hotbar.
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> ensureControls(p));
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control respawn failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // Player object is still usable during quit for inventory restore
        // (teleport is a no-op then, same as the spectate path).
        disable(e.getPlayer());
    }
}
