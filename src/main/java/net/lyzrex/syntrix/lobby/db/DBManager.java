package net.lyzrex.syntrix.lobby.db;

import net.lyzrex.syntrix.lobby.SyntrixLobby;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public final class DBManager {

    private final SyntrixLobby plugin;


    private String host = "127.0.0.1";
    private int port = 3306;
    private String database = "syntrix";
    private String user = "root";
    private String pass = "";
    private boolean useSSL = false;
    private boolean enabled = false;

    public DBManager(SyntrixLobby plugin) {
        this.plugin = plugin;
    }


    public void init() {
        this.enabled = plugin.getConfig().getBoolean("mysql.enabled", false);
        if (!enabled) {
            plugin.getLogger().info("[DB] MySQL disabled in config.");
            return;
        }

        this.host     = plugin.getConfig().getString("mysql.host", "127.0.0.1");
        this.port     = plugin.getConfig().getInt("mysql.port", 3306);
        this.database = plugin.getConfig().getString("mysql.database", "syntrix");
        this.user     = plugin.getConfig().getString("mysql.user", "root");
        this.pass     = plugin.getConfig().getString("mysql.password", "");
        this.useSSL   = plugin.getConfig().getBoolean("mysql.useSSL", false);

        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Throwable ignored) {}

        try (Connection c = getConnection()) {
            if (c == null) throw new SQLException("Connection is null");
            createTables();
            plugin.getLogger().info("[DB] Connected and ensured tables.");
        } catch (SQLException ex) {
            this.enabled = false;
            plugin.getLogger().severe("[DB] Could not connect: " + ex.getMessage());
        }
    }

    public boolean isEnabled() { return enabled; }


    public Connection getConnection() throws SQLException {
        if (!enabled) throw new SQLException("Database is disabled by config.");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&autoReconnect=true"
                + "&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";
        return DriverManager.getConnection(url, user, pass);
    }


    public void createTables() throws SQLException {
        final String sql = """
                CREATE TABLE IF NOT EXISTS players(
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(32) NOT NULL,
                    last_join TIMESTAMP NULL DEFAULT NULL,
                    last_quit TIMESTAMP NULL DEFAULT NULL,
                    last_ip VARCHAR(64),
                    total_joins INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }


    public byte[] toBytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    public UUID fromBytes(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long msb = bb.getLong();
        long lsb = bb.getLong();
        return new UUID(msb, lsb);
    }


    public void markJoin(UUID uuid, String name, String ip) {
        if (!enabled) return;
        final String sql = """
                INSERT INTO players(uuid, name, last_join, last_ip, total_joins)
                VALUES(?, ?, NOW(), ?, 1)
                ON DUPLICATE KEY UPDATE
                    name=VALUES(name),
                    last_join=VALUES(last_join),
                    last_ip=VALUES(last_ip),
                    total_joins=total_joins+1;
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, ip == null ? "unknown" : ip);
            ps.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] markJoin failed: " + ex.getMessage());
        }
    }

    public void markQuit(UUID uuid) {
        if (!enabled) return;
        final String sql = "UPDATE players SET last_quit = NOW() WHERE uuid = ?;";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] markQuit failed: " + ex.getMessage());
        }
    }
}
