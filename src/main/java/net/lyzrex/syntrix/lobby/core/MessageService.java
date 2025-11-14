package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;


public final class MessageService {

    public static final String DEFAULT_PREFIX = "<gradient:#2AF598:#009EFD>Syntrix</gradient> <#8799ae>• ";

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public MessageService(SyntrixLobby plugin) {
        this.plugin = plugin;
    }


    public static String resolvePrefix(SyntrixLobby plugin) {
        if (plugin == null) return "";

        YamlConfiguration cfg = plugin.messages();
        boolean enabled = cfg.getBoolean(ConfigKeys.PREFIX_ENABLE, true);
        String pre = cfg.getString(ConfigKeys.PREFIX_TEXT, DEFAULT_PREFIX);
        return enabled ? pre : "";
    }
    public String prefix() {
        return resolvePrefix(plugin);
    }

    public void send(CommandSender sender, String msg) {
        if (sender instanceof Player player) {
            if (msg == null || msg.isBlank()) {
                return;
            }
            player.sendActionBar(mm.deserialize(msg));
            return;
        }

        String prefix = prefix();
        String body = msg == null ? "" : msg;
        if (body.isBlank() && prefix.isBlank()) {
            return;
        }

        String payload = body.isBlank() ? prefix : prefix + body;
        if (payload.isBlank()) {
            return;
        }

        sender.sendMessage(mm.deserialize(payload));
    }


    public void sendRaw(CommandSender sender, String msg) {
        if (sender instanceof Player player) {
            player.sendActionBar(mm.deserialize(msg == null ? "" : msg));
        } else {
            sender.sendMessage(mm.deserialize(msg == null ? "" : msg));
        }
    }


    public String get(String path, String def) {
        return plugin.messages().getString(path, def);
    }


    public void sendFromConfig(CommandSender sender, String path, String def) {
        String text = plugin.messages().getString(path, def);
        send(sender, text);
    }
}
