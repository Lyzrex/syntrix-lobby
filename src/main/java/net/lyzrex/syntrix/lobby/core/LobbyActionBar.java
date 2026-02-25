package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class LobbyActionBar {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private int taskId = -1;

    public LobbyActionBar(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (taskId != -1) return;

        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                int hp = (int) Math.ceil(p.getHealth());
                int ping = p.getPing();

                String msg = "<#FF4D4F>" + hp + " ❤ <#8799ae>| <#2AF598>Lobby <#8799ae>| <#009EFD>" + ping + "ms";
                p.sendActionBar(mm.deserialize(msg));
            }
        }, 0L, 10L); // Update alle halbe Sekunde (10 Ticks)
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }
}