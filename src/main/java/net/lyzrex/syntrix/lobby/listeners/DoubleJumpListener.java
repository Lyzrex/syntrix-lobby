package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import net.lyzrex.syntrix.lobby.utils.SoundUtil;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DoubleJumpListener implements Listener {

    private final SyntrixLobby plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    public DoubleJumpListener(@NotNull SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("doublejump.enabled", true);
    }

    private boolean hasBuildMode(Player p) {
        return p.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
    }

    private boolean canUse(Player p) {
        if (!enabled()) return false;
        if (hasBuildMode(p)) return false;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        String perm = plugin.getConfig().getString("doublejump.permission", "syntrix.doublejump");
        return perm == null || perm.isBlank() || p.hasPermission(perm);
    }

    private double cooldownSeconds() {
        return plugin.getConfig().getDouble("doublejump.cooldown-seconds", 1.0D);
    }

    private void playJumpSound(Player p) {
        String raw = plugin.getConfig().getString("doublejump.sound", "ENTITY_FIREWORK_ROCKET_LAUNCH");
        float vol = (float) plugin.getConfig().getDouble("doublejump.volume", 0.8D);
        float pit = (float) plugin.getConfig().getDouble("doublejump.pitch", 1.2D);
        Sound s = SoundUtil.resolve(raw, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
        p.playSound(p.getLocation(), s, SoundCategory.MASTER, vol, pit);
    }

    private void primeLobbyFlight(Player p) {
        if (!canUse(p)) return;
        if (!p.getAllowFlight()) p.setAllowFlight(true);
        if (p.isFlying()) p.setFlying(false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        primeLobbyFlight(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!canUse(p)) return;
        if (p.isOnGround() && !p.getAllowFlight()) p.setAllowFlight(true); // Reset nach Sprung
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldownUntil.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!canUse(p)) return;
        if (!e.isFlying()) return;
        e.setCancelled(true);

        long now = System.currentTimeMillis();
        long until = cooldownUntil.getOrDefault(p.getUniqueId(), 0L);
        if (until > now) {
            double left = (until - now) / 1000.0;
            MessageUtil.sendCooldown(p, plugin, left); // mit Prefix
            return;
        }


        p.setAllowFlight(false);
        var dir = p.getLocation().getDirection().normalize();
        double up = 0.55D;
        double forward = 1.10D;
        p.setVelocity(dir.multiply(forward).setY(up));

        playJumpSound(p);

        long cdMs = (long) Math.max(0, cooldownSeconds() * 1000.0);
        cooldownUntil.put(p.getUniqueId(), now + cdMs);
    }
}