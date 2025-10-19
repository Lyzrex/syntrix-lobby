package net.lyzrex.syntrix.lobby.manager;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.listeners.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;

public final class ListenerManager {
    private ListenerManager() {}

    public static void registerAll(SyntrixLobby plugin) {
        PluginManager pm = Bukkit.getPluginManager();

        pm.registerEvents(new PlayerJoinListener(plugin), plugin);
        pm.registerEvents(new WelcomeListener(plugin), plugin);
        pm.registerEvents(new LobbyProtectionListener(plugin), plugin);
        pm.registerEvents(new InventoryGuardListener(), plugin);
        pm.registerEvents(new NavigatorListener(plugin), plugin);
        pm.registerEvents(new PlayerHiderListener(plugin, plugin.playerHider()), plugin);
        pm.registerEvents(new LobbyItemEnforcerListener(plugin), plugin);
        pm.registerEvents(new JumpAndRunListener(plugin), plugin);
        pm.registerEvents(new JumpPadListener(plugin), plugin);
        pm.registerEvents(new DoubleJumpListener(plugin), plugin);
        pm.registerEvents(new HeightGuardListener(plugin), plugin);
        pm.registerEvents(new SignColorListener(plugin), plugin);

    }
}
