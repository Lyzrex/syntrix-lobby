package net.lyzrex.syntrix.lobby.manager;

import de.murmelmeister.murmelapi.MurmelAPI;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;

public class DatabaseSetup {

    public static void init(SyntrixLobby plugin) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {

                MurmelAPI.getDatabase().update("CREATE TABLE IF NOT EXISTS discord_verify (user_id INT PRIMARY KEY, discord_id VARCHAR(32) UNIQUE, discord_name VARCHAR(100), verify_code VARCHAR(10), verified BOOLEAN DEFAULT FALSE, reward_claimed BOOLEAN DEFAULT FALSE, FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
                try { MurmelAPI.getDatabase().update("ALTER TABLE discord_verify ADD COLUMN IF NOT EXISTS reward_claimed BOOLEAN DEFAULT FALSE"); } catch (Exception ignored) {}

                String sqlSession = "CREATE TABLE IF NOT EXISTS player_session (id VARCHAR(36) PRIMARY KEY, user_id INT NOT NULL, login_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, ip_address VARCHAR(64), client_brand VARCHAR(64), protocol_version INT, server_name VARCHAR(32) DEFAULT 'Lobby', FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, INDEX (user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
                MurmelAPI.getDatabase().update(sqlSession);
                try { MurmelAPI.getDatabase().update("ALTER TABLE player_session ADD COLUMN IF NOT EXISTS server_name VARCHAR(32) DEFAULT 'Lobby'"); } catch (Exception ignored) {}

                MurmelAPI.getDatabase().update("DELETE FROM player_session WHERE server_name = 'Lobby'");
                plugin.getLogger().info("Database tables initialized successfully.");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to initialize database tables: " + e.getMessage());
            }
        }, 40L);
    }
}