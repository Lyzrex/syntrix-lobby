package net.lyzrex.syntrix.lobby.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class VipAreaCommand extends BaseCommand {

    public VipAreaCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (!player.hasPermission("syntrix.admin")) {
            player.sendMessage(mm.deserialize("<#FF4D4F>You do not have permission to do this."));
            return true;
        }

        if (args.length != 1 || (!args[0].equalsIgnoreCase("pos1") && !args[0].equalsIgnoreCase("pos2"))) {
            player.sendMessage(mm.deserialize("<#FF4D4F>Usage: /viparea <pos1|pos2>"));
            return true;
        }

        Location loc = player.getLocation();
        String pos = args[0].toLowerCase();

        // Speichert die Koordinaten in der Config
        plugin.getConfig().set("viparea." + pos + ".world", loc.getWorld().getName());
        plugin.getConfig().set("viparea." + pos + ".x", loc.getX());
        plugin.getConfig().set("viparea." + pos + ".y", loc.getY());
        plugin.getConfig().set("viparea." + pos + ".z", loc.getZ());
        plugin.saveConfig();

        player.sendMessage(mm.deserialize("<#2AF598>VIP Area <white><bold>" + pos.toUpperCase() + "</bold></white> successfully set to your location."));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("syntrix.admin")) {
            return List.of("pos1", "pos2");
        }
        return List.of();
    }
}