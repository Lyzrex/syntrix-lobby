package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
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

        if (!plugin.getConfig().getBoolean("commands.time.enabled", true)) return true;

        final String perm = plugin.getConfig().getString("commands.time.permission", "syntrix.time");
        if (!sender.hasPermission(perm)) {
            ms.send(sender, plugin.messages().getString("general.no-permission",
                    "<red>You do not have permission.</red>"));
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
                ms.send(sender, plugin.messages().getString("time.set.ok",
                        "<green>Time set to <white>%ticks%</white>.</green>").replace("%ticks%", String.valueOf(ticks)));
            } else {
                ms.send(sender, "<red>World not found.</red>");
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

                plugin.restartTimeControl();

                ms.send(sender, plugin.messages().getString("time.auto.enabled",
                                "<green>Automatic time-lock enabled (fixed=<white>%ticks%</white>).</green>")
                        .replace("%ticks%", String.valueOf(plugin.getConfig().getLong("timeControl.fixed-time", 1000L))));
                return true;
            }
            if (args[1].equalsIgnoreCase("off")) {
                plugin.getConfig().set("timeControl.enabled", false);
                plugin.saveConfig();

                plugin.restartTimeControl();

                ms.send(sender, plugin.messages().getString("time.auto.disabled",
                        "<red>Automatic time-lock disabled.</red>"));
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

            plugin.restartTimeControl();

            ms.send(sender, plugin.messages().getString("time.lock.updated",
                            "<green>Fixed time updated to <white>%ticks%</white> and task restarted.</green>")
                    .replace("%ticks%", String.valueOf(ticks)));
            return true;
        }


        ms.send(sender, usage());
        return true;
    }

    private String usage() {
        return plugin.messages().getString("time.usage",
                "<gray>Usage:</gray> <white>/time set <ticks></white> | <white>/time auto on|off</white> | <white>/time lock <ticks></white>");
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
        return Collections.emptyList();
    }
}
