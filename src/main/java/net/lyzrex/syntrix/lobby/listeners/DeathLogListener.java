package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class DeathLogListener implements Listener {

    private final SyntrixLobby plugin;

    public DeathLogListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.deathLogs() != null) {
            plugin.deathLogs().handleDeath(event);
        }
    }
}