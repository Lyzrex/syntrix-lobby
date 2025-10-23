package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HexColorChatListener implements Listener {

    private final SyntrixLobby plugin;
    private static final Pattern HEX = Pattern.compile("(?i)#([0-9a-f]{6})");

    public HexColorChatListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;

        String perm = plugin.getConfig().getString("chat.permission", "syntrix.chat.hex");
        if (perm != null && !perm.isBlank() && !e.getPlayer().hasPermission(perm)) return;


        Component original = e.message();
        if (!(original instanceof TextComponent tc)) return;

        String raw = tc.content();
        Matcher m = HEX.matcher(raw);


        if (!m.find()) return;
        m.reset();


        TextComponent.Builder out = Component.text();
        int last = 0;
        TextColor current = null;

        while (m.find()) {
            if (m.start() > last) {
                String before = raw.substring(last, m.start());
                out.append(Component.text(before, current));
            }
            current = TextColor.fromHexString("#" + m.group(1));
            last = m.end();
        }
        if (last < raw.length()) {
            out.append(Component.text(raw.substring(last), current));
        }

        e.message(out.build());
    }
}