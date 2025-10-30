package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public final class JumpAndRunHologramCommand extends BaseCommand {

    public JumpAndRunHologramCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        JumpAndRunService service = plugin.jumpAndRun();
        if (service == null) {
            MessageUtil.send(sender, plugin, "errors.not-found",
                    "<red>The Jump & Run service is unavailable.</red>");
            return true;
        }

        String permission = service.leaderboardPermission();
        if (permission != null && !permission.isBlank() && !sender.hasPermission(permission)) {
            MessageUtil.send(sender, plugin, "jumpandrun.leaderboard.command.no-permission",
                    "<red>You do not have permission to manage the leaderboard hologram.</red>");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> handleSpawn(sender, service);
            case "remove" -> handleRemove(sender, service);
            case "update" -> handleUpdate(sender, service);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleSpawn(CommandSender sender, JumpAndRunService service) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin, "jumpandrun.leaderboard.command.player-only",
                    "<red>Only players can perform this action.</red>");
            return;
        }
        Location base = player.getLocation().add(0, 2.0, 0);
        service.spawnHologram(base);
        MessageUtil.send(sender, plugin, "jumpandrun.leaderboard.command.created",
                "<green>Jump & Run hologram spawned.</green>");
    }

    private void handleRemove(CommandSender sender, JumpAndRunService service) {
        service.removeHologram();
        MessageUtil.send(sender, plugin, "jumpandrun.leaderboard.command.removed",
                "<red>Jump & Run hologram removed.</red>");
    }

    private void handleUpdate(CommandSender sender, JumpAndRunService service) {
        service.reload();
        MessageUtil.send(sender, plugin, "jumpandrun.leaderboard.command.updated",
                "<green>Jump & Run hologram updated.</green>");
    }

    private void sendUsage(CommandSender sender) {
        MessageUtil.send(sender, plugin, "jumpandrun.leaderboard.command.usage",
                "<gray>Usage:</gray> <white>/jumpandrunholo <spawn|remove|update></white>");
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "update").stream()
                    .filter(opt -> opt.regionMatches(true, 0, args[0], 0, args[0].length()))
                    .toList();
        }
        return List.of();
    }
}