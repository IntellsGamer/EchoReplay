package com.echoreplay.record;

import com.echoreplay.EchoReplayPlugin;
import com.echoreplay.model.TimelineEvent;
import com.echoreplay.select.Cuboid;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Captures chat messages from players in the recording region. Uses Paper's
 * AsyncChatEvent (not the deprecated Bukkit PlayerChatEvent).
 */
public final class ChatRecorder implements Listener {

    private final EchoReplayPlugin plugin;

    public ChatRecorder(EchoReplayPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (!plugin.cfg().getBoolean("recording.capture-chat", true)) return;
        Player p = e.getPlayer();
        if (!p.getWorld().getUID().equals(s.world().getUID())) return;
        Cuboid c = s.cuboid();
        if (!c.contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) return;
        int npc = s.npcIdFor(p.getUniqueId());
        String json;
        try {
            json = GsonComponentSerializer.gson().serialize(e.message());
        } catch (Exception ex) {
            json = "\"\"";
        }
        s.emit(new TimelineEvent.Chat(s.mediaMillis(), npc, json));
    }
}
