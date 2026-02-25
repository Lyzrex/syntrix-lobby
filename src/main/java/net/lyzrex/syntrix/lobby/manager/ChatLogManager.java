package net.lyzrex.syntrix.lobby.manager;

import de.murmelmeister.murmelapi.MurmelAPI;
import de.murmelmeister.murmelapi.user.User;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ChatLogManager {
    private final SyntrixLobby plugin;

    public ChatLogManager(SyntrixLobby plugin) { this.plugin = plugin; }

    public void log(Player player, String message) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            try {
                MurmelAPI.getDatabase().update("INSERT INTO chat_logs (user_id, player_uuid, player_name, message, server_name, world) VALUES (?, ?, ?, ?, ?, ?)", s -> {
                    s.setInt(1, user.id()); s.setString(2, player.getUniqueId().toString()); s.setString(3, player.getName());
                    s.setString(4, message); s.setString(5, "Lobby"); s.setString(6, player.getWorld().getName());
                });
            } catch (Exception ignored) {}
        });
    }
}