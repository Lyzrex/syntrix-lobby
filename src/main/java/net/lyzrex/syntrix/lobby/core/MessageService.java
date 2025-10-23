package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;


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
        String prefix = prefix();
        sender.sendMessage(mm.deserialize(prefix + (msg == null ? "" : msg)));
    }


    public void sendRaw(CommandSender sender, String msg) {
        sender.sendMessage(mm.deserialize(msg == null ? "" : msg));
    }


    public String get(String path, String def) {
        return plugin.messages().getString(path, def);
    }


    public void sendFromConfig(CommandSender sender, String path, String def) {
        String text = plugin.messages().getString(path, def);
        send(sender, text);
    }
}