package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public final class SetHeightCommand implements TabExecutor {

    private final SyntrixLobby plugin;
    private final MessageService ms;

    public SetHeightCommand(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.ms = new MessageService(plugin);

        PluginCommand cmd = plugin.getCommand("setheight");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Command 'setheight' fehlt in plugin.yml");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!plugin.getConfig().getBoolean("commands.setheight.enabled", true)) {
            ms.sendFromConfig(sender,
                    "commands.setheight.disabled-command",
                    "<red>The setheight command is currently disabled.</red>");
            return true;
        }

        if (!(sender instanceof Player p)) {
            ms.send(sender, "<red>Only players can use this command.</red>");
            return true;
        }

        String perm = plugin.getConfig().getString("commands.setheight.permission", "syntrix.setheight");
        if (perm != null && !perm.isBlank() && !p.hasPermission(perm)) {
            ms.send(p, plugin.messages().getString("general.no-permission",
                    "<red>You do not have permission.</red>"));
            return true;
        }

        String worldName = plugin.getConfig().getString("lobby.world", "world");
        World w = plugin.getServer().getWorld(worldName);
        if (w == null) {
            String notFound = plugin.messages().getString("commands.setheight.world-missing",
                    "<red>Lobby world not found:</red> <white>{world}</white>");
            ms.send(p, notFound.replace("{world}", worldName));
            return true;
        }

        double height;
        if (args.length >= 1) {
            try {
                height = Double.parseDouble(args[0]);
            } catch (NumberFormatException ex) {
                ms.send(p, usage(label));
                return true;
            }
        } else {
            height = Math.floor(p.getLocation().getY());
        }

        plugin.getConfig().set("protections.deathHeight", height);
        plugin.saveConfig();

        String msg = plugin.messages().getString("commands.setheight.updated",
                        "<green>Death height set to</green> <white>{height}</white>.")
                .replace("{height}", String.valueOf(height));
        ms.send(p, msg);
        return true;
    }

    private String usage(String label) {
        return plugin.messages().getString(
                        "commands.setheight.usage",
                        "<gray>Usage:</gray> <white>/%label% [height]</white>")
                .replace("%label%", label);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player p) {
            double y = Math.floor(p.getLocation().getY());
            return Collections.singletonList(String.valueOf((int) y));
        }
        return Collections.emptyList();
    }
}
