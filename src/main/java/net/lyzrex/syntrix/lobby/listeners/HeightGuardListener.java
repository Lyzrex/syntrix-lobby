package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

public final class HeightGuardListener implements Listener {

    private final SyntrixLobby plugin;
    private final MessageService ms;


    public HeightGuardListener(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.ms = new MessageService(plugin);
    }

    private double cfgHeight() {
        return plugin.getConfig().getDouble("protections.deathHeight",
                plugin.getConfig().getDouble("safety.deathHeight", Double.NEGATIVE_INFINITY));
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
        String perm = plugin.getConfig().getString("protections.bypass-permission", "syntrix.protections.bypass");
        boolean inBuildMode = p.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
        boolean bypass = inBuildMode && (perm == null || perm.isBlank() || p.hasPermission(perm));
        if (bypass) {
            return;
        }

        if (e.getFrom() != null && e.getFrom().getY() <= h) {
            return;
        }

        if (e.getTo() != null && e.getTo().getY() <= h) {
            Location target = lobbySpawn();
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.teleport(target);
                ms.send(p, plugin.messages().getString(
                        "commands.setheight.killed",
                        "<gray>You have been teleported to the lobby spawn.</gray>"
                ));
            });
    }
    }
}