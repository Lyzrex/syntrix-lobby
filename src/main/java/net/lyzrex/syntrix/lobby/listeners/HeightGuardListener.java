package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public final class HeightGuardListener implements Listener {

    private final SyntrixLobby plugin;
    private final MessageService ms;

    private final Set<UUID> deathByHeight = new HashSet<>();

    public HeightGuardListener(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.ms = new MessageService(plugin);
    }

    private double cfgHeight() {
        return plugin.getConfig().getDouble("safety.deathHeight", Double.NEGATIVE_INFINITY);
    }

    private Location lobbySpawn() {
        String worldName = plugin.getConfig().getString("lobby.world", "world");
        World w = Bukkit.getWorld(worldName);
        if (w == null) {

            w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (w == null) throw new IllegalStateException("No worlds loaded.");
        }

        double x = plugin.getConfig().getDouble("lobby.spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("lobby.spawn.y", 80.0);
        double z = plugin.getConfig().getDouble("lobby.spawn.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("lobby.spawn.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("lobby.spawn.pitch", 0.0);
        return new Location(w, x, y, z, yaw, pitch);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        double h = cfgHeight();
        if (h == Double.NEGATIVE_INFINITY) return;

        if (e.getTo() != null && e.getTo().getY() <= h && p.getHealth() > 0.0) {
            deathByHeight.add(p.getUniqueId());
            p.setHealth(0.0);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!deathByHeight.contains(p.getUniqueId())) return;

        e.deathMessage(null);
        ms.send(p, plugin.messages().getString(
                "sethight.killed",
                "<gray>You fell below the allowed height.</gray>"
        ));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!deathByHeight.remove(p.getUniqueId())) return;

        e.setRespawnLocation(lobbySpawn());
        ms.send(p, plugin.messages().getString(
                "sethight.respawn",
                "<gray>You have been teleported to the lobby spawn.</gray>"
        ));
    }
}
