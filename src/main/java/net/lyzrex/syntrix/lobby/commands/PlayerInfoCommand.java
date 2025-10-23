package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.db.DBManager.AltAccountMatch;
import net.lyzrex.syntrix.lobby.db.DBManager.IpHistoryEntry;
import net.lyzrex.syntrix.lobby.db.DBManager.PlayerProfile;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerInfoCommand extends BaseCommand {

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
                            sendUsage(sender, "commands.playerinfo.usage", "<#8799ae>Usage:</#8799ae> <#FFFFFF>/playerinfo <player></#FFFFFF>");
                            return true;
                        }


                        boolean ipSub = args[0].equalsIgnoreCase("ip");
                        String targetName;
                        if (ipSub) {
                            if (args.length < 2) {
                                sendUsage(sender, "commands.playerinfo.ip-usage", "<#8799ae>Usage:</#8799ae> <#FFFFFF>/playerinfo ip <player></#FFFFFF>");
                                return true;
                            }
                            String ipPerm = plugin.getConfig().getString("commands.playerinfo.ip-history-permission", basePerm);
                            if (ipPerm != null && !ipPerm.isBlank() && !sender.hasPermission(ipPerm)) {
                                MessageUtil.send(sender, plugin, "errors.no-permission", "<#FF4D4F>You do not have permission to do this.</#FF4D4F>");
                                return true;
                            }
                            targetName = args[1];
                        } else {
                            targetName = args[0];
                        }

                        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                        UUID uuid = target.getUniqueId();
                        if (uuid == null) {
                            MessageUtil.send(sender, plugin, "errors.not-found", "<#FF4D4F>Command or player not found.</#FF4D4F>");
                            return true;
                        }

                        if (plugin.db() == null || !plugin.db().isEnabled()) {
                            MessageUtil.sendRaw(sender, plugin,
                                    plugin.messages().getString("commands.playerinfo.database-disabled",
                                            "<#FF4D4F>The database system is disabled.</#FF4D4F>"));
                            return true;
                        }


                                try {
                                    PlayerProfile profile = plugin.db().loadPlayerProfile(uuid);
                                    if (profile == null) {
                                        MessageUtil.sendRaw(sender, plugin,
                                                plugin.messages().getString("commands.playerinfo.not-found",
                                                        "<#FF4D4F>No database record for that player.</#FF4D4F>"));
                                        return true;
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
                                boolean isOnline = false;
                                Player online = Bukkit.getPlayer(profile.uuid());
                                if (online != null) {
                                    isOnline = online.isOnline();
                                } else if (target instanceof Player player) {
                                    isOnline = player.isOnline();
                                }

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
                                base.put("id", Integer.toString(profile.id()));
                                base.put("uuid", profile.uuid().toString());
                                base.put("first_join", formatTimestamp(profile.firstJoin(), formatter, zone, na));
                                base.put("last_join", formatTimestamp(profile.lastJoin(), formatter, zone, na));
                                base.put("last_quit", formatTimestamp(profile.lastQuit(), formatter, zone, na));
                                base.put("login_streak", Integer.toString(profile.loginStreak()));
                                base.put("total_joins", Integer.toString(profile.totalJoins()));
                                base.put("last_ip", safeValue(profile.lastIp(), na));

                                String statusKey = isOnline ? "commands.playerinfo.status.online" : "commands.playerinfo.status.offline";
                                String statusDefault = isOnline ? "<#2AF598>online</#2AF598>" : "<#FF4D4F>offline</#FF4D4F>";
                                base.put("status", plugin.messages().getString(statusKey, statusDefault));

                                List<String> lines = new ArrayList<>();
                                addIfPresent(lines, "commands.playerinfo.header", "<#F6C35D>==== User information ====</#F6C35D>", base);
                                addIfPresent(lines, "commands.playerinfo.name", "<#8799ae>User:</#8799ae> <#FFFFFF>{player}</#FFFFFF> <#8799ae>(ID: {id})</#8799ae>", base);
                                addIfPresent(lines, "commands.playerinfo.uuid", "<#8799ae>Mojang ID:</#8799ae> <#FFFFFF>{uuid}</#FFFFFF>", base);
                                addIfPresent(lines, "commands.playerinfo.status.line", "<#8799ae>Online status:</#8799ae> {status}", base);
                                addIfPresent(lines, "commands.playerinfo.first-join", "<#8799ae>First join:</#8799ae> <#FFFFFF>{first_join}</#FFFFFF>", base);
                                addIfPresent(lines, "commands.playerinfo.last-join", "<#8799ae>Last join:</#8799ae> <#FFFFFF>{last_join}</#FFFFFF>", base);
                                addIfPresent(lines, "commands.playerinfo.last-quit", "<#8799ae>Last quit:</#8799ae> <#FFFFFF>{last_quit}</#FFFFFF>", base);
                                addIfPresent(lines, "commands.playerinfo.login-streak", "<#8799ae>Login streak:</#8799ae> <#FFFFFF>{login_streak}</#FFFFFF>", base);
                                addIfPresent(lines, "commands.playerinfo.total-joins", "<#8799ae>Total joins:</#8799ae> <#FFFFFF>{total_joins}</#FFFFFF>", base);
                                addIfPresent(lines, "commands.playerinfo.last-ip", "<#8799ae>Last IP:</#8799ae> <#FFFFFF>{last_ip}</#FFFFFF>", base);

                                int altLimit = plugin.getConfig().getInt("commands.playerinfo.max-alt-results", 5);
                                if (altLimit > 0) {
                                    List<AltAccountMatch> alts = plugin.db().findPotentialAlts(profile.id(), altLimit);
                                    if (!alts.isEmpty()) {
                                        addIfPresent(lines, "commands.playerinfo.alts.header", "<#8799ae>Maybe the same:</#8799ae>", base);
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
                                                "<#8799ae>No matching alternate accounts found.</#8799ae>", base);
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
                                        "<#8799ae>IP history for <#FFFFFF>{player}</#FFFFFF>:</#8799ae>", base);

                                List<IpHistoryEntry> history = plugin.db().loadIpHistory(profile.id(), limit);
                                if (history.isEmpty()) {
                                    addIfPresent(lines, "commands.playerinfo.ip-history.none",
                                            "<#8799ae>No stored IP addresses.</#8799ae>", base);
                                } else {
                                    for (IpHistoryEntry entry : history) {
                                        Map<String, String> map = new HashMap<>(base);
                                        map.put("ip", safeValue(entry.ip(), na));
                                        map.put("first_seen", formatTimestamp(entry.firstSeen(), formatter, zone, na));
                                        map.put("last_seen", formatTimestamp(entry.lastSeen(), formatter, zone, na));
                                        map.put("uses", Integer.toString(Math.max(1, entry.uses())));
                                        addIfPresent(lines, "commands.playerinfo.ip-history.entry",
                                                "  <#FFFFFF>{ip}</#FFFFFF> <#8799ae>({first_seen} → {last_seen}, {uses} joins)</#8799ae>", map);
                                    }
                                }

                                for (String line : lines) {
                                    if (line == null || line.isBlank()) continue;
                                    MessageUtil.sendRaw(sender, plugin, line);
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
                                return value;
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