package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.regex.Pattern;


public final class SignColorListener implements Listener {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("(?i)#([0-9a-f]{6})");

    public SignColorListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        if (!plugin.getConfig().getBoolean("signs.enabled", true)) return;

        String perm = plugin.getConfig().getString("signs.permission", "syntrix.signs.color");
        if (perm != null && !perm.isBlank() && !e.getPlayer().hasPermission(perm)) return;

        boolean allowMini = plugin.getConfig().getBoolean("signs.allow-minimessage", true);
        boolean allowHex  = plugin.getConfig().getBoolean("signs.allow-hex", true);
        boolean filterEmpty = plugin.getConfig().getBoolean("signs.filter-empty-lines", false);

        for (int i = 0; i < e.lines().size(); i++) {

            String raw = PlainTextComponentSerializer.plainText().serialize(e.line(i));
            if (raw == null) raw = "";
            String text = raw;

            if (filterEmpty && text.isBlank()) {
                e.line(i, Component.empty());
                continue;
            }

            if (allowHex) {
                text = HEX.matcher(text).replaceAll("<#$1>");
            }

            Component result;
            if (allowMini && looksLikeMiniMessage(text)) {
                result = mm.deserialize(text);
            } else {
                result = legacyLike(text);
            }

            e.line(i, result);
        }
    }

    private boolean looksLikeMiniMessage(String s) {
        return s.indexOf('<') >= 0 && s.indexOf('>') > s.indexOf('<');
    }

    private Component legacyLike(String s) {
        String tmp = s.replace('&', '§');
        tmp = HEX.matcher(s).replaceAll("§x§$1".replaceAll("", "")); // nicht genutzt hier
        return MiniMessage.miniMessage().deserialize(
                tmp.replace("§", "&")
        );
    }
}
