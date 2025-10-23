package net.lyzrex.syntrix.lobby.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import org.bukkit.command.TabExecutor;

public abstract class BaseCommand implements TabExecutor {
    protected final SyntrixLobby plugin;
    protected final MessageService ms;
    protected final MiniMessage mm = MiniMessage.miniMessage();

    protected BaseCommand(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.ms = new MessageService(plugin);
    }
}