package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class PlayerTelemetryListener implements Listener {

    private final SyntrixLobby plugin;

    public PlayerTelemetryListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.sessions() != null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!enabled() || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getWorld() == event.getTo().getWorld()
                && event.getFrom().distanceSquared(event.getTo()) < 1.0E-4) {
            return;
        }
        plugin.sessions().handleMovement(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled()) {
            return;
        }
        plugin.sessions().handleInteraction(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (!enabled() || event.getWhoClicked() == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        plugin.sessions().handleInteraction(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled()) {
            return;
        }
        plugin.sessions().handleInteraction(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        if (!enabled()) {
            return;
        }
        plugin.sessions().handleInteraction(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResourcePack(PlayerResourcePackStatusEvent event) {
        if (!enabled()) {
            return;
        }
        plugin.sessions().handleResourcePack(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled()) {
            return;
        }
        plugin.sessions().handleDeath(event.getEntity());
    }
}
