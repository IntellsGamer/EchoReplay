package dev.idebugger.echoreplay.packet;

import dev.idebugger.echoreplay.EchoReplayPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

/**
 * Initializes/loads PacketEvents as an embedded library (no separate plugin).
 */
public final class PacketEventsSetup {

    private PacketEventsSetup() {}

    public static void onLoad(EchoReplayPlugin plugin) {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
        PacketEvents.getAPI()
                .getSettings()
                .reEncodeByDefault(false)
                .checkForUpdates(false)
                .bStats(false);
    }

    public static void onEnable() {
        PacketEvents.getAPI().init();
    }

    public static void onDisable() {
        PacketEvents.getAPI().terminate();
    }
}
