package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.select.Cuboid;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Client-side wireframe particles around the playback cuboid.
 * Uses per-player {@code Player#spawnParticle} which sends a single
 * {@code WrapperPlayServerWorldParticles} packet to that viewer only —
 * pure protocol trick, no world change or server-wide broadcast.
 * <p>
 * The border is rendered as a 12-edge wireframe cube enclosing the region.
 * It is throttled by {@code replay.border.interval-ticks} and adaptive
 * stepping so large regions don't spam packets.
 */
public final class PlaybackBorderRenderer {

    private PlaybackBorderRenderer() {}

    public static void tick(ReplaySession session) {
        EchoReplay plugin = EchoReplay.get();
        if (plugin == null || session == null || !session.started()) return;
        if (!plugin.cfg().getBoolean("replay.border.enabled", true)) return;

        int interval = plugin.cfg().getInt("replay.border.interval-ticks", 4);
        if (interval <= 0) interval = 4;
        // Session owns the counter so each playback throttles independently.
        if (!session.shouldRenderBorder(interval)) return;

        Cuboid c = session.cuboid();
        World w = session.world();
        if (c == null || w == null) return;

        String name = plugin.cfg().getString("replay.border.particle", "END_ROD");
        Particle particle;
        try {
            particle = Particle.valueOf(name);
        } catch (Exception ex) {
            particle = Particle.END_ROD;
        }
        // Dust needs extra data; fall back if someone configured DUST without color
        Object dustData = null;
        if (particle == Particle.DUST) {
            try {
                dustData = new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 255, 255), 1.0f);
            } catch (Exception ignored) {
                particle = Particle.END_ROD;
            }
        }

        double cfgStep = plugin.cfg().getDouble("replay.border.step", 2.0);
        if (cfgStep < 0.5) cfgStep = 0.5;
        int maxPerFrame = plugin.cfg().getInt("replay.border.max-per-frame", 300);
        if (maxPerFrame <= 0) maxPerFrame = 300;

        // Adaptive step to cap packets for huge cuboids.
        int xSize = c.xSize();
        int ySize = c.ySize();
        int zSize = c.zSize();
        double totalLen = 4.0 * (xSize + ySize + zSize);
        double est = totalLen / cfgStep;
        double step = cfgStep;
        if (est > maxPerFrame) {
            step = totalLen / maxPerFrame;
        }

        PlaybackBorderPrefs prefs = plugin.borderPrefs();
        List<Player> viewers = session.liveViewersPublic();
        for (Player p : viewers) {
            if (p == null || !p.isOnline()) continue;
            if (prefs != null && !prefs.isEnabled(p.getUniqueId())) continue;
            // Only show if in same world; liveViewers already filters but double-check
            if (!p.getWorld().getUID().equals(w.getUID())) continue;
            // Distance cull: skip if player is very far (> 128 + max dimension)
            double cx = (c.min().x() + c.max().x()) / 2.0;
            double cy = (c.min().y() + c.max().y()) / 2.0;
            double cz = (c.min().z() + c.max().z()) / 2.0;
            var ploc = p.getLocation();
            double dx = ploc.getX() - cx;
            double dy = ploc.getY() - cy;
            double dz = ploc.getZ() - cz;
            double distSq = dx*dx + dy*dy + dz*dz;
            double maxDist = 128 + Math.max(xSize, Math.max(ySize, zSize));
            if (distSq > maxDist * maxDist) continue;
            renderFor(p, c, particle, dustData, step);
        }
    }

    private static void renderFor(Player p, Cuboid c, Particle particle, Object dustData, double step) {
        double x1 = c.min().x();
        double y1 = c.min().y();
        double z1 = c.min().z();
        double x2 = c.max().x() + 1;
        double y2 = c.max().y() + 1;
        double z2 = c.max().z() + 1;

        // Bottom square (y = y1)
        line(p, particle, dustData, x1, y1, z1, x2, y1, z1, step);
        line(p, particle, dustData, x2, y1, z1, x2, y1, z2, step);
        line(p, particle, dustData, x2, y1, z2, x1, y1, z2, step);
        line(p, particle, dustData, x1, y1, z2, x1, y1, z1, step);
        // Top square (y = y2)
        line(p, particle, dustData, x1, y2, z1, x2, y2, z1, step);
        line(p, particle, dustData, x2, y2, z1, x2, y2, z2, step);
        line(p, particle, dustData, x2, y2, z2, x1, y2, z2, step);
        line(p, particle, dustData, x1, y2, z2, x1, y2, z1, step);
        // Vertical pillars
        line(p, particle, dustData, x1, y1, z1, x1, y2, z1, step);
        line(p, particle, dustData, x2, y1, z1, x2, y2, z1, step);
        line(p, particle, dustData, x1, y1, z2, x1, y2, z2, step);
        line(p, particle, dustData, x2, y1, z2, x2, y2, z2, step);
    }

    private static void line(Player p, Particle particle, Object dustData,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double step) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len <= 0.001) {
            spawn(p, particle, dustData, x1, y1, z1);
            return;
        }
        int steps = (int) Math.ceil(len / step);
        if (steps < 1) steps = 1;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + dx * t;
            double y = y1 + dy * t;
            double z = z1 + dz * t;
            spawn(p, particle, dustData, x, y, z);
        }
    }

    private static void spawn(Player p, Particle particle, Object dustData, double x, double y, double z) {
        try {
            if (particle == Particle.DUST && dustData != null) {
                p.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, dustData);
            } else {
                p.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
            }
        } catch (Exception ignored) {}
    }
}
