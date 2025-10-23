package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public final class SpawnCommand extends BaseCommand {

    public SpawnCommand(SyntrixLobby plugin) { super(plugin); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player p)) return true;

        final String perm = plugin.getConfig().getString("commands.spawn.permission", "syntrix.spawn");
        if (perm != null && !perm.isBlank() && !p.hasPermission(perm)) return true;

        // /spawn autojoin [on|off]
        if (args.length >= 1 && "autojoin".equalsIgnoreCase(args[0])) {
            if (args.length == 1) {
                // toggle
                boolean has = p.getPersistentDataContainer().has(SyntrixLobby.AUTOJOIN_OFF, PersistentDataType.BYTE);
                if (has) {
                    p.getPersistentDataContainer().remove(SyntrixLobby.AUTOJOIN_OFF);
                    ms.send(p, plugin.messages().getString("autojoin.enabled",
                            "<green>You will now be teleported to spawn on join.</green>"));
                } else {
                    p.getPersistentDataContainer().set(SyntrixLobby.AUTOJOIN_OFF, PersistentDataType.BYTE, (byte) 1);
                    ms.send(p, plugin.messages().getString("autojoin.disabled",
                            "<red>You will no longer be teleported to spawn on join.</red>"));
                }
                return true;
            }
            if (args.length == 2) {
                if ("on".equalsIgnoreCase(args[1])) {
                    p.getPersistentDataContainer().remove(SyntrixLobby.AUTOJOIN_OFF);
                    ms.send(p, plugin.messages().getString("autojoin.enabled",
                            "<green>You will now be teleported to spawn on join.</green>"));
                    return true;
                }
                if ("off".equalsIgnoreCase(args[1])) {
                    p.getPersistentDataContainer().set(SyntrixLobby.AUTOJOIN_OFF, PersistentDataType.BYTE, (byte) 1);
                    ms.send(p, plugin.messages().getString("autojoin.disabled",
                            "<red>You will no longer be teleported to spawn on join.</red>"));
                    return true;
                }
            }
            // Usage fallback
            ms.send(p, "<gray>Usage:</gray> <white>/" + label + " autojoin [on|off]</white>");
            return true;
        }

        // /spawn
        final String worldName = plugin.getConfig().getString("lobby.world", "world");
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            ms.send(p, "<red>Lobby world not found:</red> <white>" + worldName + "</white>");
            return true;
        }

        Location loc = new Location(
                world,
                plugin.getConfig().getDouble("lobby.spawn.x", 0.5),
                plugin.getConfig().getDouble("lobby.spawn.y", 80.0),
                plugin.getConfig().getDouble("lobby.spawn.z", 0.5),
                (float) plugin.getConfig().getDouble("lobby.spawn.yaw", 0.0),
                (float) plugin.getConfig().getDouble("lobby.spawn.pitch", 0.0)
        );

        p.teleport(loc);
        ms.send(p, plugin.messages().getString("commands.spawn.success", "<green>Teleported to spawn.</green>"));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command cmd,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1) return List.of("autojoin");
        if (args.length == 2 && "autojoin".equalsIgnoreCase(args[0])) return List.of("on", "off");
        return Collections.emptyList();
    }
}