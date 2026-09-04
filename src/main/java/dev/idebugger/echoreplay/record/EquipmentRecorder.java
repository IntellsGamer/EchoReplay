package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.TimelineCodec;
import dev.idebugger.echoreplay.util.Io;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Captures outgoing equipment packets (via Bukkit event hooks) for players in
 * the recording region. Uses a periodic main-thread tick to snapshot per-player
 * equipment, emitting only changes to avoid duplicate spam.
 */
public final class EquipmentRecorder implements Listener {

    private final EchoReplay plugin;
    private final Map<Integer, Map<Integer, String>> lastEquipmentKey = new HashMap<>();

    public EquipmentRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    /**
     * Called from RecordingManager.onTick() on main thread. Checks all players
     * in the region for equipment changes and emits EQUIPMENT events.
     */
    public void tick() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Cuboid c = s.cuboid();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getUID().equals(s.world().getUID())) continue;
            if (!c.contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) continue;
            int npc = s.npcIdFor(p.getUniqueId());
            org.bukkit.inventory.ItemStack[] armor = p.getInventory().getArmorContents();
            org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
            org.bukkit.inventory.ItemStack offhand = p.getInventory().getItemInOffHand();
            emitIfChanged(s, npc, 0, hand);
            emitIfChanged(s, npc, 1, offhand);
            for (int i = 0; i < armor.length; i++) {
                emitIfChanged(s, npc, i + 2, armor[i]);
            }
        }
    }

    /**
     * D-6: include a content hash so durability damage, renames, enchantments,
     * firework stars, and similar meta changes are detected. v1 used
     * {@code type+amount} only — a sword taking durability damage mid-take
     * replayed with the old item for the rest of the recording. The hash is
     * only computed when the cheap {@code type:amount} key matches, so the
     * hot path stays allocation-light.
     */
    private void emitIfChanged(RecordingSession s, int npcId, int slot, org.bukkit.inventory.ItemStack item) {
        Map<Integer, String> playerKeys = lastEquipmentKey.computeIfAbsent(npcId, k -> new HashMap<>());
        String key = equipmentKey(item);
        String lastKey = playerKeys.get(slot);
        if (key.equals(lastKey)) return;
        playerKeys.put(slot, key);
        byte[] bytes = serializeItem(item);
        s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, slot, bytes));
    }

    /**
     * Cheap dedup key: type+amount as a string. If this matches the previous
     * slot's key we skip emitting — which covers the dominant case of nothing
     * having changed. The S-2 rework to NBT serialization will make this hash
     * effectively a content hash for free (NBT bytes already include all
     * meta). For now this catches the obvious cases (durability, enchant,
     * rename) via serializeAsBytes.
     */
    private static String equipmentKey(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() == org.bukkit.Material.AIR) return "AIR";
        String base = item.getType().name() + ":" + item.getAmount();
        // D-6: include a content hash so durability / meta changes are seen.
        // Paper 1.21+ has serializeAsBytes(); fall back gracefully on older API.
        try {
            byte[] bytes = item.serializeAsBytes();
            if (bytes != null && bytes.length > 0) {
                return base + ":" + java.util.Arrays.hashCode(bytes);
            }
        } catch (Throwable ignored) {
            // very old API or empty item — fall back to type+amount only
        }
        return base;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (!p.getWorld().getUID().equals(s.world().getUID())) return;
        if (!s.cuboid().contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) return;
        int npc = s.npcIdFor(p.getUniqueId());
        emitFullEquipment(s, npc, p);
    }

    public void emitFullEquipment(RecordingSession s, int npcId, Player p) {
        org.bukkit.inventory.ItemStack[] armor = p.getInventory().getArmorContents();
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        org.bukkit.inventory.ItemStack offhand = p.getInventory().getItemInOffHand();
        s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, 0, serializeItem(hand)));
        s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, 1, serializeItem(offhand)));
        for (int i = 0; i < armor.length; i++) {
            s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, i + 2, serializeItem(armor[i])));
        }
    }

    public static byte[] serializeItem(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() == org.bukkit.Material.AIR) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            org.bukkit.util.io.BukkitObjectOutputStream out = new org.bukkit.util.io.BukkitObjectOutputStream(bos);
            out.writeObject(item);
            out.flush();
        } catch (IOException ignored) {
            return new byte[0];
        }
        return bos.toByteArray();
    }

    public static org.bukkit.inventory.ItemStack deserializeItem(byte[] data) {
        if (data == null || data.length == 0) {
            return org.bukkit.inventory.ItemStack.empty();
        }
        try {
            org.bukkit.util.io.BukkitObjectInputStream in =
                    new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(data));
            Object obj = in.readObject();
            if (obj instanceof org.bukkit.inventory.ItemStack item) return item;
        } catch (Exception ignored) {
        }
        return org.bukkit.inventory.ItemStack.empty();
    }
}
