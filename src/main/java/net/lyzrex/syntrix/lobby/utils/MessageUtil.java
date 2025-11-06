package net.lyzrex.syntrix.lobby.utils;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class MessageUtil {

    private static final MiniMessage mm = MiniMessage.miniMessage();

    private MessageUtil() {}

    private static String prefix(SyntrixLobby plugin) {
        return MessageService.resolvePrefix(plugin);
    }

    public static void send(CommandSender sender, SyntrixLobby plugin, String path, String def) {
        String msg = plugin.messages().getString(path, def);
        if (msg == null || msg.isBlank()) return;
        sender.sendMessage(mm.deserialize(prefix(plugin) + msg));
    }

    public static void sendRaw(CommandSender sender, SyntrixLobby plugin, String messageMiniMessage) {
        if (messageMiniMessage == null || messageMiniMessage.isBlank()) return;
        sender.sendMessage(mm.deserialize(prefix(plugin) + messageMiniMessage));
    }

    public static void sendActionBar(Player player, @Nullable String miniMessage) {
        if (player == null || miniMessage == null || miniMessage.isBlank()) {
            return;
        }
        player.sendActionBar(mm.deserialize(miniMessage));
    }

    public static void sendCooldown(Player p, SyntrixLobby plugin, double secondsLeft) {
        String tmpl = plugin.messages().getString(
                "doublejump.cooldown",
                "<red>Double jump is on cooldown for {time}s.</red>"
        );
        String msg = tmpl.replace("{time}", String.format(Locale.US, "%.1f", Math.max(0.0, secondsLeft)));
        p.sendMessage(mm.deserialize(prefix(plugin) + msg));
    }
}
