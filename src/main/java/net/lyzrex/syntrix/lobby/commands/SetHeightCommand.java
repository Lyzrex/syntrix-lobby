package net.lyzrex.syntrix.lobby.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SetHeightCommand(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.ms = new MessageService(plugin);

        PluginCommand cmd = plugin.getCommand("sethight");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Command 'sethight' fehlt in plugin.yml");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage(mm.deserialize(ms.prefix() + "<red>Only players can use this command.</red>"));
            return true;
        }

        if (!p.hasPermission("syntrix.sethight")) {
            p.sendMessage(mm.deserialize(ms.prefix() + "<red>You do not have permission.</red>"));
            return true;
        }

        String worldName = plugin.getConfig().getString("lobby.world", "world");
        World w = plugin.getServer().getWorld(worldName);
        if (w == null) {
            p.sendMessage(mm.deserialize(ms.prefix() + "<red>Lobby world not found:</red> <white>" + worldName + "</white>"));
            return true;
        }

        double height;
        if (args.length >= 1) {
            try {
                height = Double.parseDouble(args[0]);
            } catch (NumberFormatException ex) {
                p.sendMessage(mm.deserialize(ms.prefix() + "<gray>Usage:</gray> <white>/" + label + " [height]</white>"));
                return true;
            }
        } else {
            height = Math.floor(p.getLocation().getY());
        }

        plugin.getConfig().set("safety.deathHeight", height);
        plugin.saveConfig();

        String msg = plugin.messages().getString("sethight.updated",
                        "<green>Death height set to</green> <white>{height}</white>.")
                .replace("{height}", String.valueOf(height));
        p.sendMessage(mm.deserialize(ms.prefix() + msg));
        return true;
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