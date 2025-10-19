package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.persistence.PersistentDataType;

public final class LobbyItemEnforcerListener implements Listener {

    private final SyntrixLobby plugin;

    public LobbyItemEnforcerListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    private boolean isInBuild(Player p) {
        return p.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
    }

    private void enforce(Player p) {
        if (p == null || !p.isOnline()) return;
        if (isInBuild(p)) return;
        PlayerJoinListener.giveLobbyLoadout(plugin, p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getServer().getScheduler().runTask(plugin, () -> enforce(e.getPlayer()));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            plugin.getServer().getScheduler().runTask(plugin, () -> enforce(p));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            plugin.getServer().getScheduler().runTask(plugin, () -> enforce(p));
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        plugin.getServer().getScheduler().runTask(plugin, () -> enforce(e.getPlayer()));
    }
}
