package net.lyzrex.syntrix.lobby.manager;

import de.murmelmeister.murmelapi.MurmelAPI;
import de.murmelmeister.murmelapi.user.User;
import de.murmelmeister.murmelapi.user.session.UserSession;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SessionManager {

    private final SyntrixLobby plugin;

    public SessionManager(SyntrixLobby plugin) {
        this.plugin = plugin;
        initTable();
    }

    private void initTable() {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                String sql = """
                    CREATE TABLE IF NOT EXISTS player_session (
                        id VARCHAR(36) PRIMARY KEY,
                        user_id INT NOT NULL,
                        login_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        ip_address VARCHAR(64),
                        client_brand VARCHAR(64),
                        protocol_version INT,
                        server_name VARCHAR(32) DEFAULT 'Lobby',
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                        INDEX (user_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
                MurmelAPI.getDatabase().update(sql);

                try {
                    MurmelAPI.getDatabase().update("ALTER TABLE player_session ADD COLUMN IF NOT EXISTS server_name VARCHAR(32) DEFAULT 'Lobby'");
                } catch (Exception ignored) {}

                MurmelAPI.getDatabase().update("DELETE FROM player_session WHERE server_name = 'Lobby'");

                plugin.getLogger().info("Player sessions table initialized and ghost sessions cleared.");
            } catch (Exception e) {
                plugin.getLogger().warning("Could not initialize player_session table: " + e.getMessage());
            }
        }, 60L);
    }

    public void logSession(Player player) {
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "Unknown";
        String clientBrand = player.getClientBrandName() != null ? player.getClientBrandName() : "Unknown";
        int protocol = player.getProtocolVersion();
        String sessionId = UUID.randomUUID().toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());

            // FIX: Wenn der Spieler das allererste Mal joint, existiert er nicht. Wir müssen ihn anlegen!
            if (user == null) {
                user = MurmelAPI.getUserProvider().create(player.getUniqueId(), player.getName());
            }

            // Falls die Datenbank trotzdem fehlschlägt, abbrechen
            if (user == null) return;

            User finalUser = user;
            String sqlPlayerSession = """
                CREATE TABLE IF NOT EXISTS player_session (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id INT NOT NULL,
                    login_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    ip_address VARCHAR(64),
                    client_brand VARCHAR(64),
                    protocol_version INT,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """;
            MurmelAPI.getDatabase().update(sqlPlayerSession);
            String sqlVerify = """
    CREATE TABLE IF NOT EXISTS discord_verify (
        user_id INT PRIMARY KEY,
        discord_id VARCHAR(32) UNIQUE,
        discord_name VARCHAR(100),
        verify_code VARCHAR(10),
        verified BOOLEAN DEFAULT FALSE,
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
""";
            MurmelAPI.getDatabase().update(sqlVerify);
            Bukkit.getLogger().info("[SyntrixPlots] Database tables verified.");
            String sql = "INSERT INTO player_session (id, user_id, ip_address, client_brand, protocol_version, server_name) VALUES (?, ?, ?, ?, ?, ?)";
            final int finalUserId = user.id();
            MurmelAPI.getDatabase().update(sql, s -> {
                s.setString(1, sessionId);
                s.setInt(2, finalUserId);
                s.setString(3, ip);
                s.setString(4, clientBrand);
                s.setInt(5, protocol);
                s.setString(6, "Lobby");
            });
        });
    }

    public void removeSession(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return;
            MurmelAPI.getDatabase().update("DELETE FROM player_session WHERE user_id = ? AND server_name = 'Lobby'", s -> s.setInt(1, user.id()));
        });
    }
}