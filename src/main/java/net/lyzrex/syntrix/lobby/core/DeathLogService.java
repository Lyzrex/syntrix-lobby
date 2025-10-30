package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

public final class DeathLogService {

    private final SyntrixLobby plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private boolean enabled;
    private boolean consoleEnabled;
    private boolean chatEnabled;
    private String consoleFormat;
    private String chatMessage;
    private String chatPermission;
    private String timeFormat;
    private int coordinatePrecision;

    public DeathLogService(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void init() {
        reload();
    }

    public void reload() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("deathLogs");
        if (section == null) {
            this.enabled = false;
            return;
        }
        this.enabled = section.getBoolean("enabled", true);
        this.consoleEnabled = section.getBoolean("console.enabled", true);
        this.chatEnabled = section.getBoolean("chat.enabled", true);
        this.consoleFormat = section.getString("console.format",
                "[Death] {time} {player} died in {world} at {x}, {y}, {z} ({cause})");
        this.chatMessage = section.getString("chat.message",
                "<gray>[Death]</gray> <red>{player}</red> <gray>died at</gray> <yellow>{x}</yellow> <gray>, {y}, {z}</gray> <gray>in {world}</gray> <gray>({time})</gray>");
        this.chatPermission = section.getString("chat.permission", "");
        this.timeFormat = section.getString("time-format", "yyyy-MM-dd HH:mm:ss");
        this.coordinatePrecision = Math.max(0, section.getInt("coordinate-precision", 1));
    }

    public void handleDeath(@NotNull PlayerDeathEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getEntity();
        Location location = player.getLocation();
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "unknown";
        String cause = event.getEntity().getLastDamageCause() != null
                ? event.getEntity().getLastDamageCause().getCause().name()
                : "UNKNOWN";
        String time = DateTimeFormatter.ofPattern(timeFormat, Locale.getDefault())
                .format(LocalDateTime.now(ZoneId.systemDefault()));

        PlaceholderContext placeholders = new PlaceholderContext(player.getName(),
                player.getName(),
                worldName,
                cause,
                formatCoordinate(location.getX()),
                formatCoordinate(location.getY()),
                formatCoordinate(location.getZ()),
                time);

        if (consoleEnabled && consoleFormat != null && !consoleFormat.isBlank()) {
            plugin.getLogger().info(placeholders.apply(consoleFormat));
        }
        if (chatEnabled && chatMessage != null && !chatMessage.isBlank()) {
            broadcast(placeholders);
        }
    }

    public void shutdown() {
        // nothing to tear down yet, but the method exists for symmetry
    }

    private void broadcast(PlaceholderContext ctx) {
        Component component = miniMessage.deserialize(ctx.apply(chatMessage));
        List<Player> recipients = collectRecipients();
        for (Player viewer : recipients) {
            viewer.sendMessage(component);
        }
    }

    private List<Player> collectRecipients() {
        Predicate<Player> filter = chatPermission == null || chatPermission.isBlank()
                ? player -> true
                : player -> player.hasPermission(chatPermission);
        List<Player> list = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (filter.test(online)) {
                list.add(online);
            }
        }
        return list;
    }

    private String formatCoordinate(double value) {
        return String.format(Locale.US, "% ." + coordinatePrecision + "f", value).trim();
    }

    private record PlaceholderContext(String player,
                                      String displayName,
                                      String world,
                                      String cause,
                                      String x,
                                      String y,
                                      String z,
                                      String time) {
        String apply(String template) {
            Objects.requireNonNull(template, "template");
            return template
                    .replace("{player}", player)
                    .replace("{display_name}", displayName)
                    .replace("{world}", world)
                    .replace("{cause}", cause)
                    .replace("{x}", x)
                    .replace("{y}", y)
                    .replace("{z}", z)
                    .replace("{time}", time);
        }
    }
}