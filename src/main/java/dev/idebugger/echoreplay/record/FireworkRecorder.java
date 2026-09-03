package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.TimelineEvent;
import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FireworkExplodeEvent;

/**
 * Captures firework explosion as an entity status event (17) so the replay
 * can trigger the client-side burst with correct colors/balls defined by the
 * firework item. The status is sent to the fake firework entity during replay.
 */
public final class FireworkRecorder implements Listener {

    private final EchoReplay plugin;

    public FireworkRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(FireworkExplodeEvent e) {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Firework fw = e.getEntity();
        if (!fw.getWorld().getUID().equals(s.world().getUID())) return;
        if (!s.cuboid().contains(fw.getLocation().getBlockX(), fw.getLocation().getBlockY(), fw.getLocation().getBlockZ())) return;
        int npc = s.npcIdFor(fw.getUniqueId());
        // Status 17 is the vanilla firework explosion (client creates particles from the item's FireworkMeta)
        s.emit(new TimelineEvent.EntityStatus(s.mediaMillis(), npc, (byte) 17));
    }
}
