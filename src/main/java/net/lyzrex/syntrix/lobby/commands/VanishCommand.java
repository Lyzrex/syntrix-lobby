package net.lythadmin.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.VanishService;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class VanishCommand implements TabExecutor {
    private final SyntrixLobby plugin;
    private final VanishService service;

    public VanishCommand(SyntrixLobby plugin, VanishService service) {
        this.plugin = plugin;
        this.service = service;
        if (plugin.getCommand("vanish") != null) {
            plugin.getCommand("vanish").setExecutor(this);
            plugin.getCommand("vanish").setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Command 'vanish' missing in plugin.yml!");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.send(sender, plugin, "errors.player-only", "<red>This command is player-only.</red>");
            return true;
        }

        String perm = plugin.getConfig().getString("vanish.permission", "lythcore.vanish");
        if (perm != null && !perm.isBlank() && !p.hasPermission(perm)) {
            MessageUtil.send(p, plugin, "errors.no-permission", "<red>You do not have permission to do this.</red>");
            return true;
        }

        Boolean targetState = null;
        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable")) targetState = true;
            if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) targetState = false;
        }

        boolean newState = (targetState != null) ? targetState : !service.isVanished(p.getUniqueId());

        service.setVanished(p.getUniqueId(), newState);
        service.applyVisibility(p);
        service.applyVisibilityForViewer(p);

        MessageUtil.send(p, plugin,
                newState ? "vanish.enabled" : "vanish.disabled",
                newState ? "<green>Vanish enabled.</green>" : "<red>Vanish disabled.</red>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("on", "off");
        return List.of();
    }
}
