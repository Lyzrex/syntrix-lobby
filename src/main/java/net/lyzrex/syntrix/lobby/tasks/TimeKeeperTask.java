package net.lyzrex.syntrix.lobby.tasks;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

public final class TimeKeeperTask extends BukkitRunnable {

    private final SyntrixLobby plugin;

    public TimeKeeperTask(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("timeControl.enabled", true)) return;

        World world = resolveWorld();
        if (world == null) return;

        long fixed = clampTime(plugin.getConfig().getLong("timeControl.fixed-time", 1000L));
        if (world.getTime() != fixed) {
            world.setTime(fixed);
        }

        if (plugin.getConfig().getBoolean("timeControl.freeze", true)) {
            world.setGameRuleValue("doDaylightCycle", "false");
        }
    }

    private World resolveWorld() {
        String worldName = plugin.getConfig().getString("timeControl.world", "");
        if (worldName == null || worldName.isBlank()) {
            worldName = plugin.getConfig().getString("lobby.world", "world");
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("[TimeControl] World '" + worldName + "' not found.");
        }
        return world;
    }

    private long clampTime(long value) {
        if (value < 0) return 0L;
        return Math.min(value, 23999L);
    }
}