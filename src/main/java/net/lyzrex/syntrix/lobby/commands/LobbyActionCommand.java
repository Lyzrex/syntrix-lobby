package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

public class LobbyActionCommand extends BaseCommand {

    public LobbyActionCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) return true;

        String action = args[0].toLowerCase();
        String target = args[1].toLowerCase();
        String prefix = MessageService.resolvePrefix(plugin);

        if (action.equals("server")) {
            // Sendet die Nachricht in den normalen CHAT (nicht Actionbar!)
            player.sendMessage(mm.deserialize(prefix + "<#8799ae>Connecting to <#2AF598><bold>" + target.toUpperCase() + "</bold></#2AF598>..."));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            try {
                // Führt den BungeeCord Teleport aus
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(b);
                out.writeUTF("Connect");
                out.writeUTF(target);
                player.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
            } catch (Exception ignored) {}
        }
        else if (action.equals("link")) {
            // Alle Links landen im normalen CHAT und haben einen Klick- und Hover-Effekt!
            if (target.equals("discord")) {
                player.sendMessage(mm.deserialize(prefix + "<#5865F2>Join our Discord: <click:open_url:'https://discord.gg/H998F9MxwX'><hover:show_text:'<#5865F2>Click to join!'><underlined>discord.gg/H998F9MxwX</underlined></hover></click>"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            } else if (target.equals("website")) {
                player.sendMessage(mm.deserialize(prefix + "<#00AA00>Visit our Website: <click:open_url:'https://lythrion.net'><hover:show_text:'<#00AA00>Click to visit!'><underlined>lythrion.net</underlined></hover></click>"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            } else if (target.equals("youtube")) {
                player.sendMessage(mm.deserialize(prefix + "<#FF0000>Visit our YouTube: <click:open_url:'https://youtube.com/LythrionNetwork'><hover:show_text:'<#FF0000>Click to watch!'><underlined>YouTube</underlined></hover></click>"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            } else if (target.equals("twitch")) {
                player.sendMessage(mm.deserialize(prefix + "<#9146FF>Visit our Twitch: <click:open_url:'https://twitch.tv/LythrionNetwork'><hover:show_text:'<#9146FF>Click to watch!'><underlined>Twitch</underlined></hover></click>"));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) return List.of("server", "link");
        if (args.length == 2 && args[0].equalsIgnoreCase("server")) return List.of("citybuild");
        if (args.length == 2 && args[0].equalsIgnoreCase("link")) return List.of("discord", "website", "youtube", "twitch");
        return List.of();
    }
}