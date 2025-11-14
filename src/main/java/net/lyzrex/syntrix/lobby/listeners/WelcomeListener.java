package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.MessageService;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public final class WelcomeListener implements Listener {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public WelcomeListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        e.joinMessage(null);


        boolean vanished = false;
        if (plugin.getConfig().getBoolean("vanish.enabled", true) && plugin.vanish() != null) {

            vanished = plugin.vanish().applyOnJoin(p);


            if (plugin.vanish().isVanished(p.getUniqueId())) {
                MessageUtil.send(p, plugin, "vanish.still-vanished", "<#8799ae>You remain vanished.</#8799ae>");
            }


            boolean autoEnabled = plugin.getConfig().getBoolean("vanish.auto.enabled", true);
            String autoPerm = plugin.getConfig().getString("vanish.auto.permission", "syntrix.vanish.auto");
            if (autoEnabled && autoPerm != null && p.hasPermission(autoPerm) && !plugin.vanish().isVanished(p.getUniqueId())) {
                plugin.vanish().setVanished(p.getUniqueId(), true);
                MessageUtil.send(p, plugin, "vanish.enabled", "<gradient:#2AF598:#009EFD>Vanish enabled.</gradient>");
                vanished = true;
            }
        }


        File dataFile = getPlayerDataFile(p.getUniqueId());
        boolean firstTime = !dataFile.exists();

        if (plugin.getConfig().getBoolean("player-data.enabled", true)) {
            try {
                logJoin(p, dataFile);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to write player-data for " + p.getName() + ": " + ex.getMessage());
            }
        }

        if (!plugin.getConfig().getBoolean("welcome.enabled", true)) {

            doSpawnTeleportIfEnabled(p);
            return;
        }

        boolean suppressJoinQuit = plugin.getConfig().getBoolean("vanish.suppress-join-quit", true);


        if (plugin.getConfig().getBoolean("welcome.join.enabled", true) && !(vanished && suppressJoinQuit)) {
            String joinMsg = plugin.messages().getString(
                    "welcome.join",
                    "<#2AF598>[+]</#2AF598> <#FFFFFF><player>"
            );
            broadcastActionBar(renderFor(p, joinMsg));
        }


        if (plugin.getConfig().getBoolean("welcome.firstJoin.enabled", true) && firstTime) {

            String toPlayer = plugin.messages().getString(
                    "welcome.first.toPlayer",
                    "<gradient:#2AF598:#009EFD>Welcome</gradient> <#FFFFFF><player> <#8799ae>to the network!"
            );
            p.sendActionBar(renderFor(p, toPlayer));


            if (plugin.getConfig().getBoolean("welcome.firstJoin.broadcast", true) && !(vanished && suppressJoinQuit)) {
                String bc = plugin.messages().getString(
                        "welcome.first.broadcast",
                        "<#F6C35D>Everyone welcome <#FFFFFF><player></#FFFFFF> <#F6C35D>!"
                );
                broadcastActionBar(renderFor(p, bc));
            }


            if (plugin.getConfig().getBoolean("welcome.firstJoin.firework", true)) spawnSmallFirework(p);
            if (plugin.getConfig().getBoolean("welcome.firstJoin.particles", true)) {
                p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.5, 0), 40, 0.5, 0.5, 0.5, 0.02);
            }
        }


        if (plugin.getConfig().getBoolean("effects.enabled", true)) {
            if (plugin.getConfig().getBoolean("effects.title.enabled", true)) {
                String title = plugin.messages().getString("welcome.effects.title",
                        "<gradient:#4FACFE:#00F2FE>Welcome</gradient>");
                String subtitle = plugin.messages().getString("welcome.effects.subtitle",
                        "<#8799ae>to <#FFFFFF>Syntrix</#FFFFFF><#8799ae>!");

                p.showTitle(Title.title(
                        mm.deserialize(title),
                        mm.deserialize(subtitle),
                        Title.Times.times(
                                Duration.ofMillis(plugin.getConfig().getLong("effects.title.fadeInMs", 500)),
                                Duration.ofMillis(plugin.getConfig().getLong("effects.title.stayMs", 2500)),
                                Duration.ofMillis(plugin.getConfig().getLong("effects.title.fadeOutMs", 800))
                        )
                ));
            }

            if (plugin.getConfig().getBoolean("effects.sound.enabled", true)) {
                float vol = (float) plugin.getConfig().getDouble("effects.sound.volume", 1.0);
                float pit = (float) plugin.getConfig().getDouble("effects.sound.pitch", 1.2);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, vol, pit);
            }

            if (plugin.getConfig().getBoolean("effects.particles.enabled", true)) {
                int count = plugin.getConfig().getInt("effects.particles.count", 40);
                double ox = plugin.getConfig().getDouble("effects.particles.offset.x", 0.5);
                double oy = plugin.getConfig().getDouble("effects.particles.offset.y", 0.5);
                double oz = plugin.getConfig().getDouble("effects.particles.offset.z", 0.5);
                double speed = plugin.getConfig().getDouble("effects.particles.speed", 0.02);
                p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.5, 0), count, ox, oy, oz, speed);
            }
        }


        doSpawnTeleportIfEnabled(p);
    }


    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        final Player p = e.getPlayer();
        e.quitMessage(null);

        boolean suppressJoinQuit = plugin.getConfig().getBoolean("vanish.suppress-join-quit", true);
        boolean vanished = plugin.getConfig().getBoolean("vanish.enabled", true)
                && plugin.vanish() != null
                && plugin.vanish().isVanished(p.getUniqueId());

        if (plugin.getConfig().getBoolean("welcome.enabled", true)
                && plugin.getConfig().getBoolean("welcome.quit.enabled", true)
                && !(vanished && suppressJoinQuit)) {
            String quitMsg = plugin.messages().getString(
                    "welcome.quit",
                    "<#FF4D4F>[-]</#FF4D4F> <#FFFFFF><player>"
            );
            broadcastActionBar(renderFor(p, quitMsg));
        }

        if (plugin.getConfig().getBoolean("player-data.enabled", true)
                && plugin.getConfig().getBoolean("player-data.track-last-quit", true)) {
            try {
                logQuit(p);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to update last quit for " + p.getName() + ": " + ex.getMessage());
            }
        }
    }


    private Component renderFor(Player p, String raw) {
        if (raw == null) raw = "";
        String fixed = raw.contains("{player}") ? raw.replace("{player}", "<player>") : raw;
        TagResolver resolver = Placeholder.unparsed("player", p.getName());
        return mm.deserialize(fixed, resolver);
    }


    private void broadcastActionBar(Component message) {
        Bukkit.getOnlinePlayers().forEach(pl -> pl.sendActionBar(message));

        String prefix = MessageUtilPrefix();
        Component consoleMessage = prefix.isBlank()
                ? message
                : mm.deserialize(prefix).append(message);
        Bukkit.getConsoleSender().sendMessage(consoleMessage);
    }


    private String MessageUtilPrefix() {

        return MessageService.resolvePrefix(plugin);
    }

    private void doSpawnTeleportIfEnabled(Player p) {
        boolean global = plugin.getConfig().getBoolean("join.teleport-to-spawn.enabled", true);
        boolean allowToggle = plugin.getConfig().getBoolean("join.teleport-to-spawn.allow-player-toggle", true);
        boolean playerOff = allowToggle && p.getPersistentDataContainer()
                .has(SyntrixLobby.AUTOJOIN_OFF, PersistentDataType.BYTE);

        if (!global || playerOff) return;

        String worldName = plugin.getConfig().getString("lobby.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' not found for spawn teleport.");
            return;
        }

        Location loc = new Location(
                world,
                plugin.getConfig().getDouble("lobby.spawn.x", 0.5),
                plugin.getConfig().getDouble("lobby.spawn.y", 80.0),
                plugin.getConfig().getDouble("lobby.spawn.z", 0.5),
                (float) plugin.getConfig().getDouble("lobby.spawn.yaw", 0.0),
                (float) plugin.getConfig().getDouble("lobby.spawn.pitch", 0.0)
        );
        Bukkit.getScheduler().runTask(plugin, () -> p.teleport(loc));
    }

    private File getPlayerDataFile(UUID id) {
        String folderName = plugin.getConfig().getString("player-data.folder", "player-data");
        File dir = new File(plugin.getDataFolder(), folderName);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, id.toString() + ".yml");
    }

    private void logJoin(Player p, File file) throws IOException {
        var yml = YamlConfiguration.loadConfiguration(file);
        String now = fmt.format(Instant.now());
        boolean first = !file.exists();

        yml.set("uuid", p.getUniqueId().toString());
        yml.set("name", p.getName());
        if (first) {
            yml.set("first-join", now);
            yml.set("joins", 1);
        } else {
            yml.set("joins", yml.getInt("joins", 0) + 1);
        }
        yml.set("last-join", now);

        InetSocketAddress addr = p.getAddress();
        if (addr != null && addr.getAddress() != null) {
            String ip = addr.getAddress().getHostAddress();
            yml.set("last-ip", ip);
            List<String> ips = yml.getStringList("ips");
            if (!ips.contains(ip)) {
                ips.add(ip);
                yml.set("ips", ips);
            }
        }

        try (var w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(yml.saveToString());
        }
    }

    private void logQuit(Player p) throws IOException {
        File file = getPlayerDataFile(p.getUniqueId());
        var yml = YamlConfiguration.loadConfiguration(file);
        String now = fmt.format(Instant.now());
        yml.set("last-quit", now);
        if (!yml.isSet("uuid")) yml.set("uuid", p.getUniqueId().toString());
        if (!yml.isSet("name")) yml.set("name", p.getName());
        try (var w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(yml.saveToString());
        }
    }

    private void spawnSmallFirework(Player p) {
        p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL)
                    .withColor(Color.AQUA)
                    .withFade(Color.WHITE)
                    .trail(true)
                    .flicker(true)
                    .build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 1L);
        });
    }
}
