package net.lyzrex.syntrix.lobby.core;

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
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DoubleJumpService implements Listener {

    private final SyntrixLobby plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<UUID> temporarilyDisabled = new HashSet<>();

    public DoubleJumpService(@NotNull SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!temporarilyDisabled.contains(player.getUniqueId())) {
                prime(player);
            }
        }
    }

    public void disableForFlight(@NotNull Player player) {
        temporarilyDisabled.add(player.getUniqueId());
        cooldownUntil.remove(player.getUniqueId());
    }

    public boolean isTemporarilyDisabled(@NotNull UUID uuid) {
        return temporarilyDisabled.contains(uuid);
    }

    public void restoreAfterFlight(@NotNull Player player) {
        temporarilyDisabled.remove(player.getUniqueId());
        prime(player);
    }

    public void refresh(@NotNull Player player) {
        if (temporarilyDisabled.contains(player.getUniqueId())) {
            return;
        }
        prime(player);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("doublejump.enabled", true);
    }

    private boolean hasBuildMode(Player player) {
        return player.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
    }

    private boolean canUse(Player player) {
        if (!enabled()) {
            return false;
        }
        if (temporarilyDisabled.contains(player.getUniqueId())) {
            return false;
        }
        if (hasBuildMode(player)) {
            return false;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        String perm = plugin.getConfig().getString("doublejump.permission", "syntrix.doublejump");
        return perm == null || perm.isBlank() || player.hasPermission(perm);
    }
    public boolean isPrimed(@NotNull Player player) {
        return canUse(player);
    }

    private double cooldownSeconds() {
        return plugin.getConfig().getDouble("doublejump.cooldown-seconds", 1.0D);
    }

    private void playJumpSound(Player player) {
        String raw = plugin.getConfig().getString("doublejump.sound", "ENTITY_FIREWORK_ROCKET_LAUNCH");
        float volume = (float) plugin.getConfig().getDouble("doublejump.volume", 0.8D);
        float pitch = (float) plugin.getConfig().getDouble("doublejump.pitch", 1.2D);
        Sound sound = SoundUtil.resolve(raw, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    private void prime(Player player) {
        if (!canUse(player)) {
            return;
        }
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        if (player.isFlying()) {
            player.setFlying(false);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        prime(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!canUse(player)) {
            return;
        }
        if (player.isOnGround() && !player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldownUntil.remove(id);
        temporarilyDisabled.remove(id);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!temporarilyDisabled.contains(player.getUniqueId())) {
                prime(player);
            }
        });
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!canUse(player)) {
            return;
        }
        if (!event.isFlying()) {
            return;
        }
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long until = cooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            double left = (until - now) / 1000.0;
            MessageUtil.sendCooldown(player, plugin, left);
            return;
        }

        player.setAllowFlight(false);
        var dir = player.getLocation().getDirection().normalize();
        double up = 0.55D;
        double forward = 1.10D;
        player.setVelocity(dir.multiply(forward).setY(up));

        playJumpSound(player);

        long cooldownMillis = (long) Math.max(0, cooldownSeconds() * 1000.0);
        cooldownUntil.put(player.getUniqueId(), now + cooldownMillis);
    }
}
