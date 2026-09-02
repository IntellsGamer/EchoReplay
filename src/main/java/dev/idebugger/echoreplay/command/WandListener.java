package dev.idebugger.echoreplay.command;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.select.Selection;
import dev.idebugger.echoreplay.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Handles the selection wand: left=pos1, right=pos2. Cancels native use so a
 * golden axe won't strip logs etc.
 */
public final class WandListener implements Listener {

    private static final NamespacedKey WAND_KEY = NamespacedKey.fromString("echoreplay:wand");

    private final EchoReplay plugin;

    public WandListener(EchoReplay plugin) {
        this.plugin = plugin;
    }

    private boolean isWand(Player p) {
        var item = p.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return false;
        if (item.getItemMeta() == null) return false;
        return item.getItemMeta().getPersistentDataContainer().has(WAND_KEY, PersistentDataType.INTEGER);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("echoreplay.wand")) return;
        if (!isWand(p)) return;

        Block target = e.getClickedBlock();
        if (target == null) return;
        e.setCancelled(true);

        Selection sel = plugin.selectionManager().get(p);
        BlockPos pos = new BlockPos(target.getX(), target.getY(), target.getZ());
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            sel.setPos1(pos);
            p.sendMessage(Text.mm("<gray>Pos1 set to (" + pos.x() + ", " + pos.y() + ", " + pos.z() + ").</gray>"));
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            sel.setPos2(pos);
            p.sendMessage(Text.mm("<gray>Pos2 set to (" + pos.x() + ", " + pos.y() + ", " + pos.z() + ").</gray>"));
        }
    }
}
