package net.lyzrex.syntrix.lobby.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.PlayerSessionService;
import net.lyzrex.syntrix.lobby.db.DBManager;
import net.lyzrex.syntrix.lobby.db.DBManager.AltAccountMatch;
import net.lyzrex.syntrix.lobby.db.DBManager.IpHistoryEntry;
import net.lyzrex.syntrix.lobby.db.DBManager.PlayerProfile;
import net.lyzrex.syntrix.lobby.db.DBManager.PlaytimeStats;
import net.lyzrex.syntrix.lobby.db.DBManager.SessionInfo;
import net.lyzrex.syntrix.lobby.db.DBManager.SessionMoment;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PlayerInfoCommand extends BaseCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN_SERIALIZER = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY_SECTION_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private String lastInvalidPattern;
    private String lastInvalidZone;

    public PlayerInfoCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        String basePerm = plugin.getConfig().getString("commands.playerinfo.permission", "syntrix.playerinfo");
        if (basePerm != null && !basePerm.isBlank() && !sender.hasPermission(basePerm)) {
            MessageUtil.send(sender, plugin, "errors.no-permission", "<#FF4D4F>You do not have permission to do this.</#FF4D4F>");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, "commands.playerinfo.usage", "<#8799ae>Usage:</#8799ae> <#FFFFFF>/playerinfo playerName</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo #123</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo id 123</#FFFFFF>");
            return true;
        }
        boolean ipSub = args[0].equalsIgnoreCase("ip");
        boolean idAlias = false;
        String query;
        if (ipSub) {
            if (args.length < 2) {
                sendUsage(sender, "commands.playerinfo.ip-usage", "<#8799ae>Usage:</#8799ae> <#FFFFFF>/playerinfo ip playerName</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo ip #123</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo ip id 123</#FFFFFF>");
                return true;
            }
            String ipPerm = plugin.getConfig().getString("commands.playerinfo.ip-history-permission", basePerm);
            if (ipPerm != null && !ipPerm.isBlank() && !sender.hasPermission(ipPerm)) {
                MessageUtil.send(sender, plugin, "errors.no-permission", "<#FF4D4F>You do not have permission to do this.</#FF4D4F>");
                return true;
            }
            query = args[1];
            if (query.equalsIgnoreCase("id")) {
                idAlias = true;
                if (args.length < 3) {
                    sendUsage(sender, "commands.playerinfo.ip-usage", "<#8799ae>Usage:</#8799ae> <#FFFFFF>/playerinfo ip playerName</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo ip #123</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo ip id 123</#FFFFFF>");
                    return true;
                }
                query = args[2];
            }
        } else {
            query = args[0];
            if (query.equalsIgnoreCase("id")) {
                idAlias = true;
                if (args.length < 2) {
                    sendUsage(sender, "commands.playerinfo.usage", "<#8799ae>Usage:</#8799ae> <#FFFFFF>/playerinfo playerName</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo #123</#FFFFFF> <#8799ae>or</#8799ae> <#FFFFFF>/playerinfo id 123</#FFFFFF>");
                    return true;
                }
                query = args[1];
            }
        }

        Integer playerId = parsePlayerId(query);
        if (idAlias && playerId == null) {
            MessageUtil.sendRaw(sender, plugin,
                    plugin.messages().getString("commands.playerinfo.invalid-id",
                            "<#FF4D4F>Please provide a numeric player ID (for example 12 or #12).</#FF4D4F>"));
            return true;
        }

        if (plugin.db() == null || !plugin.db().isEnabled()) {

            MessageUtil.sendRaw(sender, plugin,
                    plugin.messages().getString("commands.playerinfo.database-disabled",
                            "<#FF4D4F>The database system is disabled.</#FF4D4F>"));
            return true;
        }
        try {
            OfflinePlayer target;
            PlayerProfile profile;
            UUID uuid;

            if (playerId != null) {
                profile = plugin.db().loadPlayerProfile(playerId);
                if (profile == null) {
                    MessageUtil.sendRaw(sender, plugin,
                            plugin.messages().getString("commands.playerinfo.not-found",
                                    "<#FF4D4F>No database record for that player.</#FF4D4F>"));
                    return true;
                }
                uuid = profile.uuid();
                if (uuid == null) {
                    MessageUtil.send(sender, plugin, "errors.not-found", "<#FF4D4F>Command or player not found.</#FF4D4F>");
                    return true;
                }
                target = Bukkit.getOfflinePlayer(uuid);
            } else {
                target = Bukkit.getOfflinePlayer(query);
                uuid = target.getUniqueId();
                if (uuid == null) {
                    MessageUtil.send(sender, plugin, "errors.not-found", "<#FF4D4F>Command or player not found.</#FF4D4F>");
                    return true;
                }
                profile = plugin.db().loadPlayerProfile(uuid);
                if (profile == null) {
                    MessageUtil.sendRaw(sender, plugin,
                            plugin.messages().getString("commands.playerinfo.not-found",
                                    "<#FF4D4F>No database record for that player.</#FF4D4F>"));
                    return true;
                }
            }

            if (ipSub) {
                handleIpHistory(sender, target, profile);
            } else {
                handleOverview(sender, target, profile);
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[DB] playerinfo failed: " + ex.getMessage());
            MessageUtil.sendRaw(sender, plugin,
                    plugin.messages().getString("commands.playerinfo.error",
                            "<#FF4D4F>Database error while fetching player info.</#FF4D4F>"));
        }

        return true;
    }

    private void handleOverview(CommandSender sender, OfflinePlayer target, PlayerProfile profile) throws SQLException {
        Player online = Bukkit.getPlayer(profile.uuid());
        boolean isOnline = online != null && online.isOnline();

        DateTimeFormatter formatter = resolveFormatter();
        ZoneId zone = resolveZone();
        Locale locale = Locale.getDefault();
        String na = plugin.messages().getString("commands.playerinfo.not-available", "<#8799ae>N/A</#8799ae>");

        PlayerSessionService.SessionSnapshot liveSnapshot = plugin.sessions() != null
                ? plugin.sessions().snapshot(profile.uuid())
                : null;
        SessionInfo latestSession = plugin.db().loadLatestSession(profile.id());
        PlaytimeStats playtimeStats = plugin.db().loadPlaytimeStats(profile.id());
        int loginStreak = plugin.db().resolveLoginStreak(profile, zone);

        int heatmapDays = Math.max(1, plugin.getConfig().getInt("commands.playerinfo.heatmap.days", 30));
        int heatmapSummaryCount = Math.max(1, plugin.getConfig().getInt("commands.playerinfo.heatmap.summary-count", 4));
        int heatmapLimit = Math.max(10, plugin.getConfig().getInt("commands.playerinfo.heatmap.max-rows", 256));
        int heatmapDetailPerDay = Math.max(1, plugin.getConfig().getInt("commands.playerinfo.heatmap.detail-per-day", 3));
        List<DBManager.SessionMoment> moments = plugin.db().loadSessionMoments(profile.id(), heatmapDays, heatmapLimit);

        long currentSessionSeconds = liveSnapshot != null ? liveSnapshot.durationMillis() / 1000L : 0L;
        long totalSeconds = playtimeStats.totalSeconds() + currentSessionSeconds;
        long last30dSeconds = playtimeStats.last30dSeconds();

        long sessionDurationMillis = liveSnapshot != null
                ? liveSnapshot.durationMillis()
                : (latestSession != null && latestSession.durationSeconds() != null
                ? latestSession.durationSeconds() * 1000L : -1L);
        long sessionAfkMillis = liveSnapshot != null
                ? liveSnapshot.afkMillis()
                : (latestSession != null && latestSession.afkSeconds() != null
                ? latestSession.afkSeconds() * 1000L : -1L);

        Integer currentPing = liveSnapshot != null ? liveSnapshot.currentPing() : null;
        Integer averagePing = liveSnapshot != null ? liveSnapshot.averagePing()
                : latestSession != null ? latestSession.averagePing() : null;
        Integer minPing = liveSnapshot != null ? liveSnapshot.minPing()
                : latestSession != null ? latestSession.minPing() : null;
        Integer maxPing = liveSnapshot != null ? liveSnapshot.maxPing()
                : latestSession != null ? latestSession.maxPing() : null;
        Integer joinPing = liveSnapshot != null ? liveSnapshot.joinPing()
                : latestSession != null ? latestSession.joinPing() : null;
        Double joinTps = liveSnapshot != null ? liveSnapshot.joinTps()
                : latestSession != null ? latestSession.joinTps() : null;
        Double lastTps = liveSnapshot != null ? liveSnapshot.lastTps()
                : latestSession != null ? latestSession.lastTps() : null;

        String clientVersion = liveSnapshot != null ? liveSnapshot.clientVersion()
                : latestSession != null ? latestSession.clientVersion() : null;
        Integer clientProtocol = liveSnapshot != null ? liveSnapshot.clientProtocol()
                : latestSession != null ? latestSession.clientProtocol() : null;
        String clientBrand = liveSnapshot != null ? liveSnapshot.clientBrand()
                : latestSession != null ? latestSession.clientBrand() : null;
        String clientMods = liveSnapshot != null ? liveSnapshot.clientMods()
                : latestSession != null ? latestSession.clientMods() : null;
        boolean bedrock = liveSnapshot != null ? liveSnapshot.bedrock()
                : latestSession != null && latestSession.bedrock();


        HeatmapSummary heatmap = buildHeatmap(moments, zone, locale, heatmapSummaryCount, heatmapDetailPerDay, na);

        String displayName = profile.name();
        if (displayName == null || displayName.isBlank()) {
            displayName = target.getName() != null ? target.getName() : profile.uuid().toString();
        }

        Map<String, String> base = new HashMap<>();
        base.put("player", displayName);
        base.put("name", displayName);
        base.put("id", Integer.toString(profile.id()));
        base.put("uuid", profile.uuid().toString());
        base.put("first_join", formatTimestamp(profile.firstJoin(), formatter, zone, na));
        base.put("last_join", formatTimestamp(profile.lastJoin(), formatter, zone, na));
        base.put("last_quit", formatTimestamp(profile.lastQuit(), formatter, zone, na));
        base.put("login_streak", Integer.toString(loginStreak));
        base.put("total_joins", Integer.toString(profile.totalJoins()));
        base.put("last_ip", safeValue(profile.lastIp(), na));
        base.put("playtime_total", formatDuration(totalSeconds, na));
        base.put("playtime_30d", formatDuration(last30dSeconds, na));
        base.put("session_duration", formatDurationMillis(sessionDurationMillis, na));
        base.put("session_afk", formatDurationMillis(sessionAfkMillis, na));
        base.put("session_ping_current", formatPing(currentPing, na));
        base.put("session_ping_average", formatPing(averagePing, na));
        base.put("session_ping_min", formatPing(minPing, na));
        base.put("session_ping_max", formatPing(maxPing, na));
        base.put("session_join_ping", formatPing(joinPing, na));
        base.put("session_join_tps", formatTps(joinTps, na));
        base.put("session_last_tps", formatTps(lastTps, na));
        base.put("client_version", safeValue(clientVersion, na));
        base.put("client_protocol", clientProtocol != null ? clientProtocol.toString() : na);
        base.put("client_brand", safeValue(clientBrand, na));
        base.put("client_mods", safeValue(clientMods, na));
        base.put("client_platform", bedrock
                ? plugin.messages().getString("commands.playerinfo.platform.bedrock", "<#FFA94D>Bedrock</#FFA94D>")
                : plugin.messages().getString("commands.playerinfo.platform.java", "<#2AF598>Java</#2AF598>"));
        base.put("heatmap_summary", heatmap.summary());
        base.put("heatmap_details", heatmap.details());
        base.put("heatmap_days", Integer.toString(heatmapDays));

        String statusKey = isOnline ? "commands.playerinfo.status.online" : "commands.playerinfo.status.offline";
        String statusDefault = isOnline ? "<#2AF598>online</#2AF598>" : "<#FF4D4F>offline</#FF4D4F>";
        base.put("status", plugin.messages().getString(statusKey, statusDefault));

        List<String> lines = new ArrayList<>();
        addIfPresent(lines, "commands.playerinfo.header",
                "<gradient:#2AF598:#009EFD>Syntrix</gradient> <#8799ae>• <#FFFFFF>{player}</#FFFFFF> <#8799ae>(ID #{id})</#8799ae>",
                base);
        addDivider(lines, base);

        addIfPresent(lines, "commands.playerinfo.sections.profile.title",
                "<#F6C35D><bold>Profil</bold></#F6C35D>", base);
        addIfPresent(lines, "commands.playerinfo.sections.profile.name",
                "  <#8799ae>Name:</#8799ae> <#FFFFFF>{player}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.profile.uuid",
                "  <#8799ae>UUID:</#8799ae> <#FFFFFF>{uuid}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.profile.status",
                "  <#8799ae>Status:</#8799ae> {status}", base);
        addIfPresent(lines, "commands.playerinfo.sections.profile.last-ip",
                "  <#8799ae>Letzte IP:</#8799ae> <#FFFFFF>{last_ip}</#FFFFFF>", base);

        addDivider(lines, base);
        addIfPresent(lines, "commands.playerinfo.sections.activity.title",
                "<#F6C35D><bold>Aktivität</bold></#F6C35D>", base);
        addIfPresent(lines, "commands.playerinfo.sections.activity.first-join",
                "  <#8799ae>Erster Login:</#8799ae> <#FFFFFF>{first_join}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.activity.last-join",
                "  <#8799ae>Letzter Login:</#8799ae> <#FFFFFF>{last_join}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.activity.last-quit",
                "  <#8799ae>Logout:</#8799ae> <#FFFFFF>{last_quit}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.activity.login-streak",
                "  <#8799ae>Streak:</#8799ae> <#FFFFFF>{login_streak}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.activity.total-joins",
                "  <#8799ae>Logins gesamt:</#8799ae> <#FFFFFF>{total_joins}</#FFFFFF>", base);

        addDivider(lines, base);
        addIfPresent(lines, "commands.playerinfo.sections.playtime.title",
                "<#F6C35D><bold>Spielzeit</bold></#F6C35D>", base);
        addIfPresent(lines, "commands.playerinfo.sections.playtime.total",
                "  <#8799ae>Gesamt:</#8799ae> <#FFFFFF>{playtime_total}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.playtime.recent",
                "  <#8799ae>Letzte 30 Tage:</#8799ae> <#FFFFFF>{playtime_30d}</#FFFFFF>",
                base);
        addIfPresent(lines, "commands.playerinfo.sections.playtime.session",
                "  <#8799ae>Aktuelle Session:</#8799ae> <#FFFFFF>{session_duration}</#FFFFFF> <#8799ae>(AFK {session_afk})</#8799ae>",
                base);

        addDivider(lines, base);
        addIfPresent(lines, "commands.playerinfo.sections.connection.title",
                "<#F6C35D><bold>Verbindung</bold></#F6C35D>", base);
        addIfPresent(lines, "commands.playerinfo.sections.connection.ping",
                "  <#8799ae>Ping:</#8799ae> <#FFFFFF>{session_ping_current}</#FFFFFF> <#8799ae>(Ø {session_ping_average} • Min {session_ping_min} • Max {session_ping_max})</#8799ae>",
                base);
        addIfPresent(lines, "commands.playerinfo.sections.connection.tps",
                "  <#8799ae>Server:</#8799ae> <#FFFFFF>{session_last_tps}</#FFFFFF> <#8799ae>(Join TPS {session_join_tps} • Join Ping {session_join_ping})</#8799ae>",
                base);
        addIfPresent(lines, "commands.playerinfo.sections.connection.client",
                "  <#8799ae>Client:</#8799ae> <#FFFFFF>{client_version}</#FFFFFF> <#8799ae>(Protocol {client_protocol} • {client_platform})</#8799ae>",
                base);
        addIfPresent(lines, "commands.playerinfo.sections.connection.brand",
                "  <#8799ae>Brand:</#8799ae> <#FFFFFF>{client_brand}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.connection.mods",
                "  <#8799ae>Mods:</#8799ae> <#FFFFFF>{client_mods}</#FFFFFF>", base);

        addIfPresent(lines, "commands.playerinfo.sections.extras.title",
                "<#F6C35D><bold>Extras</bold></#F6C35D>", base);
        addIfPresent(lines, "commands.playerinfo.sections.extras.heatmap",
                "  <#8799ae>Login-Zeiten:</#8799ae> <#FFFFFF>{heatmap_summary}</#FFFFFF>", base);
        addIfPresent(lines, "commands.playerinfo.sections.extras.heatmap-detail",
                "  <#8799ae>Details:</#8799ae> <#FFFFFF>{heatmap_details}</#FFFFFF>", base);

        int altLimit = plugin.getConfig().getInt("commands.playerinfo.max-alt-results", 5);
        if (altLimit > 0) {
            addDivider(lines, base);
            List<AltAccountMatch> alts = plugin.db().findPotentialAlts(profile.id(), altLimit);
            addIfPresent(lines, "commands.playerinfo.alts.header",
                    "<#F6C35D><bold>Verdächtige Accounts</bold></#F6C35D>", base);
            if (!alts.isEmpty()) {
                for (AltAccountMatch alt : alts) {
                    Map<String, String> altMap = new HashMap<>(base);
                    altMap.put("name", alt.name());
                    altMap.put("player", alt.name());
                    altMap.put("uuid", alt.uuid().toString());
                    altMap.put("ips", safeValue(alt.ips(), na));
                    altMap.put("last_seen", formatTimestamp(alt.lastSeen(), formatter, zone, na));
                    addIfPresent(lines, "commands.playerinfo.alts.entry",
                            "  <#FFFFFF>{name}</#FFFFFF> <#8799ae>({ips} • {last_seen})</#8799ae>", altMap);
                }
            } else {
                addIfPresent(lines, "commands.playerinfo.alts.none",
                        "  <#8799ae>Keine Übereinstimmungen gefunden.</#8799ae>", base);
            }
        }

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            MessageUtil.sendRaw(sender, plugin, line);
        }
    }

    private void handleIpHistory(CommandSender sender, OfflinePlayer target, PlayerProfile profile) throws SQLException {
        int limit = plugin.getConfig().getInt("commands.playerinfo.ip-history-limit", 20);
        DateTimeFormatter formatter = resolveFormatter();
        ZoneId zone = resolveZone();
        String na = plugin.messages().getString("commands.playerinfo.not-available", "<#8799ae>N/A</#8799ae>");

        String displayName = profile.name();
        if (displayName == null || displayName.isBlank()) {
            displayName = target.getName() != null ? target.getName() : profile.uuid().toString();
        }

        Map<String, String> base = new HashMap<>();
        base.put("player", displayName);
        base.put("name", displayName);
        List<String> lines = new ArrayList<>();
        addIfPresent(lines, "commands.playerinfo.ip-history.header",
                "<gradient:#2AF598:#009EFD>Syntrix</gradient> <#8799ae>• IP-Historie für <#FFFFFF>{player}</#FFFFFF>:</#8799ae>", base);

        List<IpHistoryEntry> history = plugin.db().loadIpHistory(profile.id(), limit);
        if (history.isEmpty()) {
            addIfPresent(lines, "commands.playerinfo.ip-history.none",
                    "  <#8799ae>Keine gespeicherten IP-Adressen.</#8799ae>", base);
        } else {
            for (IpHistoryEntry entry : history) {
                Map<String, String> map = new HashMap<>(base);
                map.put("ip", safeValue(entry.ip(), na));
                map.put("first_seen", formatTimestamp(entry.firstSeen(), formatter, zone, na));
                map.put("last_seen", formatTimestamp(entry.lastSeen(), formatter, zone, na));
                map.put("uses", Integer.toString(Math.max(1, entry.uses())));
                addIfPresent(lines, "commands.playerinfo.ip-history.entry",
                        "  <#FFFFFF>{ip}</#FFFFFF> <#8799ae>({first_seen} → {last_seen}, {uses} Logins)</#8799ae>", map);
            }
        }

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            MessageUtil.sendRaw(sender, plugin, line);
        }
    }

    private Integer parsePlayerId(String input) {
        if (input == null) {
            return null;
        }
        String candidate = input.trim();
        if (candidate.startsWith("#")) {
            candidate = candidate.substring(1);
        }
        if (candidate.isEmpty()) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (!Character.isDigit(candidate.charAt(i))) {
                return null;
            }
        }
        try {
            int value = Integer.parseInt(candidate);
            return value < 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void sendUsage(CommandSender sender, String path, String def) {
        MessageUtil.sendRaw(sender, plugin, plugin.messages().getString(path, def));
    }

    private void addIfPresent(List<String> lines, String path, String fallback, Map<String, String> replacements) {
        String template = plugin.messages().getString(path, fallback);
        if (template == null || template.isBlank()) return;
        lines.add(applyPlaceholders(template, replacements));
    }

    private void addDivider(List<String> lines, Map<String, String> replacements) {
        String template = plugin.messages().getString(
                "commands.playerinfo.section-divider",
                "<#2A3344>────────────────────────────────────</#2A3344>"
        );
        if (template == null || template.isBlank()) {
            return;
        }
        lines.add(applyPlaceholders(template, replacements));
    }

    private String applyPlaceholders(String template, Map<String, String> replacements) {
        String result = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String value = entry.getValue();
            if (value == null) value = "";
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }

    private String formatTimestamp(Timestamp ts, DateTimeFormatter formatter, ZoneId zone, String na) {
        if (ts == null) return na;
        return formatter.format(ts.toInstant().atZone(zone));
    }

    private String safeValue(String value, String na) {
        if (value == null || value.isBlank()) return na;
        String sanitized = sanitizeForMiniMessage(value.trim());
        if (sanitized.isBlank()) return na;
        return sanitized;
    }

    private String sanitizeForMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String stripped = stripLegacyFormatting(input);
        if (stripped.isEmpty()) {
            return "";
        }
        return MINI_MESSAGE.serialize(Component.text(stripped));
    }

    private String stripLegacyFormatting(String input) {
        String result = input;
        if (containsLegacyCode(result, '\u00A7')) {
            result = deserializeLegacy(result, LEGACY_SECTION_SERIALIZER, '\u00A7');
        }
        if (containsLegacyCode(result, '&')) {
            result = deserializeLegacy(result, LEGACY_AMPERSAND_SERIALIZER, '&');
        }
        return result;
    }

    private String deserializeLegacy(String value, LegacyComponentSerializer serializer, char indicator) {
        try {
            return PLAIN_SERIALIZER.serialize(serializer.deserialize(value));
        } catch (Exception ex) {
            return value.replace(String.valueOf(indicator), "");
        }
    }

    private boolean containsLegacyCode(String value, char indicator) {
        for (int i = 0; i < value.length() - 1; i++) {
            if (value.charAt(i) != indicator) {
                continue;
            }
            char next = value.charAt(i + 1);
            if (isLegacyCodeChar(next)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLegacyCodeChar(char c) {
        return "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(c) >= 0;
    }

    private String formatDuration(long seconds, String na) {
        if (seconds < 0) return na;
        long remaining = seconds;
        long days = remaining / 86_400;
        remaining %= 86_400;
        long hours = remaining / 3_600;
        remaining %= 3_600;
        long minutes = remaining / 60;
        long secs = remaining % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('d').append(' ');
        if (hours > 0 || days > 0) sb.append(hours).append('h').append(' ');
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append('m').append(' ');
        sb.append(secs).append('s');
        return sb.toString().trim();
    }

    private String formatDurationMillis(long millis, String na) {
        if (millis < 0) return na;
        return formatDuration(Math.round(millis / 1000.0), na);
    }

    private String formatPing(Integer ping, String na) {
        if (ping == null || ping < 0) return na;
        return ping + " ms";
    }

    private String formatTps(Double tps, String na) {
        if (tps == null || tps.isNaN() || tps <= 0.0D) return na;
        return String.format(Locale.US, "%.1f", tps);
    }

    private HeatmapSummary buildHeatmap(List<DBManager.SessionMoment> moments,
                                        ZoneId zone,
                                        Locale locale,
                                        int summaryCount,
                                        int detailPerDay,
                                        String na) {
        if (moments == null || moments.isEmpty()) {
            String noneDetail = plugin.messages().getString("commands.playerinfo.heatmap.none-detail",
                    "No recent login data.");
            return new HeatmapSummary(na, noneDetail);
        }

        EnumMap<DayOfWeek, int[]> totals = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            totals.put(day, new int[6]);
        }

        for (DBManager.SessionMoment moment : moments) {
            if (moment.sessionStart() == null) continue;
            ZonedDateTime time = moment.sessionStart().toInstant().atZone(zone);
            DayOfWeek day = time.getDayOfWeek();
            int bucket = Math.min(5, Math.max(0, time.getHour() / 4));
            long duration = Math.max(1L, moment.durationSeconds());
            int weight = (int) Math.max(1L, duration / 900L);
            totals.get(day)[bucket] += weight;
        }

        List<HeatmapEntry> entries = new ArrayList<>();
        for (Map.Entry<DayOfWeek, int[]> entry : totals.entrySet()) {
            int[] buckets = entry.getValue();
            for (int i = 0; i < buckets.length; i++) {
                if (buckets[i] <= 0) continue;
                entries.add(new HeatmapEntry(entry.getKey(), i, buckets[i]));
            }
        }

        entries.sort(Comparator.comparingInt(HeatmapEntry::count).reversed());
        List<String> summaryParts = new ArrayList<>();
        for (int i = 0; i < Math.min(summaryCount, entries.size()); i++) {
            HeatmapEntry entry = entries.get(i);
            summaryParts.add(formatHeatmapLabel(entry.day(), entry.bucket(), entry.count(), locale));
        }
        String summary = summaryParts.isEmpty() ? na : String.join(" <#8799ae>|</#8799ae> ", summaryParts);

        List<String> detailLines = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            int[] buckets = totals.get(day);
            List<HeatmapEntry> perDay = new ArrayList<>();
            for (int i = 0; i < buckets.length; i++) {
                if (buckets[i] <= 0) continue;
                perDay.add(new HeatmapEntry(day, i, buckets[i]));
            }
            if (perDay.isEmpty()) continue;
            perDay.sort(Comparator.comparingInt(HeatmapEntry::count).reversed());
            if (perDay.size() > detailPerDay) {
                perDay = perDay.subList(0, detailPerDay);
            }
            String segments = perDay.stream()
                    .map(entry -> formatBucketRange(entry.bucket()) + "=" + entry.count())
                    .collect(Collectors.joining(", "));
            detailLines.add(day.getDisplayName(TextStyle.SHORT, locale) + " " + segments);
        }

        String details = detailLines.isEmpty() ? na : String.join(" • ", detailLines);
        return new HeatmapSummary(summary, details);
    }

    private String formatHeatmapLabel(DayOfWeek day, int bucket, int count, Locale locale) {
        return day.getDisplayName(TextStyle.SHORT, locale) + " " + formatBucketRange(bucket) + " (" + count + ")";
    }

    private String formatBucketRange(int bucket) {
        int start = bucket * 4;
        int end = start + 4;
        return String.format(Locale.US, "%02d-%02d", start, end);
    }

    private DateTimeFormatter resolveFormatter() {
        final String fallbackPattern = "dd.MM.yyyy HH:mm:ss";
        String pattern = plugin.getConfig().getString("commands.playerinfo.time-format");
        if (pattern == null || pattern.isBlank()) {
            pattern = fallbackPattern;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault());
            lastInvalidPattern = null;
            return formatter;
        } catch (IllegalArgumentException ex) {
            if (lastInvalidPattern == null || !lastInvalidPattern.equals(pattern)) {
                plugin.getLogger().warning("Invalid commands.playerinfo.time-format '" + pattern + "', using " + fallbackPattern + ".");
                lastInvalidPattern = pattern;
            }
            return DateTimeFormatter.ofPattern(fallbackPattern, Locale.getDefault());
        }
    }

    private ZoneId resolveZone() {
        String zoneId = plugin.getConfig().getString("commands.playerinfo.timezone");
        if (zoneId == null || zoneId.isBlank()) {
            lastInvalidZone = null;
            return ZoneId.systemDefault();
        }
        try {
            ZoneId zone = ZoneId.of(zoneId);
            lastInvalidZone = null;
            return zone;
        } catch (DateTimeException ex) {
            if (lastInvalidZone == null || !lastInvalidZone.equals(zoneId)) {
                plugin.getLogger().warning("Invalid commands.playerinfo.timezone '" + zoneId + "', using system default.");
                lastInvalidZone = zoneId;
            }
            return ZoneId.systemDefault();
        }
    }

    private record HeatmapSummary(String summary, String details) {
    }

    private record HeatmapEntry(DayOfWeek day, int bucket, int count) {
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            Set<String> suggestions = new LinkedHashSet<>();
            if ("ip".startsWith(start)) suggestions.add("ip");
            for (Player player : Bukkit.getOnlinePlayers()) {
                String name = player.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(start)) {
                    suggestions.add(name);
                }
            }
            return new ArrayList<>(suggestions);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("ip")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                String name = player.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(start)) {
                    suggestions.add(name);
                }
            }
            return suggestions;
        }

        return List.of();
    }
}