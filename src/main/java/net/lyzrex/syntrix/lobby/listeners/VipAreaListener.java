package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

public class VipAreaListener implements Listener {

    private final SyntrixLobby plugin;

    public VipAreaListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (to == null) return;

        // Prüfen, ob der Spieler im VIP-Bereich ist
        if (isInVipArea(to)) {
            // Hat der Spieler den VIP Rang?
            if (!player.hasPermission("syntrix.vip") && !player.hasPermission("syntrix.admin")) {

                Location from = event.getFrom();
                Vector knockback = from.toVector().subtract(to.toVector()).normalize().multiply(0.8).setY(0.3);

                // Verhindert Glitches, falls from und to exakt identisch sind
                if (Double.isNaN(knockback.getX()) || Double.isInfinite(knockback.getX())) {
                    knockback = player.getLocation().getDirection().multiply(-0.8).setY(0.3);
                }

                player.setVelocity(knockback);

                MessageUtil.sendActionBar(player, "<#FF4D4F>This area is only accessible to VIPs!");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
        }
    }

    private boolean isInVipArea(Location loc) {
        // Prüft, ob pos1 und pos2 überhaupt schon mit dem Command gesetzt wurden
        if (!plugin.getConfig().contains("viparea.pos1") || !plugin.getConfig().contains("viparea.pos2")) {
            return false;
        }

        String world1 = plugin.getConfig().getString("viparea.pos1.world");
        String world2 = plugin.getConfig().getString("viparea.pos2.world");

        if (loc.getWorld() == null || !loc.getWorld().getName().equals(world1) || !loc.getWorld().getName().equals(world2)) {
            return false;
        }

        double x1 = plugin.getConfig().getDouble("viparea.pos1.x");
        double y1 = plugin.getConfig().getDouble("viparea.pos1.y");
        double z1 = plugin.getConfig().getDouble("viparea.pos1.z");

        double x2 = plugin.getConfig().getDouble("viparea.pos2.x");
        double y2 = plugin.getConfig().getDouble("viparea.pos2.y");
        double z2 = plugin.getConfig().getDouble("viparea.pos2.z");

        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);

        return loc.getX() >= minX && loc.getX() <= maxX &&
                loc.getY() >= minY && loc.getY() <= maxY &&
                loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
}