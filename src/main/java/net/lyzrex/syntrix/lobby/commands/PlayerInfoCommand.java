package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;


public record PlayerInfoCommand(SyntrixLobby plugin) implements TabExecutor {

    public PlayerInfoCommand {

        if (plugin.getCommand("playerinfo") != null) {
            plugin.getCommand("playerinfo").setExecutor(this);
            plugin.getCommand("playerinfo").setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Command 'playerinfo' fehlt in plugin.yml");
        }


        if (plugin.db() != null && plugin.db().isEnabled()) {
            try {
                plugin.db().createTables();
            } catch (SQLException ex) {
                plugin.getLogger().warning("[DB] createTables in PlayerInfo: " + ex.getMessage());
            }
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /playerinfo <name>");
            return true;
        }

        String perm = plugin.getConfig().getString("commands.playerinfo.permission", "syntrix.playerinfo");
        if (perm != null && !perm.isBlank() && !sender.hasPermission(perm)) {
            sender.sendMessage("§cYou do not have permission.");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        UUID uuid = target.getUniqueId();
        if (uuid == null) {
            sender.sendMessage("§cPlayer not found.");
            return true;
        }

        if (plugin.db() == null || !plugin.db().isEnabled()) {
            sender.sendMessage("§cDatabase is disabled.");
            return true;
        }

        String sql = "SELECT * FROM players WHERE uuid = ?;";
        try (Connection conn = plugin.db().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name     = rs.getString("name");
                    String lastJoin = rs.getString("last_join");
                    String lastQuit = rs.getString("last_quit");
                    String lastIp   = rs.getString("last_ip");
                    int joins       = rs.getInt("total_joins");
                    boolean online  = Bukkit.getPlayer(uuid) != null;

                    sender.sendMessage("§6--- Player Info: " + (name != null ? name : target.getName()) + " ---");
                    sender.sendMessage("§7Online: §f" + (online ? "§aYes" : "§cNo"));
                    sender.sendMessage("§7Last join: §f" + (lastJoin != null ? lastJoin : "N/A"));
                    sender.sendMessage("§7Last quit: §f" + (lastQuit != null ? lastQuit : "N/A"));
                    sender.sendMessage("§7Total joins: §f" + joins);
                    sender.sendMessage("§7Last IP: §f" + (lastIp != null ? lastIp : "Unknown"));
                    sender.sendMessage("§7UUID: §f" + uuid);
                } else {
                    sender.sendMessage("§cNo data found for that player.");
                }
            }
        } catch (SQLException ex) {
            sender.sendMessage("§cDB error: " + ex.getMessage());
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            String start = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .filter(n -> n != null && n.toLowerCase().startsWith(start))
                    .toList();
        }
        return List.of();
    }
}
