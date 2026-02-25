package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class LobbyTimeCommand extends BaseCommand {

    public LobbyTimeCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!plugin.getConfig().getBoolean("commands.time.enabled", true)) {
            ms.send(sender, plugin.messages().getString(
                    "commands.time.disabled-command",
                    "<#FF4D4F>The time command is currently disabled.</#FF4D4F>"
            ));
            return true;
        }

        final String perm = plugin.getConfig().getString("commands.time.permission", "syntrix.time");
        if (!sender.hasPermission(perm)) {
            ms.send(sender, plugin.messages().getString("errors.no-permission",
                    "<#FF4D4F>You do not have permission to do this.</#FF4D4F>"));
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            long ticks = parseTicks(args[1]);
            if (ticks < 0) {
                ms.send(sender, usage());
                return true;
            }
            World w = resolveWorld();
            if (w != null) {
                w.setTime(ticks);
                ms.send(sender, plugin.messages().getString("commands.time.set.ok",
                        "<#2AF598>Time set to <#FFFFFF>{ticks}</#FFFFFF>.</#2AF598>").replace("{ticks}", String.valueOf(ticks)));
            } else {
                ms.send(sender, plugin.messages().getString(
                        "commands.time.world-missing",
                        "<#FF4D4F>World not found.</#FF4D4F>"
                ));
            }
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("auto")) {
            if (args[1].equalsIgnoreCase("on")) {
                plugin.getConfig().set("timeControl.enabled", true);
                plugin.saveConfig();

                World w = resolveWorld();
                if (w != null) {
                    long fixed = plugin.getConfig().getLong("timeControl.fixed-time", 1000L);
                    w.setTime(fixed);
                }

                plugin.startTimeControl();

                ms.send(sender, plugin.messages().getString("commands.time.auto.enabled",
                                "<#2AF598>Automatic time-lock enabled <#8799ae>(fixed=<#FFFFFF>{ticks}</#FFFFFF>).</#8799ae>")
                        .replace("{ticks}", String.valueOf(plugin.getConfig().getLong("timeControl.fixed-time", 1000L))));
                return true;
            }
            if (args[1].equalsIgnoreCase("off")) {
                plugin.getConfig().set("timeControl.enabled", false);
                plugin.saveConfig();

                plugin.startTimeControl();

                ms.send(sender, plugin.messages().getString("commands.time.auto.disabled",
                        "<#FF4D4F>Automatic time-lock disabled.</#FF4D4F>"));
                return true;
            }
            ms.send(sender, usage());
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("lock")) {
            long ticks = parseTicks(args[1]);
            if (ticks < 0) {
                ms.send(sender, usage());
                return true;
            }
            plugin.getConfig().set("timeControl.fixed-time", ticks);
            plugin.saveConfig();

            World w = resolveWorld();
            if (w != null) w.setTime(ticks);

            plugin.startTimeControl();

            ms.send(sender, plugin.messages().getString("commands.time.lock.updated",
                            "<#2AF598>Fixed time updated to <#FFFFFF>{ticks}</#FFFFFF> and task restarted.</#2AF598>")
                    .replace("{ticks}", String.valueOf(ticks)));
            return true;
        }

        ms.send(sender, usage());
        return true;
    }

    private String usage() {
        return plugin.messages().getString("commands.time.usage",
                "<#8799ae>Usage:</#8799ae> <#FFFFFF>/time set <ticks></#FFFFFF> | <#FFFFFF>/time auto on|off</#FFFFFF> | <#FFFFFF>/time lock <ticks></#FFFFFF>");
    }

    private World resolveWorld() {
        String conf = plugin.getConfig().getString("timeControl.world", "");
        if (conf != null && !conf.isBlank()) {
            World w = Bukkit.getWorld(conf);
            if (w != null) return w;
        }
        return Bukkit.getWorld(plugin.getConfig().getString("lobby.world", "world"));
    }

    private long parseTicks(String s) {
        try {
            long v = Long.parseLong(s);
            return (v < 0 ? -1 : v);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command cmd,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("set", "auto", "lock").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("auto")) {
            return List.of("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("lock"))) {
            return List.of("0", "1000", "6000", "12000", "18000").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}