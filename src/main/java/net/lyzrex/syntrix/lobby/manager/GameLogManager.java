package net.lyzrex.syntrix.lobby.manager;

import de.murmelmeister.murmelapi.MurmelAPI;
import de.murmelmeister.murmelapi.user.User;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public class GameLogManager {
    private final SyntrixLobby plugin;

    public GameLogManager(SyntrixLobby plugin) { this.plugin = plugin; }

    public void logPlayerSession(Player player) {
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "Unknown";
        String clientBrand = player.getClientBrandName() != null ? player.getClientBrandName() : "Unknown";
        int protocol = player.getProtocolVersion();
        String sessionId = UUID.randomUUID().toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            MurmelAPI.getDatabase().update("DELETE FROM player_session WHERE user_id = ? AND server_name = 'Lobby'", s -> s.setInt(1, user.id()));
            MurmelAPI.getDatabase().update("INSERT INTO player_session (id, user_id, ip_address, client_brand, protocol_version, server_name) VALUES (?, ?, ?, ?, ?, ?)", s -> {
                s.setString(1, sessionId); s.setInt(2, user.id()); s.setString(3, ip);
                s.setString(4, clientBrand); s.setInt(5, protocol); s.setString(6, "Lobby");
            });
        });
    }

    public void removePlayerSession(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            MurmelAPI.getDatabase().update("DELETE FROM player_session WHERE user_id = ? AND server_name = 'Lobby'", s -> s.setInt(1, user.id()));
        });
    }

    public void logBlockBreak(Player player, Block block) {
        final String worldName = block.getWorld().getName();
        final int x = block.getX(), y = block.getY(), z = block.getZ();
        final String blockType = block.getType().name();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            MurmelAPI.getDatabase().update("INSERT INTO block_logs (user_id, world, x, y, z, block_type) VALUES (?, ?, ?, ?, ?, ?)", s -> {
                s.setInt(1, user.id()); s.setString(2, worldName);
                s.setInt(3, x); s.setInt(4, y); s.setInt(5, z); s.setString(6, blockType);
            });
        });
    }

    public void logContainer(Player player, String action, String containerType, Location loc, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        final String matName = item.getType().name();
        final int amount = item.getAmount();
        final String worldName = loc.getWorld().getName();
        final int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            MurmelAPI.getDatabase().update("INSERT INTO container_logs (user_id, action, container_type, item_type, amount, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", s -> {
                s.setInt(1, user.id()); s.setString(2, action); s.setString(3, containerType);
                s.setString(4, matName); s.setInt(5, amount); s.setString(6, worldName);
                s.setInt(7, x); s.setInt(8, y); s.setInt(9, z);
            });
        });
    }

    public void logItemAction(Player player, String action, ItemStack item, Location loc) {
        if (item == null || item.getType() == Material.AIR) return;
        final String matName = item.getType().name();
        final int amount = item.getAmount();
        final String worldName = loc.getWorld().getName();
        final int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            MurmelAPI.getDatabase().update("INSERT INTO item_logs (user_id, action, item_type, amount, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", s -> {
                s.setInt(1, user.id()); s.setString(2, action); s.setString(3, matName);
                s.setInt(4, amount); s.setString(5, worldName); s.setInt(6, x);
                s.setInt(7, y); s.setInt(8, z);
            });
        });
    }
}