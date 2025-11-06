package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.listeners.PlayerJoinListener; // falls du den Listener anders benennst, hier anpassen
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public final class BuildCommand extends BaseCommand {

    private static final Set<UUID> BYPASS_PLAYERS = new HashSet<>();

    public BuildCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!plugin.getConfig().getBoolean("commands.build.enabled", true)) return true;


        String perm = plugin.getConfig().getString("commands.build.permission", "syntrix.build");

        if (!(sender instanceof Player p)) {
            ms.send(sender, "<red>Only players can use this command.</red>");
            return true;
        }
        if (!p.hasPermission(perm)) {
            ms.send(p, plugin.messages().getString("general.no-permission",
                    "<red>You do not have permission.</red>"));
            return true;
        }

        var pdc = p.getPersistentDataContainer();
        boolean nowEnabled;

        if (pdc.has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE)) {

            pdc.remove(SyntrixLobby.BUILD_MODE);
            BYPASS_PLAYERS.remove(p.getUniqueId());
            p.setGameMode(GameMode.ADVENTURE);

            if (plugin.getConfig().getBoolean("build.clear-on-disable", true)) {
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
            }

            PlayerJoinListener.giveLobbyLoadout(plugin, p);

            ms.send(p, plugin.messages().getString("commands.build.disabled",
                    "<red>Build mode disabled. Inventory restored.</red>"));
            nowEnabled = false;
        } else {

            pdc.set(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE, (byte) 1);
            BYPASS_PLAYERS.add(p.getUniqueId());
            p.setGameMode(GameMode.CREATIVE);

            ms.send(p, plugin.messages().getString("commands.build.enabled",
                    "<green>Build mode enabled. You are now in creative mode.</green>"));
            nowEnabled = true;
        }

        plugin.getLogger().info(p.getName() + " toggled build mode -> " + nowEnabled);
        return true;
    }


    public static boolean hasBypass(UUID uuid) {
        return BYPASS_PLAYERS.contains(uuid);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        return List.of();
    }
}