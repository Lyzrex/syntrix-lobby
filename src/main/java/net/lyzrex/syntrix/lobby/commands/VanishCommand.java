package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.VanishService;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class VanishCommand extends BaseCommand {
    private final VanishService service;

    public VanishCommand(SyntrixLobby plugin) {
        super(plugin);
        this.service = plugin.vanish();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin, "errors.player-only", "<red>This command is player-only.</red>");
            return true;
        }
        String perm = plugin.getConfig().getString("vanish.permission", "syntrix.vanish");
        if (perm != null && !perm.isBlank() && !player.hasPermission(perm)) {
            MessageUtil.send(player, plugin, "errors.no-permission", "<red>You do not have permission to do this.</red>");
            return true;
        }
        Boolean targetState = null;
        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable")) {
                targetState = true;
            } else if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) {
                targetState = false;
            }
        }

        boolean newState = targetState != null ? targetState : !service.isVanished(player.getUniqueId());
        service.setVanished(player.getUniqueId(), newState);
        service.applyVisibility(player);
        service.applyVisibilityForViewer(player);
        MessageUtil.send(player, plugin,

                newState ? "vanish.enabled" : "vanish.disabled",
                newState ? "<green>Vanish enabled.</green>" : "<red>Vanish disabled.</red>");
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command cmd,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("on", "off");
        }
        return List.of();
    }
}