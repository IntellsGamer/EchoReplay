package dev.idebugger.echoreplay.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Central Adventure/MiniMessage helpers. No ChatColor anywhere in the plugin.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    public static void broadcast(Component c) {
        org.bukkit.Bukkit.getServer().broadcast(c);
    }
}
