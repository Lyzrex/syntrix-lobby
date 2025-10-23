package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;


public final class LobbyReloadCommand extends BaseCommand {

    public LobbyReloadCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {


        if (!plugin.getConfig().getBoolean("commands.lobbyreload.enabled", true)) return true;

        // permission
        final String perm = plugin.getConfig().getString("commands.lobbyreload.permission", "syntrix.reload");
        if (!sender.hasPermission(perm)) {
            ms.send(sender, plugin.messages().getString("general.no-permission",
                    "<red>You do not have permission.</red>"));
            return true;
        }

        long duration = plugin.reloadAll();

        String msg = plugin.messages().getString(
                "commands.lobbyreload.reloaded",
                "<#2AF598>Lobby reloaded successfully</#2AF598> <#8799ae>in <#FFFFFF>{time}ms</#FFFFFF><#8799ae>."
        );

        if (msg != null) {
            msg = msg
                    .replace("{time}", String.valueOf(duration))
                    .replace("%ms%", String.valueOf(duration));
        }

        ms.send(sender, msg);
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command cmd,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        return Collections.emptyList();
    }
}