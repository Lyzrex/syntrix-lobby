package net.lyzrex.syntrix.lobby.tasks;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

public final class SpawnParticleRingTask extends BukkitRunnable {

    private final SyntrixLobby plugin;

    public SpawnParticleRingTask(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("spawnParticles.enabled", false)) return;

        String worldName = plugin.getConfig().getString("lobby.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("[SpawnParticles] World '" + worldName + "' not found.");
            return;
        }

        double x = plugin.getConfig().getDouble("lobby.spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("lobby.spawn.y", 80.0);
        double z = plugin.getConfig().getDouble("lobby.spawn.z", 0.5);

        double radius = plugin.getConfig().getDouble("spawnParticles.radius", 2.5);
        int points = Math.max(8, plugin.getConfig().getInt("spawnParticles.points", 60));
        String type = plugin.getConfig().getString("spawnParticles.type", "CRIT");

        Particle particle;
        try {
            particle = Particle.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException ex) {
            particle = Particle.CRIT;
        }

        Location center = new Location(world, x, y, z);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double px = center.getX() + radius * Math.cos(angle);
            double pz = center.getZ() + radius * Math.sin(angle);
            world.spawnParticle(particle, px, y, pz, 1, 0, 0, 0, 0);
        }
    }
}
