package net.lyzrex.syntrix.lobby.db;

import net.lyzrex.syntrix.lobby.SyntrixLobby;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.time.ZoneId;

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
        boolean configEnabled = plugin.getConfig().getBoolean("mysql.enabled", false);
        this.enabled = false;
        if (!configEnabled) {
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

        try (Connection c = openConnection()) {
            createTables(c);
            this.enabled = true;
            plugin.getLogger().info("[DB] Connected and ensured tables.");
        } catch (SQLException ex) {
            plugin.getLogger().severe("[DB] Could not connect: " + ex.getMessage());
            plugin.getLogger().severe("[DB] MySQL features are disabled. Check the mysql.* settings in config.yml.");
        }
    }

    public boolean isEnabled() { return enabled; }


    public Connection getConnection() throws SQLException {
        if (!enabled) throw new SQLException("Database is disabled. Review earlier MySQL errors and config.yml.");
        return openConnection();
    }


    private Connection openConnection() throws SQLException {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&autoReconnect=true"
                + "&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";
        return DriverManager.getConnection(url, user, pass);
    }


    private void createTables(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players(
                        id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                        uuid VARCHAR(36) NOT NULL,
                        name VARCHAR(32) NOT NULL,
                        first_join TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                        last_join TIMESTAMP NULL DEFAULT NULL,
                        last_quit TIMESTAMP NULL DEFAULT NULL,
                        last_ip VARCHAR(64),
                        total_joins INT NOT NULL DEFAULT 0,
                        login_streak INT NOT NULL DEFAULT 1,
                        PRIMARY KEY (id),
                        UNIQUE KEY uq_players_uuid (uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_ips(
                        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                        player_id INT UNSIGNED NOT NULL,
                        ip VARCHAR(64) NOT NULL,
                        first_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        uses INT NOT NULL DEFAULT 1,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_player_ip (player_id, ip),
                        INDEX idx_player_ip_ip (ip),
                        CONSTRAINT fk_player_ips_player FOREIGN KEY (player_id)
                            REFERENCES players(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
        }

        ensureSchema(c);
    }


    private String buildJdbcUrl(boolean includeDatabase) {
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(host)
                .append(":")
                .append(port);
        if (includeDatabase && database != null && !database.isBlank()) {
            url.append("/").append(database);
        }
        url.append("?useSSL=").append(useSSL)
                .append("&autoReconnect=true")
                .append("&allowPublicKeyRetrieval=true")
                .append("&serverTimezone=UTC");
        return url.toString();
    }


    private void logConnectionDiagnostics(SQLException ex) {
        String state = ex.getSQLState();
        int code = ex.getErrorCode();
        plugin.getLogger().severe("[DB] SQLState=" + (state == null ? "n/a" : state)
                + ", ErrorCode=" + code + ".");

        if (!isAccessDenied(ex)) {
            plugin.getLogger().severe("[DB] Ensure the database is reachable and that firewalls allow TCP connections to "
                    + host + ":" + port + ".");
            return;
        }

        plugin.getLogger().severe("[DB] The database rejected the supplied credentials or privileges. Attempting diagnostics...");
        try (Connection ignored = DriverManager.getConnection(buildJdbcUrl(false), user, pass)) {
            plugin.getLogger().severe("[DB] Login succeeded without selecting a schema, so user '"
                    + user + "' lacks privileges for database '" + database + "'.");
            plugin.getLogger().severe("[DB] Grant the necessary rights, e.g.: GRANT ALL ON `"
                    + database + "`.* TO '" + user + "'@'" + host + "'; FLUSH PRIVILEGES; (adjust host as needed, e.g. '%' or localhost)");
        } catch (SQLException inner) {
            plugin.getLogger().severe("[DB] Login still failed without a schema. Double-check the username, password, and allowed hosts.");
            plugin.getLogger().severe("[DB] Inner error: " + inner.getMessage());
        }
    }


    private boolean isAccessDenied(SQLException ex) {
        String state = ex.getSQLState();
        if ("28000".equals(state)) return true;
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase().contains("access denied");
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
        try (Connection c = getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                PlayerProfile profile = loadProfile(c, uuid);
                int playerId;
                int newStreak = 1;
                Timestamp now = new Timestamp(System.currentTimeMillis());
                ZoneId zone = ZoneId.systemDefault();
                String sanitizedIp = (ip == null || ip.isBlank()) ? "unknown" : ip;

                if (profile == null) {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO players(uuid, name, first_join, last_join, last_ip, total_joins, login_streak)
                            VALUES(?, ?, NOW(), NOW(), ?, 1, 1)
                            """, Statement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, uuid.toString());
                        ins.setString(2, name);
                        ins.setString(3, sanitizedIp);
                        ins.executeUpdate();
                        try (ResultSet keys = ins.getGeneratedKeys()) {
                            if (keys.next()) {
                                playerId = keys.getInt(1);
                            } else {
                                throw new SQLException("Failed to retrieve generated player id");
                            }
                        }
                    }
                } else {
                    playerId = profile.id();
                    Timestamp lastJoin = profile.lastJoin();
                    int streak = Math.max(1, profile.loginStreak());
                    if (lastJoin != null) {
                        long diff = now.toInstant().atZone(zone).toLocalDate().toEpochDay()
                                - lastJoin.toInstant().atZone(zone).toLocalDate().toEpochDay();
                        if (diff == 0) {
                            newStreak = streak;
                        } else if (diff == 1) {
                            newStreak = streak + 1;
                        } else {
                            newStreak = 1;
                        }
                    }
                    try (PreparedStatement upd = c.prepareStatement("""
                            UPDATE players
                            SET name = ?,
                                last_join = NOW(),
                                last_ip = ?,
                                total_joins = total_joins + 1,
                                login_streak = ?
                            WHERE id = ?
                            """)) {
                        upd.setString(1, name);
                        upd.setString(2, sanitizedIp);
                        upd.setInt(3, newStreak);
                        upd.setInt(4, playerId);
                        upd.executeUpdate();
                    }
                }

                if (!"unknown".equalsIgnoreCase(sanitizedIp)) {
                    upsertPlayerIp(c, playerId, sanitizedIp);
                }

                c.commit();
            } catch (SQLException ex) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw ex;
            } finally {
                c.setAutoCommit(auto);
            }
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

    public PlayerProfile loadPlayerProfile(UUID uuid) throws SQLException {
        try (Connection c = getConnection()) {
            return loadProfile(c, uuid);
        }
    }

    public List<IpHistoryEntry> loadIpHistory(int playerId, int limit) throws SQLException {
        List<IpHistoryEntry> entries = new ArrayList<>();
        if (limit <= 0) {
            return entries;
        }
        String sql = """
                SELECT ip, first_seen, last_seen, uses
                FROM player_ips
                WHERE player_id = ?
                ORDER BY last_seen DESC
                LIMIT ?
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new IpHistoryEntry(
                            rs.getString("ip"),
                            rs.getTimestamp("first_seen"),
                            rs.getTimestamp("last_seen"),
                            rs.getInt("uses")
                    ));
                }
            }
        }
        return entries;
    }

    public List<AltAccountMatch> findPotentialAlts(int playerId, int limit) throws SQLException {
        List<AltAccountMatch> matches = new ArrayList<>();
        if (limit <= 0) {
            return matches;
        }
        String sql = """
                SELECT p2.id,
                       p2.uuid,
                       p2.name,
                       GROUP_CONCAT(DISTINCT pi2.ip ORDER BY pi2.last_seen DESC SEPARATOR ', ') AS ips,
                       MAX(pi2.last_seen) AS last_seen
                FROM player_ips base
                JOIN player_ips pi2 ON pi2.ip = base.ip
                JOIN players p2 ON p2.id = pi2.player_id
                WHERE base.player_id = ?
                  AND p2.id <> base.player_id
                GROUP BY p2.id, p2.uuid, p2.name
                ORDER BY last_seen DESC
                LIMIT ?
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new AltAccountMatch(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            rs.getString("ips") == null ? "" : rs.getString("ips"),
                            rs.getTimestamp("last_seen")
                    ));
                }
            }
        }
        return matches;
    }

    private PlayerProfile loadProfile(Connection c, UUID uuid) throws SQLException {
        String sql = """
                SELECT id, uuid, name, first_join, last_join, last_quit, last_ip, total_joins, login_streak
                FROM players
                WHERE uuid = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new PlayerProfile(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getTimestamp("first_join"),
                        rs.getTimestamp("last_join"),
                        rs.getTimestamp("last_quit"),
                        rs.getString("last_ip"),
                        rs.getInt("total_joins"),
                        rs.getInt("login_streak")
                );
            }
        }
    }

    private void upsertPlayerIp(Connection c, int playerId, String ip) throws SQLException {
        String select = "SELECT id FROM player_ips WHERE player_id = ? AND ip = ?";
        try (PreparedStatement ps = c.prepareStatement(select)) {
            ps.setInt(1, playerId);
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    try (PreparedStatement upd = c.prepareStatement("""
                            UPDATE player_ips
                            SET last_seen = NOW(), uses = uses + 1
                            WHERE id = ?
                            """)) {
                        upd.setLong(1, id);
                        upd.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ins = c.prepareStatement("""
                            INSERT INTO player_ips(player_id, ip, first_seen, last_seen, uses)
                            VALUES(?, ?, NOW(), NOW(), 1)
                            """)) {
                        ins.setInt(1, playerId);
                        ins.setString(2, ip);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    private void ensureSchema(Connection c) {
        try {
            DatabaseMetaData meta = c.getMetaData();
            boolean hasId = hasColumn(meta, "players", "id");
            boolean hasFirstJoin = hasColumn(meta, "players", "first_join");
            boolean hasLoginStreak = hasColumn(meta, "players", "login_streak");

            if (!hasId) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players DROP PRIMARY KEY");
                } catch (SQLException ignored) {}
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD COLUMN id INT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (id)");
                } catch (SQLException ex) {
                    plugin.getLogger().warning("[DB] Could not add player id column: " + ex.getMessage());
                }
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD UNIQUE KEY uq_players_uuid (uuid)");
                } catch (SQLException ignored) {}
            }

            if (!hasFirstJoin) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD COLUMN first_join TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP AFTER name");
                } catch (SQLException ex) {
                    plugin.getLogger().warning("[DB] Could not add first_join column: " + ex.getMessage());
                }
            }

            if (!hasLoginStreak) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD COLUMN login_streak INT NOT NULL DEFAULT 1 AFTER total_joins");
                } catch (SQLException ex) {
                    plugin.getLogger().warning("[DB] Could not add login_streak column: " + ex.getMessage());
                }
            }

            if (!hasColumn(meta, "player_ips", "uses")) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE player_ips ADD COLUMN uses INT NOT NULL DEFAULT 1 AFTER last_seen");
                } catch (SQLException ignored) {}
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] Schema ensure failed: " + ex.getMessage());
        }
    }

    private boolean hasColumn(DatabaseMetaData meta, String table, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    public record PlayerProfile(int id,
                                UUID uuid,
                                String name,
                                Timestamp firstJoin,
                                Timestamp lastJoin,
                                Timestamp lastQuit,
                                String lastIp,
                                int totalJoins,
                                int loginStreak) { }

    public record IpHistoryEntry(String ip, Timestamp firstSeen, Timestamp lastSeen, int uses) { }

    public record AltAccountMatch(int id, UUID uuid, String name, String ips, Timestamp lastSeen) { }
}