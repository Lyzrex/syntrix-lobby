package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class JumpPadListener implements Listener {

    private final SyntrixLobby plugin;
    private static final MiniMessage mm = MiniMessage.miniMessage();


    private final Map<UUID, Long> lastUseMs = new ConcurrentHashMap<>();

    public JumpPadListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlate(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        if (!plugin.getConfig().getBoolean("jumppads.enabled", true)) return;


        String worldName = plugin.getConfig().getString("jumppads.world", "");
        if (worldName != null && !worldName.isBlank() && !block.getWorld().getName().equalsIgnoreCase(worldName)) {
            return;
        }


        if (!isValidTrigger(block.getType())) return;

        Player p = e.getPlayer();


        long now = System.currentTimeMillis();
        long cdMs = Math.max(0L, plugin.getConfig().getLong("jumppads.cooldown-ticks", 10)) * 50L;
        long last = lastUseMs.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < cdMs) return;
        lastUseMs.put(p.getUniqueId(), now);


        boolean useLook = plugin.getConfig().getBoolean("jumppads.velocity.use-look-direction", true);
        double horiz = plugin.getConfig().getDouble("jumppads.velocity.horizontal", 1.2D);
        double vert  = plugin.getConfig().getDouble("jumppads.velocity.vertical", 1.0D);
        double cap   = Math.max(0D, plugin.getConfig().getDouble("jumppads.velocity.cap", 3.5D));

        org.bukkit.util.Vector v;
        if (useLook) {
            org.bukkit.util.Vector dir = p.getLocation().getDirection();
            dir.setY(0);
            if (dir.lengthSquared() < 1e-6) dir = new org.bukkit.util.Vector(0, 0, 0);
            else dir.normalize();
            v = dir.multiply(horiz);
        } else {
            v = new org.bukkit.util.Vector(0, 0, 0);
            double yawRad = Math.toRadians(p.getLocation().getYaw());
            v.setX(-Math.sin(yawRad) * horiz);
            v.setZ(Math.cos(yawRad) * horiz);
        }
        v.setY(vert);

        if (cap > 0 && v.length() > cap) {
            v.normalize().multiply(cap);
        }

        p.setVelocity(v);


        if (plugin.getConfig().getBoolean("jumppads.particles.enabled", true)) {
            String typeName = plugin.getConfig().getString("jumppads.particles.type", "CLOUD");
            Particle type;
            try {
                type = Particle.valueOf(typeName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                type = Particle.CLOUD;
            }
            int count = plugin.getConfig().getInt("jumppads.particles.count", 20);
            double ox = plugin.getConfig().getDouble("jumppads.particles.offset.x", 0.2);
            double oy = plugin.getConfig().getDouble("jumppads.particles.offset.y", 0.4);
            double oz = plugin.getConfig().getDouble("jumppads.particles.offset.z", 0.2);
            double speed = plugin.getConfig().getDouble("jumppads.particles.speed", 0.01);
            String at = plugin.getConfig().getString("jumppads.particles.at", "player").toLowerCase(Locale.ROOT);

            Location loc = switch (at) {
                case "plate" -> block.getLocation().add(0.5, 1.0, 0.5);
                default -> p.getLocation().add(0, 1.0, 0);
            };
            loc.getWorld().spawnParticle(type, loc, count, ox, oy, oz, speed);
        }


        if (plugin.getConfig().getBoolean("jumppads.sound.enabled", true)) {
            String sName = plugin.getConfig().getString("jumppads.sound.type", "ENTITY_FIREWORK_ROCKET_LAUNCH");
            Sound sound;
            try {
                sound = Sound.valueOf(sName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                sound = Sound.ENTITY_FIREWORK_ROCKET_LAUNCH;
            }
            float vol = (float) plugin.getConfig().getDouble("jumppads.sound.volume", 0.6D);
            float pit = (float) plugin.getConfig().getDouble("jumppads.sound.pitch", 1.4D);
            p.playSound(p.getLocation(), sound, vol, pit);
        }


        if (plugin.getConfig().getBoolean("jumppads.message.enabled", false)) {
            boolean actionbar = plugin.getConfig().getBoolean("jumppads.message.actionbar", true);
            String text = plugin.messages().getString("jumppads.launch", "<#8799ae>Jump!</#8799ae>");
            if (actionbar) p.sendActionBar(mm.deserialize(text));
            else p.sendMessage(mm.deserialize(text));
        }
    }

    private boolean isValidTrigger(Material type) {
        ConfigurationSection ts = plugin.getConfig().getConfigurationSection("jumppads.triggers");
        boolean usePlates = ts == null || ts.getBoolean("use-pressure-plates", true);


        List<String> mats = ts != null ? ts.getStringList("materials") : Collections.emptyList();
        if (!mats.isEmpty()) {
            for (String s : mats) {
                if (s == null || s.isBlank()) continue;
                if (type.name().equalsIgnoreCase(s.trim())) return true;
            }
            return false;
        }

        if (usePlates) {
            String n = type.name();
            return n.endsWith("_PRESSURE_PLATE") || n.equals("STONE_PRESSURE_PLATE")
                    || n.equals("LIGHT_WEIGHTED_PRESSURE_PLATE")
                    || n.equals("HEAVY_WEIGHTED_PRESSURE_PLATE");
        }
        return false;
    }
}
