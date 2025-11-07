package net.lyzrex.syntrix.lobby.manager;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.listeners.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;

public final class ListenerRegistry {
    private ListenerRegistry() {
    }

    public static void registerAll(SyntrixLobby plugin) {
        PluginManager pm = Bukkit.getPluginManager();

        pm.registerEvents(new PlayerJoinListener(plugin), plugin);
        pm.registerEvents(new WelcomeListener(plugin), plugin);
        pm.registerEvents(new LobbyProtectionListener(plugin), plugin);
        pm.registerEvents(new InventoryGuardListener(plugin), plugin);
        pm.registerEvents(new NavigatorListener(plugin), plugin);
        pm.registerEvents(new LobbyItemEnforcerListener(plugin), plugin);
        pm.registerEvents(new JumpAndRunListener(plugin, plugin.jumpAndRun()), plugin);
        pm.registerEvents(new JumpPadListener(plugin), plugin);
        pm.registerEvents(plugin.doubleJump(), plugin);
        pm.registerEvents(new HeightGuardListener(plugin), plugin);
        pm.registerEvents(new SignColorListener(plugin), plugin);
    }
}