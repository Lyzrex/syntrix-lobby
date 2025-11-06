package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class JumpAndRunService {

    private static final String DATA_FILE = "jumpandrun-data.yml";

    private final SyntrixLobby plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, LeaderboardEntry> records = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataConfig;
    private SimpleHologram hologram;

    public JumpAndRunService(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void init() {
        loadData();
        reloadSettings();
        restoreHologram();
    }

    public void reload() {
        loadData();
        reloadSettings();
        if (hologram != null) {
            Location base = hologram.base();
            hologram.destroy();
            hologram = new SimpleHologram(base, hologramSpacing());
        }
        updateHologram();
    }

    public void shutdown() {
        saveData();
        if (hologram != null) {
            hologram.destroy();
            hologram = null;
        }
    }

    public LeaderboardResult recordCompletion(Player player, long durationMillis) {
        UUID uuid = player.getUniqueId();
        LeaderboardEntry previous = records.get(uuid);

        int completions = previous == null ? 1 : previous.completions() + 1;
        long previousBest = previous == null ? Long.MAX_VALUE : previous.bestTimeMillis();
        long previousTimestamp = previous == null ? 0L : previous.bestTimestampMillis();
        String name = player.getName();

        boolean isNewRecord = durationMillis < previousBest;
        long bestTime = isNewRecord ? durationMillis : previousBest;
        long recordTimestamp = isNewRecord ? System.currentTimeMillis() : previousTimestamp;

        LeaderboardEntry updated = new LeaderboardEntry(uuid, name, bestTime, recordTimestamp, completions);
        records.put(uuid, updated);
        saveData();

        if (isNewRecord || previous == null || !previous.name().equals(name)) {
            updateHologram();
        }

        return new LeaderboardResult(updated.bestTimeMillis(), isNewRecord);
    }

    public List<LeaderboardEntry> top(int limit) {
        return records.values().stream()
                .sorted(Comparator.comparingLong(LeaderboardEntry::bestTimeMillis))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public void spawnHologram(Location base) {
        if (base.getWorld() == null) {
            throw new IllegalArgumentException("Hologram base location requires a world");
        }
        if (hologram != null) {
            hologram.destroy();
        }
        hologram = new SimpleHologram(base.clone(), hologramSpacing());
        updateHologram();
        saveHologramLocation(base);
    }

    public void removeHologram() {
        if (hologram != null) {
            hologram.destroy();
            hologram = null;
        }
        if (dataConfig != null) {
            dataConfig.set("hologram", null);
            saveSilently();
        }
    }

    public String leaderboardPermission() {
        return plugin.getConfig().getString("items.jumpAndRun.leaderboard.permission", "syntrix.jumpandrun.hologram");
    }

    public double hologramSpacing() {
        return Math.max(0.1, plugin.getConfig().getDouble("items.jumpAndRun.leaderboard.spacing", 0.3));
    }

    public String leaderboardTitle() {
        return plugin.messages().getString("jumpandrun.leaderboard.title",
                "<gradient:#2AF598:#009EFD><bold>Jump & Run • Top 10</bold></gradient>");
    }

    public String leaderboardLine() {
        return plugin.messages().getString("jumpandrun.leaderboard.line",
                "<#8799ae>{pos}. <#FFFFFF>{player}</#FFFFFF> <#8799ae>- {time}");
    }

    public String leaderboardEmpty() {
        return plugin.messages().getString("jumpandrun.leaderboard.empty",
                "<#8799ae>No runs recorded yet.</#8799ae>");
    }

    private void loadData() {
        if (dataFile == null) {
            dataFile = new File(plugin.getDataFolder(), DATA_FILE);
        }
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        if (!dataFile.exists()) {
            records.clear();
            dataConfig = new YamlConfiguration();
            return;
        }
        dataConfig = new YamlConfiguration();
        try {
            dataConfig.load(dataFile);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to load jumpandrun-data.yml", ex);
        }
        records.clear();
        ConfigurationSection section = dataConfig.getConfigurationSection("records");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                long time = section.getLong(key + ".best", Long.MAX_VALUE);
                String name = section.getString(key + ".name", uuid.toString());
                int completions = section.getInt(key + ".completions", time < Long.MAX_VALUE ? 1 : 0);
                long timestamp = section.getLong(key + ".timestamp", 0L);
                if (time < Long.MAX_VALUE) {
                    if (completions <= 0) {
                        completions = 1;
                    }
                    records.put(uuid, new LeaderboardEntry(uuid, name, time, timestamp, completions));
                }
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }
        dataConfig.set("records", null);
        ConfigurationSection section = dataConfig.createSection("records");
        for (LeaderboardEntry entry : records.values()) {
            String key = entry.uuid().toString();
            section.set(key + ".name", entry.name());
            section.set(key + ".best", entry.bestTimeMillis());
            section.set(key + ".completions", entry.completions());
            section.set(key + ".timestamp", entry.bestTimestampMillis());
        }
        saveSilently();
    }

    private void saveSilently() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to save jumpandrun-data.yml", ex);
        }
    }

    private void reloadSettings() {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }
    }

    private void restoreHologram() {
        if (dataConfig == null) {
            return;
        }
        ConfigurationSection section = dataConfig.getConfigurationSection("hologram.location");
        if (section == null) {
            return;
        }
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) {
            return;
        }
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw", 0.0);
        float pitch = (float) section.getDouble("pitch", 0.0);
        Location base = new Location(world, x, y, z, yaw, pitch);
        hologram = new SimpleHologram(base, hologramSpacing());
        updateHologram();
    }

    private void saveHologramLocation(Location base) {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }
        ConfigurationSection section = dataConfig.createSection("hologram.location");
        section.set("world", base.getWorld().getName());
        section.set("x", base.getX());
        section.set("y", base.getY());
        section.set("z", base.getZ());
        section.set("yaw", base.getYaw());
        section.set("pitch", base.getPitch());
        saveSilently();
    }

    private void updateHologram() {
        if (hologram == null) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(miniMessage.deserialize(leaderboardTitle()));
        List<LeaderboardEntry> top = top(10);
        if (top.isEmpty()) {
            lines.add(miniMessage.deserialize(leaderboardEmpty()));
        } else {
            int position = 1;
            for (LeaderboardEntry entry : top) {
                String formatted = leaderboardLine()
                        .replace("{pos}", Integer.toString(position))
                        .replace("{player}", entry.name())
                        .replace("{time}", formatDuration(entry.bestTimeMillis()));
                lines.add(miniMessage.deserialize(formatted));
                position++;
            }
        }
        hologram.update(lines);
    }

    public String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        long centiseconds = duration.minusMinutes(minutes).minusSeconds(seconds).toMillis() / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centiseconds);
    }

    public PlayerStats stats(UUID uuid) {
        LeaderboardEntry entry = records.get(uuid);
        if (entry == null) {
            return new PlayerStats(0, null, null);
        }
        Long bestTime = entry.bestTimeMillis() >= Long.MAX_VALUE ? null : entry.bestTimeMillis();
        Long timestamp = entry.bestTimestampMillis() <= 0 ? null : entry.bestTimestampMillis();
        return new PlayerStats(entry.completions(), bestTime, timestamp);
    }

    public record LeaderboardResult(long bestTimeMillis, boolean newRecord) {
    }

    public record LeaderboardEntry(UUID uuid, String name, long bestTimeMillis, long bestTimestampMillis,
                                   int completions) {
    }

    public record PlayerStats(int completions, Long bestTimeMillis, Long bestTimestampMillis) {
    }

    private final class SimpleHologram {
        private final Location base;
        private final double spacing;
        private final List<ArmorStand> stands = new ArrayList<>();

        private SimpleHologram(Location base, double spacing) {
            this.base = base;
            this.spacing = spacing;
        }

        Location base() {
            return base.clone();
        }

        void update(List<Component> lines) {
            ensureSize(lines.size());
            for (int i = 0; i < stands.size(); i++) {
                ArmorStand stand = stands.get(i);
                if (i < lines.size()) {
                    stand.customName(lines.get(i));
                    stand.setCustomNameVisible(true);
                    stand.setMarker(true);
                    stand.setInvisible(true);
                } else {
                    stand.remove();
                }
            }
            pruneExcess(lines.size());
        }

        private void ensureSize(int required) {
            for (int i = stands.size(); i < required; i++) {
                Location loc = base.clone().subtract(new Vector(0, i * spacing, 0));
                ArmorStand stand = base.getWorld().spawn(loc, ArmorStand.class, s -> {
                    s.setInvisible(true);
                    s.setMarker(true);
                    s.setGravity(false);
                    s.setPersistent(false);
                    s.setSmall(true);
                    s.setCustomNameVisible(true);
                    s.setArms(false);
                    s.setBasePlate(false);
                    s.setCollidable(false);
                });
                stands.add(stand);
            }
        }

        private void pruneExcess(int keep) {
            for (int i = stands.size() - 1; i >= keep; i--) {
                ArmorStand stand = stands.remove(i);
                stand.remove();
            }
        }

        void destroy() {
            for (ArmorStand stand : stands) {
                stand.remove();
            }
            stands.clear();
        }
    }
}
