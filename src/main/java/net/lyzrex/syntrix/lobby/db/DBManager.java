package net.lyzrex.syntrix.lobby.db;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.configuration.ConfigurationSection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DBManager {

    private final SyntrixLobby plugin;

    private static final String SYSTEM_ZONE_KEY = "<system>";


    private String host = "127.0.0.1";
    private int port = 3306;
    private String database = "syntrix";
    private String user = "root";
    private String pass = "";
    private boolean useSSL = false;
    private String serverTimezone = "UTC";
    private String jdbcOverride = null;
    private Map<String, String> extraParameters = Map.of();
    private String jdbcUrl = null;
    private String jdbcBaseUrl = null;
    private Properties baseProperties = new Properties();
    private DriverInfo driverInfo = null;
    private boolean enabled = false;
    private String configuredHost = null;
    private volatile ZoneId cachedLoginZone = null;
    private volatile String cachedLoginZoneKey = null;
    private volatile String lastInvalidLoginZone = null;
    private final ConcurrentMap<UUID, Integer> cachedPlayerIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> activeSessions = new ConcurrentHashMap<>();
    private SimpleConnectionPool connectionPool = null;
    private int poolMaxSize = 8;
    private long poolBorrowTimeoutMs = 5000L;

    private static final Pattern ACCESS_DENIED_PATTERN = Pattern.compile(
            "Access denied for user '([^']*)'@'([^']*)' \\(using password: (YES|NO)\\)",
            Pattern.CASE_INSENSITIVE
    );

    public DBManager(SyntrixLobby plugin) {
        this.plugin = plugin;
    }


    public void init() {
        boolean configEnabled = plugin.getConfig().getBoolean("mysql.enabled", false);
        this.enabled = false;
        this.driverInfo = null;
        this.jdbcUrl = null;
        this.jdbcBaseUrl = null;
        this.baseProperties = new Properties();
        this.cachedPlayerIds.clear();
        this.activeSessions.clear();
        shutdownPool();
        this.connectionPool = null;
        if (!configEnabled) {
            plugin.getLogger().info("[DB] MySQL disabled in config.");
            return;
        }
        this.host = normalizeHost(plugin.getConfig().getString("mysql.host", "127.0.0.1"));
        this.port = plugin.getConfig().getInt("mysql.port", 3306);
        this.database = trimToNull(plugin.getConfig().getString("mysql.database", "syntrix"));
        this.user = trimToNull(plugin.getConfig().getString("mysql.user", "root"));
        this.pass = plugin.getConfig().getString("mysql.password", "");
        this.useSSL = plugin.getConfig().getBoolean("mysql.useSSL", false);
        this.serverTimezone = plugin.getConfig().getString("mysql.server-timezone", "UTC");
        this.jdbcOverride = plugin.getConfig().getString("mysql.jdbc-url", "");
        this.poolMaxSize = Math.max(1, plugin.getConfig().getInt("mysql.pool.max-size", 8));
        this.poolBorrowTimeoutMs = Math.max(250L, plugin.getConfig().getLong("mysql.pool.borrow-timeout", 5000L));
        this.cachedLoginZone = null;
        this.cachedLoginZoneKey = null;
        this.lastInvalidLoginZone = null;
        this.configuredHost = this.host;

        if (this.pass == null) this.pass = "";
        if (this.database == null || this.database.isBlank()) {
            plugin.getLogger().severe("[DB] mysql.database is blank. Set a schema name in config.yml.");
            return;
        }
        if (this.user == null || this.user.isBlank()) {
            plugin.getLogger().severe("[DB] mysql.user is blank. Set the database username in config.yml.");
            return;
        }
        if (this.serverTimezone != null) {
            this.serverTimezone = this.serverTimezone.trim();
            if (this.serverTimezone.isEmpty()) this.serverTimezone = null;
        }
        if (this.jdbcOverride != null) {
            this.jdbcOverride = this.jdbcOverride.trim();
            if (this.jdbcOverride.isEmpty()) this.jdbcOverride = null;
        }

        ConfigurationSection props = plugin.getConfig().getConfigurationSection("mysql.connection-properties");
        Map<String, String> params = new LinkedHashMap<>();
        if (props != null) {
            for (String key : props.getKeys(false)) {
                Object value = props.get(key);
                if (value != null) {
                    params.put(key, String.valueOf(value));
                }
            }
        }
        this.extraParameters = params;

        DriverType driverType = DriverType.fromConfig(plugin.getConfig().getString("mysql.dialect", "auto"));
        this.driverInfo = resolveDriver(driverType);
        if (this.driverInfo == null) {
            plugin.getLogger().severe("[DB] No suitable JDBC driver was found (dialect=" + driverType.name().toLowerCase(Locale.ROOT) + "). "
                    + "Add a MySQL or MariaDB JDBC driver to the server's classpath (plugins/lib) and restart.");
            return;
        }

        plugin.getLogger().info("[DB] Using JDBC driver: " + driverInfo.displayName() + " (" + driverInfo.driverClass() + ")");

        if (!attemptInitialisation(true)) {
            return;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void shutdown() {
        shutdownPool();
        this.enabled = false;
    }


    public Connection getConnection() throws SQLException {

        if (!enabled) throw new SQLException("Database is disabled. Review earlier MySQL errors and config.yml.");
        return openConnection();
    }


    private Connection openConnection() throws SQLException {
        if (connectionPool != null) {
            return connectionPool.borrow();
        }
        return openRawConnection();
    }

    private Connection openRawConnection() throws SQLException {
        if (driverInfo == null || jdbcUrl == null) {
            throw new SQLException("No JDBC driver has been initialised.");
        }
        Properties props = newConnectionProperties();
        return DriverManager.getConnection(jdbcUrl, props);
    }


    private boolean attemptInitialisation(boolean allowFallback) {
        this.jdbcBaseUrl = buildJdbcUrl(false);
        this.jdbcUrl = buildJdbcUrl(true);
        this.baseProperties = buildConnectionProperties();

        logConnectionAttempt();

        Connection initial = null;
        boolean attemptedCreate = false;
        while (true) {
            try {
                initial = openRawConnection();
                validateConnection(initial);
                break;
            } catch (SQLException ex) {
                if (!attemptedCreate && isUnknownDatabase(ex) && jdbcOverride == null
                        && database != null && !database.isBlank()) {
                    try {
                        attemptCreateDatabase();
                        attemptedCreate = true;
                        continue;
                    } catch (SQLException retry) {
                        if (allowFallback) {
                            AlternateAttemptResult alternate = tryAlternateHost(retry);
                            if (alternate == AlternateAttemptResult.SUCCESS) {
                                return true;
                            }
                            if (alternate == AlternateAttemptResult.FAILURE_ALREADY_LOGGED) {
                                return false;
                            }
                        }
                        handleInitFailure(retry, false);
                        return false;
                    }
                }

                if (allowFallback) {
                    AlternateAttemptResult alternate = tryAlternateHost(ex);
                    if (alternate == AlternateAttemptResult.SUCCESS) {
                        return true;
                    }
                    if (alternate == AlternateAttemptResult.FAILURE_ALREADY_LOGGED) {
                        return false;
                    }
                }

                handleInitFailure(ex, false);
                return false;
            }
        }

        try (Connection c = initial) {
            createTables(c);
        } catch (SQLException ex) {
            handleInitFailure(ex, false);
            return false;
        }

        this.connectionPool = new SimpleConnectionPool(
                jdbcUrl,
                baseProperties,
                poolMaxSize,
                poolBorrowTimeoutMs,
                plugin.getLogger()
        );
        this.enabled = true;
        plugin.getLogger().info("[DB] Connected and ensured tables.");
        if (configuredHost != null && !configuredHost.equalsIgnoreCase(this.host)) {
            plugin.getLogger().warning("[DB] Connection succeeded using alternate host '" + this.host
                    + "'. Update mysql.host to avoid fallback attempts on restart.");
        }
        return true;
    }

    private AlternateAttemptResult tryAlternateHost(SQLException cause) {
        String alternate = computeAlternateHost(this.host, cause);
        if (alternate == null) {
            return AlternateAttemptResult.NOT_ATTEMPTED;
        }
        return attemptAlternateInitialisation(cause, alternate);
    }

    private AlternateAttemptResult attemptAlternateInitialisation(SQLException cause, String alternateHost) {
        String failedHost = this.host;
        plugin.getLogger().warning("[DB] Connection attempt to '" + failedHost + "' failed: " + cause.getMessage());
        plugin.getLogger().warning("[DB] Retrying using alternate host '" + alternateHost
                + "' (configured mysql.host='" + configuredHost + "').");

        this.host = alternateHost;
        boolean success = attemptInitialisation(false);
        if (!success) {
            this.host = failedHost;
            return AlternateAttemptResult.FAILURE_ALREADY_LOGGED;
        }
        return AlternateAttemptResult.SUCCESS;
    }

    private String computeAlternateHost(String attemptedHost, SQLException cause) {
        if (!isAccessDenied(cause)) {
            return null;
        }
        if (jdbcOverride != null) {
            return null;
        }
        if (attemptedHost == null) {
            return null;
        }
        String normalized = attemptedHost.trim();
        if (normalized.equalsIgnoreCase("localhost")) {
            return "127.0.0.1";
        }
        if ("127.0.0.1".equals(normalized)) {
            return "localhost";
        }
        if ("::1".equals(normalized)) {
            return "localhost";
        }
        return null;
    }

    private enum AlternateAttemptResult {
        NOT_ATTEMPTED,
        SUCCESS,
        FAILURE_ALREADY_LOGGED
    }


    private void validateConnection(Connection connection) throws SQLException {
        if (connection == null) {
            throw new SQLException("Driver returned null connection.");
        }
        try {
            if (!connection.isValid(5)) {
                throw new SQLException("Connection validation failed (isValid returned false).");
            }
        } catch (SQLFeatureNotSupportedException | AbstractMethodError ignored) {
            // Older drivers do not support isValid; ignore validation in that case.
        }
    }


    private void createTables(Connection c) throws SQLException {
        ensurePlayersTable(c);
        ensurePlayerIpsTable(c);
        ensurePlayerSessionsTable(c);
    }


    private void ensurePlayersTable(Connection c) throws SQLException {
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
                        total_playtime_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0,
                        PRIMARY KEY (id),
                        UNIQUE KEY uq_players_uuid (uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
        }

        ensurePlayersSchema(c);
        ensureTableEngine(c, "players", "InnoDB");
    }


    private void ensurePlayerIpsTable(Connection c) throws SQLException {
        final String createSql = """
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
                """;
        try (Statement st = c.createStatement()) {
            st.executeUpdate(createSql);
        } catch (SQLException ex) {
            if (isForeignKeyCreationFailure(ex)) {
                plugin.getLogger().warning("[DB] player_ips table creation failed due to foreign key issues. Attempting to realign the players table schema and retry.");
                ensurePlayersSchema(c);
                ensureTableEngine(c, "players", "InnoDB");
                try (Statement retry = c.createStatement()) {
                    retry.executeUpdate(createSql);
                }
            } else {
                throw ex;
            }
        }

        ensurePlayerIpsSchema(c);
        ensureTableEngine(c, "player_ips", "InnoDB");
    }


    private void ensurePlayerSessionsTable(Connection c) throws SQLException {
        final String createSql = """
                CREATE TABLE IF NOT EXISTS player_sessions(
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_id INT UNSIGNED NOT NULL,
                    session_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    session_end TIMESTAMP NULL DEFAULT NULL,
                    duration_seconds BIGINT UNSIGNED DEFAULT NULL,
                    afk_seconds BIGINT UNSIGNED DEFAULT 0,
                    join_ping INT DEFAULT NULL,
                    avg_ping INT DEFAULT NULL,
                    min_ping INT DEFAULT NULL,
                    max_ping INT DEFAULT NULL,
                    current_ping INT DEFAULT NULL,
                    join_tps DOUBLE DEFAULT NULL,
                    last_tps DOUBLE DEFAULT NULL,
                    client_version VARCHAR(64) DEFAULT NULL,
                    client_protocol INT DEFAULT NULL,
                    client_brand VARCHAR(128) DEFAULT NULL,
                    client_mods VARCHAR(256) DEFAULT NULL,
                    is_bedrock TINYINT(1) NOT NULL DEFAULT 0,
                    resource_pack VARCHAR(256) DEFAULT NULL,
                    resource_pack_status VARCHAR(64) DEFAULT NULL,
                    resource_pack_hash VARCHAR(128) DEFAULT NULL,
                    resource_pack_prompt VARCHAR(128) DEFAULT NULL,
                    last_world VARCHAR(64) DEFAULT NULL,
                    last_x DOUBLE DEFAULT NULL,
                    last_y DOUBLE DEFAULT NULL,
                    last_z DOUBLE DEFAULT NULL,
                    last_yaw FLOAT DEFAULT NULL,
                    last_pitch FLOAT DEFAULT NULL,
                    last_death_world VARCHAR(64) DEFAULT NULL,
                    last_death_x DOUBLE DEFAULT NULL,
                    last_death_y DOUBLE DEFAULT NULL,
                    last_death_z DOUBLE DEFAULT NULL,
                    last_death_yaw FLOAT DEFAULT NULL,
                    last_death_pitch FLOAT DEFAULT NULL,
                    last_death_time TIMESTAMP NULL DEFAULT NULL,
                    PRIMARY KEY (id),
                    INDEX idx_player_sessions_player (player_id, session_start),
                    CONSTRAINT fk_sessions_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;

        try (Statement st = c.createStatement()) {
            st.executeUpdate(createSql);
        }

        ensurePlayerSessionsSchema(c);
        ensureTableEngine(c, "player_sessions", "InnoDB");
    }


    private Properties buildConnectionProperties() {
        Properties props = new Properties();
        for (Map.Entry<String, String> entry : extraParameters.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }

        if (user != null) {
            props.setProperty("user", user);
        }
        if (pass != null) {
            props.setProperty("password", pass);
        }

        applyDefaultProperty(props, "useSSL", Boolean.toString(useSSL));
        applyDefaultProperty(props, "useUnicode", "true");
        applyDefaultProperty(props, "characterEncoding", StandardCharsets.UTF_8.name());

        if (driverInfo.supportsPublicKeyRetrieval()) {
            applyDefaultProperty(props, "allowPublicKeyRetrieval", "true");
        }
        if (driverInfo.supportsServerTimezone() && serverTimezone != null && !serverTimezone.isBlank()) {
            applyDefaultProperty(props, "serverTimezone", serverTimezone);
        }

        return props;
    }

    private Properties newConnectionProperties() {
        Properties copy = new Properties();
        for (Map.Entry<Object, Object> entry : baseProperties.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private void applyDefaultProperty(Properties props, String key, String value) {
        if (value == null) {
            return;
        }
        if (props.containsKey(key)) {
            return;
        }
        props.setProperty(key, value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeHost(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "127.0.0.1" : trimmed;
    }

    private String buildJdbcUrl(boolean includeDatabase) {
        if (jdbcOverride != null) {
            return jdbcOverride;
        }

        StringBuilder url = new StringBuilder(driverInfo.jdbcScheme())
                .append(formatHostForJdbc(host));
        if (port > 0) {
            url.append(":").append(port);
        }
        if (includeDatabase && database != null && !database.isBlank()) {
            url.append("/").append(database);
        }
        return url.toString();
    }

    private String formatHostForJdbc(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return "127.0.0.1";
        }
        String trimmed = rawHost.trim();
        if (trimmed.indexOf(':') >= 0 && !trimmed.startsWith("[") && trimmed.indexOf(',') < 0) {
            return "[" + trimmed + "]";
        }
        return trimmed;
    }

    private void shutdownPool() {
        if (connectionPool != null) {
            connectionPool.close();
            connectionPool = null;
        }
    }


    private void logConnectionDiagnostics(SQLException ex) {
        String state = ex.getSQLState();
        int code = ex.getErrorCode();
        plugin.getLogger().severe("[DB] SQLState=" + (state == null ? "n/a" : state)
                + ", ErrorCode=" + code + ".");

        if (isUnknownDatabase(ex)) {
            plugin.getLogger().severe("[DB] The database '" + database + "' does not exist or is inaccessible."
                    + (jdbcOverride == null ? " Ensure it is created and the configured user has privileges." : " Custom mysql.jdbc-url prevents automatic creation."));
        }

        if (!isAccessDenied(ex)) {
            plugin.getLogger().severe("[DB] Ensure the database is reachable and that firewalls allow TCP connections to "
                    + host + ":" + port + ".");
            return;
        }

        AccessDeniedDetails loggedDetails = logAccessDeniedHints(ex);

        if (jdbcOverride != null) {
            plugin.getLogger().severe("[DB] Skipping credential diagnostics because mysql.jdbc-url is set. Verify user and password manually.");
            return;
        }
        if (jdbcBaseUrl == null) {
            plugin.getLogger().severe("[DB] Skipping credential diagnostics because no base JDBC URL is available.");
            return;
        }

        plugin.getLogger().severe("[DB] The database rejected the supplied credentials or privileges. Attempting diagnostics...");
        Properties props = newConnectionProperties();
        props.remove("database");
        props.remove("currentSchema");
        props.remove("schema");
        try (Connection ignored = DriverManager.getConnection(jdbcBaseUrl, props)) {
            plugin.getLogger().severe("[DB] Login succeeded without selecting a schema, so user '"
                    + user + "' lacks privileges for database '" + database + "'.");
            plugin.getLogger().severe("[DB] Grant the necessary rights, e.g.: GRANT ALL ON `"
                    + database + "`.* TO '" + user + "'@'" + host + "'; FLUSH PRIVILEGES; (adjust host as needed, e.g. '%' or localhost)");
        } catch (SQLException inner) {
            plugin.getLogger().severe("[DB] Login still failed without a schema. Double-check the username, password, and allowed hosts.");
            plugin.getLogger().severe("[DB] Inner error: " + inner.getMessage());
            logAccessDeniedHints(inner, loggedDetails);
            logExceptionChain(inner);
        }
    }


    private boolean isAccessDenied(SQLException ex) {
        String state = ex.getSQLState();
        if ("28000".equals(state)) return true;
        if (ex.getErrorCode() == 1045) return true;
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase().contains("access denied");
    }

    private boolean isUnknownDatabase(SQLException ex) {
        if (ex == null) return false;
        if (ex.getErrorCode() == 1049) return true;
        String state = ex.getSQLState();
        if ("42000".equals(state)) {
            String msg = ex.getMessage();
            return msg != null && msg.toLowerCase(Locale.ROOT).contains("unknown database");
        }
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("unknown database");
    }

    private void handleInitFailure(SQLException ex, boolean diagnosticsLogged) {
        String target;
        if (jdbcOverride != null) {
            target = jdbcOverride;
        } else if (jdbcUrl != null) {
            target = jdbcUrl;
        } else {
            target = host + ":" + port + "/" + database;
        }

        plugin.getLogger().severe("[DB] Could not connect to "
                + target
                + " as '" + user + "': " + ex.getMessage());
        logExceptionChain(ex);
        if (!diagnosticsLogged) {
            logConnectionDiagnostics(ex);
        }
        plugin.getLogger().severe("[DB] MySQL features are disabled. Check the mysql.* settings in config.yml.");
    }

    private void logExceptionChain(Throwable throwable) {
        Throwable cause = throwable.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                plugin.getLogger().severe("[DB] Caused by (SQLState=" + (state == null ? "n/a" : state)
                        + ", ErrorCode=" + sql.getErrorCode() + "): "
                        + sql.getClass().getName() + ": " + sql.getMessage());
            } else {
                plugin.getLogger().severe("[DB] Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
    }

    private AccessDeniedDetails logAccessDeniedHints(SQLException ex) {
        return logAccessDeniedHints(ex, null);
    }

    private AccessDeniedDetails logAccessDeniedHints(SQLException ex, AccessDeniedDetails alreadyLogged) {
        if (!isAccessDenied(ex)) {
            return alreadyLogged;
        }

        AccessDeniedDetails details = AccessDeniedDetails.parse(ex == null ? null : ex.getMessage());
        if (details == null) {
            return alreadyLogged;
        }

        if (alreadyLogged != null && alreadyLogged.equals(details)) {
            return alreadyLogged;
        }

        plugin.getLogger().severe("[DB] Access denied specifically for '" + details.user() + "'@'" + details.host()
                + "'. MySQL matches users by host, so ensure that entry exists with the correct password.");
        plugin.getLogger().severe("[DB] You can inspect the configured hosts via: SELECT host,user FROM mysql.user WHERE user='"
                + details.user() + "';");

        if (!details.passwordProvided()) {
            plugin.getLogger().severe("[DB] The server reported that no password was sent. Double-check mysql.password in config.yml.");
        }

        if ("localhost".equalsIgnoreCase(details.host()) || "127.0.0.1".equals(details.host())) {
            plugin.getLogger().severe("[DB] If only '" + details.user() + "'@'%' exists, create/update a localhost-specific user with:");
            plugin.getLogger().severe("[DB]   CREATE USER IF NOT EXISTS '" + details.user() + "'@'localhost' IDENTIFIED BY '***';");
            plugin.getLogger().severe("[DB]   GRANT ALL ON `" + database + "`.* TO '" + details.user() + "'@'localhost';");
        }

        return details;
    }

    private void attemptCreateDatabase() throws SQLException {
        if (jdbcOverride != null) {
            plugin.getLogger().severe("[DB] mysql.jdbc-url is configured; automatic database creation is disabled.");
            throw new SQLException("Cannot create database when mysql.jdbc-url is set.");
        }
        if (database == null || database.isBlank()) {
            throw new SQLException("Database name is empty; cannot create schema.");
        }
        if (jdbcBaseUrl == null) {
            throw new SQLException("No JDBC base URL available to create the database.");
        }

        String quoted = "`" + database.replace("`", "``") + "`";
        plugin.getLogger().warning("[DB] Database '" + database + "' not found. Attempting to create it...");
        Properties props = newConnectionProperties();
        props.remove("database");
        props.remove("currentSchema");
        props.remove("schema");
        try (Connection root = DriverManager.getConnection(jdbcBaseUrl, props);
             Statement st = root.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS " + quoted + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        plugin.getLogger().info("[DB] Created database '" + database + "'. Retrying connection.");
    }

    private void logConnectionAttempt() {
        String passwordState = (pass == null || pass.isEmpty()) ? "blank" : "provided";
        if (jdbcOverride != null) {
            plugin.getLogger().info("[DB] Attempting to connect using custom mysql.jdbc-url (password " + passwordState + "). Host/port/database settings are ignored.");
            logEffectiveProperties();
            return;
        }

        plugin.getLogger().info("[DB] Connecting to " + jdbcUrl + " as '" + user + "' (password " + passwordState + ").");
        logEffectiveProperties();
    }

    private void logEffectiveProperties() {
        if (baseProperties == null || baseProperties.isEmpty()) {
            return;
        }
        Properties props = newConnectionProperties();
        props.remove("user");
        props.remove("password");
        if (props.isEmpty()) {
            return;
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String key : props.stringPropertyNames()) {
            joiner.add(key + "=" + props.getProperty(key));
        }
        plugin.getLogger().info("[DB] Connection properties: " + joiner);
    }

    private DriverInfo resolveDriver(DriverType requested) {
        List<DriverInfo> candidates = switch (requested) {
            case MYSQL -> List.of(DriverInfo.MYSQL_CJ, DriverInfo.MYSQL_LEGACY);
            case MARIADB -> List.of(DriverInfo.MARIADB);
            case AUTO -> List.of(DriverInfo.MYSQL_CJ, DriverInfo.MARIADB, DriverInfo.MYSQL_LEGACY);
        };

        for (DriverInfo candidate : candidates) {
            if (tryLoadDriver(candidate.driverClass())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean tryLoadDriver(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        } catch (Throwable t) {
            plugin.getLogger().warning("[DB] Failed to initialise JDBC driver '" + className + "': " + t.getMessage());
            return false;
        }
    }

    private enum DriverType {
        AUTO,
        MYSQL,
        MARIADB;

        static DriverType fromConfig(String value) {
            if (value == null) return AUTO;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "mysql", "mysql8", "mysql-connector" -> MYSQL;
                case "mariadb", "maria" -> MARIADB;
                default -> AUTO;
            };
        }
    }

    private record AccessDeniedDetails(String user, String host, boolean passwordProvided) {
        static AccessDeniedDetails parse(String message) {
            if (message == null) {
                return null;
            }
            Matcher matcher = ACCESS_DENIED_PATTERN.matcher(message);
            if (!matcher.find()) {
                return null;
            }
            String user = matcher.group(1);
            String host = matcher.group(2);
            boolean passwordProvided = "YES".equalsIgnoreCase(matcher.group(3));
            return new AccessDeniedDetails(user, host, passwordProvided);
        }
    }

    private record DriverInfo(String displayName,
                              String driverClass,
                              String jdbcScheme,
                              boolean supportsPublicKeyRetrieval,
                              boolean supportsServerTimezone,
                              boolean appendAutoReconnect) {
        private static final DriverInfo MYSQL_CJ = new DriverInfo(
                "MySQL Connector/J 8+",
                "com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://",
                true,
                true,
                true
        );

        private static final DriverInfo MYSQL_LEGACY = new DriverInfo(
                "MySQL Connector/J 5",
                "com.mysql.jdbc.Driver",
                "jdbc:mysql://",
                false,
                false,
                true
        );

        private static final DriverInfo MARIADB = new DriverInfo(
                "MariaDB Java Client",
                "org.mariadb.jdbc.Driver",
                "jdbc:mariadb://",
                false,
                false,
                false
        );
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

    private ZoneId resolveLoginStreakZone() {
        String configured = trimToNull(plugin.getConfig().getString("commands.playerinfo.timezone"));
        String key = configured == null ? SYSTEM_ZONE_KEY : configured;

        ZoneId cached = this.cachedLoginZone;
        String cachedKey = this.cachedLoginZoneKey;
        if (cached != null && key.equals(cachedKey)) {
            return cached;
        }

        ZoneId zone;
        if (configured == null) {
            zone = ZoneId.systemDefault();
        } else {
            try {
                zone = ZoneId.of(configured);
                lastInvalidLoginZone = null;
            } catch (DateTimeException ex) {
                if (lastInvalidLoginZone == null || !lastInvalidLoginZone.equals(configured)) {
                    plugin.getLogger().warning("[DB] Invalid commands.playerinfo.timezone '" + configured + "', using system default for login streak tracking.");
                    lastInvalidLoginZone = configured;
                }
                zone = ZoneId.systemDefault();
                key = SYSTEM_ZONE_KEY;
            }
        }

        this.cachedLoginZone = zone;
        this.cachedLoginZoneKey = key;
        return zone;
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
                ZoneId zone = resolveLoginStreakZone();
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

                cachedPlayerIds.put(uuid, playerId);

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

    private Integer resolvePlayerId(Connection c, UUID uuid) throws SQLException {
        Integer cached = cachedPlayerIds.get(uuid);
        if (cached != null) {
            return cached;
        }
        PlayerProfile profile = loadProfile(c, uuid);
        if (profile != null) {
            cachedPlayerIds.put(uuid, profile.id());
            return profile.id();
        }
        return null;
    }

    public void beginSession(UUID uuid, SessionStart start) {
        if (!enabled) return;
        SessionStart payload = start == null
                ? new SessionStart(null, null, null, null, null, null, false)
                : start;

        try (Connection c = getConnection()) {
            Integer playerId = resolvePlayerId(c, uuid);
            if (playerId == null) {
                return;
            }

            String sql = """
                    INSERT INTO player_sessions(
                        player_id,
                        session_start,
                        join_ping,
                        join_tps,
                        client_version,
                        client_protocol,
                        client_brand,
                        client_mods,
                        is_bedrock
                    ) VALUES(?, NOW(), ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, playerId);
                setInteger(ps, 2, payload.joinPing());
                setDouble(ps, 3, payload.joinTps());
                setString(ps, 4, payload.clientVersion());
                setInteger(ps, 5, payload.clientProtocol());
                setString(ps, 6, payload.clientBrand());
                setString(ps, 7, payload.clientMods());
                ps.setBoolean(8, payload.bedrock());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        activeSessions.put(uuid, rs.getLong(1));
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] beginSession failed: " + ex.getMessage());
        }
    }

    public void completeSession(UUID uuid, SessionCompletion completion) {
        if (!enabled) return;
        Long sessionId = activeSessions.remove(uuid);
        if (sessionId == null) {
            return;
        }

        SessionCompletion data = completion == null
                ? new SessionCompletion(0L, 0L, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false)
                : completion;

        long durationSeconds = Math.max(0L, data.durationMillis() / 1000L);
        long afkSeconds = Math.max(0L, data.afkMillis() / 1000L);

        try (Connection c = getConnection()) {
            String sql = """
                    UPDATE player_sessions
                    SET session_end = NOW(),
                        duration_seconds = ?,
                        afk_seconds = ?,
                        avg_ping = ?,
                        min_ping = ?,
                        max_ping = ?,
                        current_ping = ?,
                        last_tps = ?,
                        resource_pack = COALESCE(?, resource_pack),
                        resource_pack_status = COALESCE(?, resource_pack_status),
                        resource_pack_hash = COALESCE(?, resource_pack_hash),
                        resource_pack_prompt = COALESCE(?, resource_pack_prompt),
                        last_world = COALESCE(?, last_world),
                        last_x = COALESCE(?, last_x),
                        last_y = COALESCE(?, last_y),
                        last_z = COALESCE(?, last_z),
                        last_yaw = COALESCE(?, last_yaw),
                        last_pitch = COALESCE(?, last_pitch),
                        last_death_world = COALESCE(?, last_death_world),
                        last_death_x = COALESCE(?, last_death_x),
                        last_death_y = COALESCE(?, last_death_y),
                        last_death_z = COALESCE(?, last_death_z),
                        last_death_yaw = COALESCE(?, last_death_yaw),
                        last_death_pitch = COALESCE(?, last_death_pitch),
                        last_death_time = COALESCE(?, last_death_time),
                        client_version = COALESCE(?, client_version),
                        client_protocol = COALESCE(?, client_protocol),
                        client_brand = COALESCE(?, client_brand),
                        client_mods = COALESCE(?, client_mods),
                        is_bedrock = ?
                    WHERE id = ?
                    """;

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                setLong(ps, 1, durationSeconds);
                setLong(ps, 2, afkSeconds);
                setInteger(ps, 3, data.averagePing());
                setInteger(ps, 4, data.minPing());
                setInteger(ps, 5, data.maxPing());
                setInteger(ps, 6, data.currentPing());
                setDouble(ps, 7, data.lastTps());
                setString(ps, 8, data.resourcePack());
                setString(ps, 9, data.resourcePackStatus());
                setString(ps, 10, data.resourcePackHash());
                setString(ps, 11, data.resourcePackPrompt());

                SessionLocation lastLoc = data.lastLocation();
                setString(ps, 12, lastLoc == null ? null : lastLoc.world());
                setDouble(ps, 13, lastLoc == null ? null : lastLoc.x());
                setDouble(ps, 14, lastLoc == null ? null : lastLoc.y());
                setDouble(ps, 15, lastLoc == null ? null : lastLoc.z());
                if (lastLoc == null || lastLoc.yaw() == null) {
                    ps.setNull(16, Types.FLOAT);
                } else {
                    ps.setFloat(16, lastLoc.yaw());
                }
                if (lastLoc == null || lastLoc.pitch() == null) {
                    ps.setNull(17, Types.FLOAT);
                } else {
                    ps.setFloat(17, lastLoc.pitch());
                }

                SessionLocation deathLoc = data.lastDeathLocation();
                setString(ps, 18, deathLoc == null ? null : deathLoc.world());
                setDouble(ps, 19, deathLoc == null ? null : deathLoc.x());
                setDouble(ps, 20, deathLoc == null ? null : deathLoc.y());
                setDouble(ps, 21, deathLoc == null ? null : deathLoc.z());
                if (deathLoc == null || deathLoc.yaw() == null) {
                    ps.setNull(22, Types.FLOAT);
                } else {
                    ps.setFloat(22, deathLoc.yaw());
                }
                if (deathLoc == null || deathLoc.pitch() == null) {
                    ps.setNull(23, Types.FLOAT);
                } else {
                    ps.setFloat(23, deathLoc.pitch());
                }
                Long deathTime = data.lastDeathTimeMillis();
                if (deathTime == null || deathTime <= 0L) {
                    ps.setNull(24, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(24, new Timestamp(deathTime));
                }

                setString(ps, 25, data.clientVersion());
                setInteger(ps, 26, data.clientProtocol());
                setString(ps, 27, data.clientBrand());
                setString(ps, 28, data.clientMods());
                ps.setBoolean(29, data.bedrock());
                ps.setLong(30, sessionId);
                ps.executeUpdate();
            }

            if (durationSeconds > 0) {
                Integer playerId = cachedPlayerIds.get(uuid);
                if (playerId != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE players SET total_playtime_seconds = total_playtime_seconds + ? WHERE id = ?")) {
                        ps.setLong(1, durationSeconds);
                        ps.setInt(2, playerId);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] completeSession failed: " + ex.getMessage());
        }
    }

    public SessionInfo loadLatestSession(int playerId) throws SQLException {
        String sql = """
                SELECT id,
                       session_start,
                       session_end,
                       duration_seconds,
                       afk_seconds,
                       join_ping,
                       avg_ping,
                       min_ping,
                       max_ping,
                       current_ping,
                       join_tps,
                       last_tps,
                       client_version,
                       client_protocol,
                       client_brand,
                       client_mods,
                       is_bedrock,
                       resource_pack,
                       resource_pack_status,
                       resource_pack_hash,
                       resource_pack_prompt,
                       last_world,
                       last_x,
                       last_y,
                       last_z,
                       last_yaw,
                       last_pitch,
                       last_death_world,
                       last_death_x,
                       last_death_y,
                       last_death_z,
                       last_death_yaw,
                       last_death_pitch,
                       last_death_time
                FROM player_sessions
                WHERE player_id = ?
                ORDER BY session_start DESC
                LIMIT 1
                """;

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SessionInfo(
                        rs.getLong("id"),
                        rs.getTimestamp("session_start"),
                        rs.getTimestamp("session_end"),
                        readLong(rs, "duration_seconds"),
                        readLong(rs, "afk_seconds"),
                        readInteger(rs, "join_ping"),
                        readInteger(rs, "avg_ping"),
                        readInteger(rs, "min_ping"),
                        readInteger(rs, "max_ping"),
                        readInteger(rs, "current_ping"),
                        readDouble(rs, "join_tps"),
                        readDouble(rs, "last_tps"),
                        rs.getString("client_version"),
                        readInteger(rs, "client_protocol"),
                        rs.getString("client_brand"),
                        rs.getString("client_mods"),
                        rs.getBoolean("is_bedrock"),
                        rs.getString("resource_pack"),
                        rs.getString("resource_pack_status"),
                        rs.getString("resource_pack_hash"),
                        rs.getString("resource_pack_prompt"),
                        mapLocation(rs, "last_world", "last_x", "last_y", "last_z", "last_yaw", "last_pitch"),
                        mapLocation(rs, "last_death_world", "last_death_x", "last_death_y", "last_death_z", "last_death_yaw", "last_death_pitch"),
                        rs.getTimestamp("last_death_time")
                );
            }
        }
    }

    public PlaytimeStats loadPlaytimeStats(int playerId) throws SQLException {
        String sql = """
                SELECT p.total_playtime_seconds,
                       COALESCE(SUM(sessions.seconds), 0)                                          AS sessions_total,
                       COALESCE(SUM(CASE WHEN sessions.session_start >= ? THEN sessions.seconds ELSE 0 END), 0) AS last_30d
                FROM players p
                LEFT JOIN (
                    SELECT player_id,
                           session_start,
                           COALESCE(duration_seconds,
                                    GREATEST(0, TIMESTAMPDIFF(SECOND, session_start, COALESCE(session_end, NOW())))) AS seconds
                    FROM player_sessions
                    WHERE player_id = ?
                ) sessions ON sessions.player_id = p.id
                WHERE p.id = ?
                GROUP BY p.total_playtime_seconds
                """;

        Instant now = Instant.now();
        Timestamp cutoff30d = Timestamp.from(now.minus(Duration.ofDays(30)));

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, cutoff30d);
            ps.setInt(2, playerId);
            ps.setInt(3, playerId);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new PlaytimeStats(0L, 0L);
                }

                long storedTotal = rs.getLong("total_playtime_seconds");
                long derivedTotal = rs.getLong("sessions_total");
                long finalTotal = Math.max(storedTotal, derivedTotal);

                long month = Math.min(finalTotal, rs.getLong("last_30d"));

                if (derivedTotal > storedTotal) {
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE players SET total_playtime_seconds = ? WHERE id = ?")) {
                        upd.setLong(1, derivedTotal);
                        upd.setInt(2, playerId);
                        upd.executeUpdate();
                    }
                }

                return new PlaytimeStats(finalTotal, month);
            }
        }
    }

    public int resolveLoginStreak(PlayerProfile profile, ZoneId zone) throws SQLException {
        if (profile == null) {
            return 0;
        }

        int stored = Math.max(1, profile.loginStreak());
        if (!enabled || profile.lastJoin() == null) {
            return stored;
        }

        final int limit = 90;
        LocalDate today = LocalDate.now(zone);

        String sql = """
                SELECT session_start
                FROM player_sessions
                WHERE player_id = ?
                ORDER BY session_start DESC
                LIMIT ?
                """;

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, profile.id());
            ps.setInt(2, limit);

            List<LocalDate> days = new ArrayList<>();
            Set<LocalDate> seen = new HashSet<>();

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts == null) {
                        continue;
                    }
                    LocalDate day = ts.toInstant().atZone(zone).toLocalDate();
                    if (day.isAfter(today)) {
                        continue;
                    }
                    if (seen.add(day)) {
                        days.add(day);
                    }
                }
            }

            if (days.isEmpty()) {
                return stored;
            }

            days.sort(Comparator.reverseOrder());

            int computed = 0;
            LocalDate expected = days.get(0);
            for (LocalDate day : days) {
                if (computed == 0) {
                    expected = day;
                }

                if (day.equals(expected)) {
                    computed++;
                    expected = expected.minusDays(1);
                } else if (day.isBefore(expected)) {
                    break;
                }
            }

            int result = Math.max(stored, computed);

            if (computed > stored) {
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE players SET login_streak = ? WHERE id = ?")) {
                    upd.setInt(1, result);
                    upd.setInt(2, profile.id());
                    upd.executeUpdate();
                }
            }

            return result;
        }
    }

    public List<SessionMoment> loadSessionMoments(int playerId, int days, int limit) throws SQLException {
        List<SessionMoment> moments = new ArrayList<>();
        if (limit <= 0) {
            return moments;
        }

        Instant cutoffInstant = Instant.now().minus(Duration.ofDays(Math.max(1, days)));
        Timestamp cutoff = Timestamp.from(cutoffInstant);

        String sql = """
                SELECT session_start,
                       COALESCE(duration_seconds,
                                GREATEST(0, TIMESTAMPDIFF(SECOND, session_start, COALESCE(session_end, NOW())))) AS seconds
                FROM player_sessions
                WHERE player_id = ?
                  AND session_start >= ?
                ORDER BY session_start DESC
                LIMIT ?
                """;

        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            ps.setTimestamp(2, cutoff);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    moments.add(new SessionMoment(
                            rs.getTimestamp("session_start"),
                            rs.getLong("seconds")
                    ));
                }
            }
        }

        return moments;
    }

    public PlayerProfile loadPlayerProfile(UUID uuid) throws SQLException {
        try (Connection c = getConnection()) {
            return loadProfile(c, uuid);
        }
    }

    public PlayerProfile loadPlayerProfile(int id) throws SQLException {
        try (Connection c = getConnection()) {
            return loadProfile(c, id);
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
                SELECT id, uuid, name, first_join, last_join, last_quit, last_ip, total_joins, login_streak, total_playtime_seconds
                FROM players
                WHERE uuid = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapProfile(rs);
            }
        }
    }

    private PlayerProfile loadProfile(Connection c, int id) throws SQLException {
        String sql = """
                SELECT id, uuid, name, first_join, last_join, last_quit, last_ip, total_joins, login_streak, total_playtime_seconds
                FROM players
                WHERE id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapProfile(rs);
            }
        }
    }

    private PlayerProfile mapProfile(ResultSet rs) throws SQLException {
        return new PlayerProfile(
                rs.getInt("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getTimestamp("first_join"),
                rs.getTimestamp("last_join"),
                rs.getTimestamp("last_quit"),
                rs.getString("last_ip"),
                rs.getInt("total_joins"),
                rs.getInt("login_streak"),
                rs.getLong("total_playtime_seconds")
        );
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

    private void ensurePlayersSchema(Connection c) {
        try {
            DatabaseMetaData meta = c.getMetaData();
            if (!hasTable(meta, "players")) {
                return;
            }

            boolean hasId = hasColumn(meta, "players", "id");
            boolean hasFirstJoin = hasColumn(meta, "players", "first_join");
            boolean hasLoginStreak = hasColumn(meta, "players", "login_streak");
            boolean hasTotalPlaytime = hasColumn(meta, "players", "total_playtime_seconds");

            if (!hasId) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players DROP PRIMARY KEY");
                } catch (SQLException ignored) {
                }
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD COLUMN id INT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (id)");
                } catch (SQLException ex) {
                    plugin.getLogger().warning("[DB] Could not add player id column: " + ex.getMessage());
                }
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD UNIQUE KEY uq_players_uuid (uuid)");
                } catch (SQLException ignored) {
                }
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

            if (!hasTotalPlaytime) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE players ADD COLUMN total_playtime_seconds BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER login_streak");
                } catch (SQLException ex) {
                    plugin.getLogger().warning("[DB] Could not add total_playtime_seconds column: " + ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] Player schema ensure failed: " + ex.getMessage());
        }
    }


    private void ensurePlayerIpsSchema(Connection c) {
        try {
            DatabaseMetaData meta = c.getMetaData();
            if (!hasTable(meta, "player_ips")) {
                return;
            }

            if (!hasColumn(meta, "player_ips", "uses")) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE player_ips ADD COLUMN uses INT NOT NULL DEFAULT 1 AFTER last_seen");
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] player_ips schema ensure failed: " + ex.getMessage());
        }
    }


    private void ensurePlayerSessionsSchema(Connection c) {
        try {
            DatabaseMetaData meta = c.getMetaData();
            if (!hasTable(meta, "player_sessions")) {
                return;
            }

            addColumnIfMissing(c, meta, "player_sessions", "afk_seconds",
                    "ALTER TABLE player_sessions ADD COLUMN afk_seconds BIGINT UNSIGNED DEFAULT 0 AFTER duration_seconds");
            addColumnIfMissing(c, meta, "player_sessions", "current_ping",
                    "ALTER TABLE player_sessions ADD COLUMN current_ping INT DEFAULT NULL AFTER max_ping");
            addColumnIfMissing(c, meta, "player_sessions", "last_tps",
                    "ALTER TABLE player_sessions ADD COLUMN last_tps DOUBLE DEFAULT NULL AFTER join_tps");
            addColumnIfMissing(c, meta, "player_sessions", "client_version",
                    "ALTER TABLE player_sessions ADD COLUMN client_version VARCHAR(64) DEFAULT NULL AFTER last_tps");
            addColumnIfMissing(c, meta, "player_sessions", "client_protocol",
                    "ALTER TABLE player_sessions ADD COLUMN client_protocol INT DEFAULT NULL AFTER client_version");
            addColumnIfMissing(c, meta, "player_sessions", "client_brand",
                    "ALTER TABLE player_sessions ADD COLUMN client_brand VARCHAR(128) DEFAULT NULL AFTER client_protocol");
            addColumnIfMissing(c, meta, "player_sessions", "client_mods",
                    "ALTER TABLE player_sessions ADD COLUMN client_mods VARCHAR(256) DEFAULT NULL AFTER client_brand");
            addColumnIfMissing(c, meta, "player_sessions", "is_bedrock",
                    "ALTER TABLE player_sessions ADD COLUMN is_bedrock TINYINT(1) NOT NULL DEFAULT 0 AFTER client_mods");
            addColumnIfMissing(c, meta, "player_sessions", "resource_pack",
                    "ALTER TABLE player_sessions ADD COLUMN resource_pack VARCHAR(256) DEFAULT NULL AFTER is_bedrock");
            addColumnIfMissing(c, meta, "player_sessions", "resource_pack_status",
                    "ALTER TABLE player_sessions ADD COLUMN resource_pack_status VARCHAR(64) DEFAULT NULL AFTER resource_pack");
            addColumnIfMissing(c, meta, "player_sessions", "resource_pack_hash",
                    "ALTER TABLE player_sessions ADD COLUMN resource_pack_hash VARCHAR(128) DEFAULT NULL AFTER resource_pack_status");
            addColumnIfMissing(c, meta, "player_sessions", "resource_pack_prompt",
                    "ALTER TABLE player_sessions ADD COLUMN resource_pack_prompt VARCHAR(128) DEFAULT NULL AFTER resource_pack_hash");
            addColumnIfMissing(c, meta, "player_sessions", "last_world",
                    "ALTER TABLE player_sessions ADD COLUMN last_world VARCHAR(64) DEFAULT NULL AFTER resource_pack_prompt");
            addColumnIfMissing(c, meta, "player_sessions", "last_x",
                    "ALTER TABLE player_sessions ADD COLUMN last_x DOUBLE DEFAULT NULL AFTER last_world");
            addColumnIfMissing(c, meta, "player_sessions", "last_y",
                    "ALTER TABLE player_sessions ADD COLUMN last_y DOUBLE DEFAULT NULL AFTER last_x");
            addColumnIfMissing(c, meta, "player_sessions", "last_z",
                    "ALTER TABLE player_sessions ADD COLUMN last_z DOUBLE DEFAULT NULL AFTER last_y");
            addColumnIfMissing(c, meta, "player_sessions", "last_yaw",
                    "ALTER TABLE player_sessions ADD COLUMN last_yaw FLOAT DEFAULT NULL AFTER last_z");
            addColumnIfMissing(c, meta, "player_sessions", "last_pitch",
                    "ALTER TABLE player_sessions ADD COLUMN last_pitch FLOAT DEFAULT NULL AFTER last_yaw");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_world",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_world VARCHAR(64) DEFAULT NULL AFTER last_pitch");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_x",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_x DOUBLE DEFAULT NULL AFTER last_death_world");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_y",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_y DOUBLE DEFAULT NULL AFTER last_death_x");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_z",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_z DOUBLE DEFAULT NULL AFTER last_death_y");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_yaw",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_yaw FLOAT DEFAULT NULL AFTER last_death_z");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_pitch",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_pitch FLOAT DEFAULT NULL AFTER last_death_yaw");
            addColumnIfMissing(c, meta, "player_sessions", "last_death_time",
                    "ALTER TABLE player_sessions ADD COLUMN last_death_time TIMESTAMP NULL DEFAULT NULL AFTER last_death_pitch");
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] player_sessions schema ensure failed: " + ex.getMessage());
        }
    }


    private void ensureTableEngine(Connection c, String table, String desiredEngine) {
        String schema = resolveSchema(c);
        if (schema == null || schema.isBlank()) {
            return;
        }

        final String sql = "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                String engine = rs.getString(1);
                if (engine == null || desiredEngine.equalsIgnoreCase(engine)) {
                    return;
                }

                plugin.getLogger().warning("[DB] Converting " + table + " table engine from " + engine + " to " + desiredEngine + " to support foreign keys.");
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE `" + table + "` ENGINE=" + desiredEngine);
                } catch (SQLException ex) {
                    plugin.getLogger().warning("[DB] Failed to convert " + table + " table to " + desiredEngine + ": " + ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] Could not verify engine for " + table + ": " + ex.getMessage());
        }
    }


    private String resolveSchema(Connection c) {
        try {
            String catalog = c.getCatalog();
            if (catalog != null && !catalog.isBlank()) {
                return catalog;
            }
        } catch (SQLException ignored) {
        }
        if (database != null && !database.isBlank()) {
            return database;
        }
        return null;
    }


    private boolean isForeignKeyCreationFailure(SQLException ex) {
        if (ex == null) {
            return false;
        }
        if (ex.getErrorCode() == 1005 || ex.getErrorCode() == 1215) {
            String message = ex.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("foreign key")) {
                return true;
            }
        }
        String message = ex.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("foreign key constraint");
    }


    private boolean hasTable(DatabaseMetaData meta, String table) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, table, null)) {
            return rs.next();
        }
    }


    private boolean hasColumn(DatabaseMetaData meta, String table, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private void addColumnIfMissing(Connection c, DatabaseMetaData meta, String table, String column, String sql) {
        try {
            if (hasColumn(meta, table, column)) {
                return;
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] Failed to inspect column '" + column + "' on " + table + ": " + ex.getMessage());
            return;
        }

        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] Could not add column '" + column + "' to " + table + ": " + ex.getMessage());
        }
    }

    private void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private void setString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private SessionLocation mapLocation(ResultSet rs, String worldColumn, String xColumn, String yColumn,
                                        String zColumn, String yawColumn, String pitchColumn) throws SQLException {
        String world = rs.getString(worldColumn);
        boolean worldNull = rs.wasNull();
        Double x = rs.getObject(xColumn) != null ? rs.getDouble(xColumn) : null;
        if (rs.wasNull()) x = null;
        Double y = rs.getObject(yColumn) != null ? rs.getDouble(yColumn) : null;
        if (rs.wasNull()) y = null;
        Double z = rs.getObject(zColumn) != null ? rs.getDouble(zColumn) : null;
        if (rs.wasNull()) z = null;
        Float yaw = rs.getObject(yawColumn) != null ? rs.getFloat(yawColumn) : null;
        if (rs.wasNull()) yaw = null;
        Float pitch = rs.getObject(pitchColumn) != null ? rs.getFloat(pitchColumn) : null;
        if (rs.wasNull()) pitch = null;

        if ((worldNull || world == null) && x == null && y == null && z == null) {
            return null;
        }

        return new SessionLocation(world, x, y, z, yaw, pitch);
    }

    private Integer readInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long readLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Double readDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
    private static final class SimpleConnectionPool implements AutoCloseable {

        private final String jdbcUrl;
        private final Properties baseProperties;
        private final int maxSize;
        private final long borrowTimeoutMillis;
        private final Logger logger;

        private final BlockingQueue<PooledConnection> available = new LinkedBlockingQueue<>();
        private final Set<PooledConnection> all = ConcurrentHashMap.newKeySet();
        private final AtomicInteger total = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        private SimpleConnectionPool(String jdbcUrl,
                                     Properties baseProperties,
                                     int maxSize,
                                     long borrowTimeoutMillis,
                                     Logger logger) {
            this.jdbcUrl = jdbcUrl;
            this.baseProperties = baseProperties;
            this.maxSize = Math.max(1, maxSize);
            this.borrowTimeoutMillis = Math.max(100L, borrowTimeoutMillis);
            this.logger = logger;
        }

        private Connection borrow() throws SQLException {
            if (closed.get()) {
                throw new SQLException("Connection pool has been closed");
            }
            while (true) {
                PooledConnection pooled = available.poll();
                if (pooled == null) {
                    if (total.get() < maxSize) {
                        pooled = createNewConnection();
                    } else {
                        try {
                            pooled = available.poll(borrowTimeoutMillis, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("Interrupted while waiting for a database connection", e);
                        }
                        if (pooled == null) {
                            throw new SQLException("Timed out waiting for a database connection from the pool");
                        }
                    }
                }
                if (pooled == null) {
                    continue;
                }
                if (!pooled.acquire()) {
                    discard(pooled);
                    continue;
                }
                return pooled.proxy();
            }
        }

        private PooledConnection createNewConnection() throws SQLException {
            while (true) {
                int current = total.get();
                if (current >= maxSize) {
                    return null;
                }
                if (total.compareAndSet(current, current + 1)) {
                    break;
                }
            }
            try {
                Properties props = cloneProperties(baseProperties);
                Connection delegate = DriverManager.getConnection(jdbcUrl, props);
                PooledConnection pooled = new PooledConnection(delegate);
                all.add(pooled);
                return pooled;
            } catch (SQLException ex) {
                total.decrementAndGet();
                throw ex;
            }
        }

        private Properties cloneProperties(Properties source) {
            Properties copy = new Properties();
            for (Map.Entry<Object, Object> entry : source.entrySet()) {
                copy.put(entry.getKey(), entry.getValue());
            }
            return copy;
        }

        private void returnToPool(PooledConnection pooled) {
            if (closed.get()) {
                pooled.closeSilently();
                return;
            }
            available.offer(pooled);
        }

        private void discard(PooledConnection pooled) {
            pooled.invalidate();
            available.remove(pooled);
            if (!all.remove(pooled)) {
                return;
            }
            pooled.closeSilently();
            total.decrementAndGet();
        }

        private boolean isClosed() {
            return closed.get();
        }

        private int totalConnections() {
            return total.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            available.clear();
            for (PooledConnection pooled : all) {
                pooled.closeSilently();
            }
            all.clear();
        }

        private final class PooledConnection implements InvocationHandler {
            private final Connection delegate;
            private final Connection proxy;
            private final AtomicBoolean inUse = new AtomicBoolean();
            private final AtomicBoolean valid = new AtomicBoolean(true);

            private PooledConnection(Connection delegate) {
                this.delegate = delegate;
                this.proxy = (Connection) Proxy.newProxyInstance(
                        delegate.getClass().getClassLoader(),
                        new Class[] { Connection.class },
                        this
                );
            }

            private Connection proxy() {
                return proxy;
            }

            private boolean acquire() {
                if (!valid.get() || !inUse.compareAndSet(false, true)) {
                    return false;
                }
                try {
                    if (delegate.isClosed()) {
                        inUse.set(false);
                        return false;
                    }
                    if (!delegate.isValid(2)) {
                        inUse.set(false);
                        return false;
                    }
                } catch (SQLException ex) {
                    logger.log(Level.WARNING, "[DB] Connection validation failed", ex);
                    inUse.set(false);
                    return false;
                }
                return true;
            }

            private void release() {
                if (!inUse.compareAndSet(true, false)) {
                    return;
                }
                if (!valid.get()) {
                    discard(this);
                    return;
                }
                returnToPool(this);
            }

            private void closeSilently() {
                try {
                    delegate.close();
                } catch (SQLException ex) {
                    logger.log(Level.FINE, "[DB] Failed to close pooled connection", ex);
                }
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("close".equals(name)) {
                    release();
                    return null;
                }
                if ("isClosed".equals(name)) {
                    if (!inUse.get()) {
                        return true;
                    }
                }
                if (!inUse.get()) {
                    throw new SQLException("Connection already returned to pool");
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException ex) {
                    Throwable cause = ex.getTargetException();
                    if (cause instanceof SQLException sql && shouldDiscard(sql)) {
                        valid.set(false);
                        inUse.set(false);
                        discard(this);
                    }
                    throw cause;
                }
            }

            private boolean shouldDiscard(SQLException ex) {
                return ex.getSQLState() != null && ex.getSQLState().startsWith("08");
            }

            private void invalidate() {
                valid.set(false);
                inUse.set(false);
            }
        }
    }

    public record SessionLocation(String world,
                                  Double x,
                                  Double y,
                                  Double z,
                                  Float yaw,
                                  Float pitch) {
    }

    public record SessionInfo(long id,
                              Timestamp sessionStart,
                              Timestamp sessionEnd,
                              Long durationSeconds,
                              Long afkSeconds,
                              Integer joinPing,
                              Integer averagePing,
                              Integer minPing,
                              Integer maxPing,
                              Integer currentPing,
                              Double joinTps,
                              Double lastTps,
                              String clientVersion,
                              Integer clientProtocol,
                              String clientBrand,
                              String clientMods,
                              boolean bedrock,
                              String resourcePack,
                              String resourcePackStatus,
                              String resourcePackHash,
                              String resourcePackPrompt,
                              SessionLocation lastLocation,
                              SessionLocation lastDeathLocation,
                              Timestamp lastDeathTime) {
    }

    public record PlaytimeStats(long totalSeconds,
                                long last30dSeconds) {
    }

    public record SessionMoment(Timestamp sessionStart, long durationSeconds) {
    }

    public record SessionStart(Integer joinPing,
                               Double joinTps,
                               String clientVersion,
                               Integer clientProtocol,
                               String clientBrand,
                               String clientMods,
                               boolean bedrock) {
    }

    public record SessionCompletion(long durationMillis,
                                    long afkMillis,
                                    Integer averagePing,
                                    Integer minPing,
                                    Integer maxPing,
                                    Integer currentPing,
                                    Double lastTps,
                                    SessionLocation lastLocation,
                                    SessionLocation lastDeathLocation,
                                    Long lastDeathTimeMillis,
                                    String resourcePack,
                                    String resourcePackStatus,
                                    String resourcePackHash,
                                    String resourcePackPrompt,
                                    String clientVersion,
                                    Integer clientProtocol,
                                    String clientBrand,
                                    String clientMods,
                                    boolean bedrock) {
    }

    public record PlayerProfile(int id,
                                UUID uuid,
                                String name,
                                Timestamp firstJoin,
                                Timestamp lastJoin,
                                Timestamp lastQuit,
                                String lastIp,
                                int totalJoins,
                                int loginStreak,
                                long totalPlaytimeSeconds) {
    }

    public record IpHistoryEntry(String ip, Timestamp firstSeen, Timestamp lastSeen, int uses) {
    }

    public record AltAccountMatch(int id, UUID uuid, String name, String ips, Timestamp lastSeen) {
    }
}
