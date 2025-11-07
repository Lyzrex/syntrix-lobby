package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public final class SetSpawnCommand extends BaseCommand {

    public SetSpawnCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player p)) {
            ms.send(sender, plugin.messages().getString(
                    "commands.setspawn.player-only",
                    "<red>This command can only be used by players.</red>"
            ));
            return true;
        }

        String perm = plugin.getConfig().getString("commands.setspawn.permission", "syntrix.setspawn");
        if (!p.hasPermission(perm)) {
            ms.send(p, plugin.messages().getString(
                    "commands.setspawn.no-permission",
                    "<red>You do not have permission.</red>"
            ));
            return true;
        }

        Location l = p.getLocation();
        plugin.getConfig().set("lobby.world", l.getWorld().getName());
        plugin.getConfig().set("lobby.spawn.x", l.getX());
        plugin.getConfig().set("lobby.spawn.y", l.getY());
        plugin.getConfig().set("lobby.spawn.z", l.getZ());
        plugin.getConfig().set("lobby.spawn.yaw", l.getYaw());
        plugin.getConfig().set("lobby.spawn.pitch", l.getPitch());
        plugin.saveConfig();

        ms.send(p, plugin.messages().getString("commands.setspawn.saved",
                "<green>Lobby spawn point successfully set.</green>"));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command cmd,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        return Collections.emptyList();
    }
}
