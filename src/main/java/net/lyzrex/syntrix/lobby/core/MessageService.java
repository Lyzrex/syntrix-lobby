package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;


public final class MessageService {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public MessageService(SyntrixLobby plugin) {
        this.plugin = plugin;
    }


    public String prefix() {
        YamlConfiguration cfg = plugin.messages();
        boolean enabled = cfg.getBoolean("messages.prefix.enabled", true);
        String pre = cfg.getString(
                "messages.prefix.text",
                "<gray>[<gradient:#00ffff:#0080ff>Syntrix</gradient>]</gray> "
        );
        return enabled ? pre : "";
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
