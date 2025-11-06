package net.lyzrex.syntrix.lobby.manager;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.commands.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;

public final class CommandManager {
    private CommandManager() {
    }

    public static void registerAll(SyntrixLobby plugin) {
        add(plugin, "spawn", new SpawnCommand(plugin));
        add(plugin, "setspawn", new SetSpawnCommand(plugin));
        add(plugin, "lobbyreload", new LobbyReloadCommand(plugin));
        add(plugin, "build", new BuildCommand(plugin));
        add(plugin, "setheight", new SetHeightCommand(plugin));
        add(plugin, "vanish", new VanishCommand(plugin));
        add(plugin, "time", new LobbyTimeCommand(plugin));
        add(plugin, "skull", new SkullCommand(plugin));
        add(plugin, "fly", new FlyCommand(plugin));
        add(plugin, "jumpandrunholo", new JumpAndRunHologramCommand(plugin));
        add(plugin, "stats", new StatsCommand(plugin));
    }

    private static void add(SyntrixLobby plugin, String name, TabExecutor exec) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd == null) {
            plugin.getLogger().warning("Command '" + name + "' not found in plugin.yml!");
            return;
        }
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
    }
}