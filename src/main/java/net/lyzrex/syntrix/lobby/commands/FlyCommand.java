package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.FlightUtil;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FlyCommand extends BaseCommand {

    private enum Mode { ENABLE, DISABLE, TOGGLE }

    public FlyCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!plugin.getConfig().getBoolean("fly.enabled", true)) {
            MessageUtil.send(sender, plugin, "fly.disabled-command",
                    "<red>The fly command is currently disabled.</red>");
            return true;
        }

        Mode mode = Mode.TOGGLE;
        Player target;
        boolean selfTarget;

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, plugin, "fly.not-player",
                        "<red>Only players can toggle their own flight.</red>");
                return true;
            }
            target = player;
            selfTarget = true;
        } else {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                MessageUtil.send(sender, plugin, "fly.offline",
                        "<red>That player is not online.</red>");
                return true;
            }
            selfTarget = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
            if (args.length >= 2) {
                Mode parsed = parseMode(args[1]);
                if (parsed == null) {
                    MessageUtil.send(sender, plugin, "fly.usage",
                            "<gray>Usage:</gray> <white>/fly [player] [on|off|toggle]</white>");
                    return true;
                }
                mode = parsed;
            }
        }

        if (selfTarget) {
            String perm = plugin.getConfig().getString("fly.permission", "syntrix.fly");
            if (perm != null && !perm.isBlank() && !sender.hasPermission(perm)) {
                MessageUtil.send(sender, plugin, "fly.no-permission",
                        "<red>You do not have permission to fly.</red>");
                return true;
            }
        } else {
            String perm = plugin.getConfig().getString("fly.others-permission", "syntrix.fly.others");
            if (perm != null && !perm.isBlank() && !sender.hasPermission(perm)) {
                MessageUtil.send(sender, plugin, "fly.no-permission-others",
                        "<red>You do not have permission to change other players' flight.</red>");
                return true;
            }
        }

        boolean wasEnabled = FlightUtil.isFlightEnabled(target);
        var doubleJump = plugin.doubleJump();
        boolean treatAsPrimed = doubleJump != null
                && doubleJump.isPrimed(target)
                && !target.isFlying()
                && target.getGameMode() != GameMode.CREATIVE
                && target.getGameMode() != GameMode.SPECTATOR;
        boolean enable = switch (mode) {
            case ENABLE -> true;
            case DISABLE -> false;
            case TOGGLE -> treatAsPrimed ? true : !wasEnabled;
        };

        boolean changed = FlightUtil.setFlight(target, enable);
        if (doubleJump != null) {
            if (enable) {
                doubleJump.disableForFlight(target);
            } else {
                doubleJump.restoreAfterFlight(target);
            }
        }
        boolean showMessages = plugin.getConfig().getBoolean("fly.messages", true);

        if (!changed && enable == wasEnabled) {
            if (showMessages) {
                String alreadyKey = enable ? "fly.already-enabled" : "fly.already-disabled";
                String alreadyDefault = enable
                        ? "<gray>Flight is already enabled.</gray>"
                        : "<gray>Flight is already disabled.</gray>";
                MessageUtil.send(sender, plugin, alreadyKey, alreadyDefault);
            }
            return true;
        }

        if (!changed && enable != wasEnabled) {
            if (!enable && (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR)) {
                MessageUtil.send(sender, plugin, "fly.creative",
                        "<gray>You cannot disable flight for creative or spectator players.</gray>");
                return true;
            }
        }

        if (showMessages) {
            if (selfTarget) {
                sendStateMessage(sender, enable, "fly.enabled", "fly.disabled");
            } else {
                sendTargetMessages(sender, target, enable);
            }
        }
        return true;
    }

    private void sendStateMessage(CommandSender sender, boolean enabled, String enableKey, String disableKey) {
        if (enabled) {
            MessageUtil.send(sender, plugin, enableKey,
                    "<green>Flight enabled.</green>");
        } else {
            MessageUtil.send(sender, plugin, disableKey,
                    "<red>Flight disabled.</red>");
        }
    }

    private void sendTargetMessages(CommandSender sender, Player target, boolean enabled) {
        String key = enabled ? "fly.target-enabled" : "fly.target-disabled";
        String template = plugin.messages().getString(key,
                enabled ? "<green>Enabled flight for {player}.</green>"
                        : "<red>Disabled flight for {player}.</red>");
        String resolved = template.replace("{player}", target.getName());
        MessageUtil.sendRaw(sender, plugin, resolved);

        if (enabled) {
            MessageUtil.send(target, plugin, "fly.enabled",
                    "<green>Flight enabled.</green>");
        } else {
            MessageUtil.send(target, plugin, "fly.disabled",
                    "<red>Flight disabled.</red>");
        }
    }

    private Mode parseMode(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> Mode.ENABLE;
            case "off", "disable", "false" -> Mode.DISABLE;
            case "toggle" -> Mode.TOGGLE;
            default -> null;
        };
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.regionMatches(true, 0, args[0], 0, args[0].length()))
                    .toList();
        }
        if (args.length == 2) {
            List<String> options = new ArrayList<>(List.of("on", "off", "toggle"));
            return options.stream()
                    .filter(opt -> opt.regionMatches(true, 0, args[1], 0, args[1].length()))
                    .toList();
        }
        return List.of();
    }
}
