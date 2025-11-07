package net.lyzrex.syntrix.lobby.core;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public final class VanishService {

    private static final String DATA_FILE = "vanish-data.yml";

    private final SyntrixLobby plugin;

    private final Set<UUID> vanished = new HashSet<>();
    private File dataFile;
    private FileConfiguration dataConfig;

    public VanishService(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void init() {
        load();
        refreshAll();
    }

    private void load() {
        if (dataFile == null) {
            dataFile = new File(plugin.getDataFolder(), DATA_FILE);
        }
        if (!dataFile.exists()) {
            vanished.clear();
            dataConfig = new YamlConfiguration();
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        List<String> entries = dataConfig.getStringList("vanished");
        vanished.clear();
        for (String raw : entries) {
            try {
                vanished.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("[Vanish] Ignoring invalid UUID in vanish-data.yml: " + raw);
            }
        }
    }
    private void save() {
        if (dataConfig == null) {
            dataConfig = new YamlConfiguration();
        }
        dataConfig.set("vanished", vanished.stream().map(UUID::toString).toList());
        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("[Vanish] Could not save vanish-data.yml: " + ex.getMessage());
        }
    }

    private boolean canSeeVanished(Player viewer) {
        String perm = plugin.getConfig().getString("vanish.see-permission", "syntrix.vanish.see");
        return perm != null && !perm.isBlank() && viewer.hasPermission(perm);
    }


    public boolean isVanished(UUID id) { return vanished.contains(id); }


    public void setVanished(UUID id, boolean state) {
        if (state) vanished.add(id); else vanished.remove(id);

        save();

        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) applyVisibility(p);
    }


    public void applyVisibility(Player vanishedPlayer) {
        boolean isVanished = isVanished(vanishedPlayer.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(vanishedPlayer.getUniqueId())) continue;
            if (isVanished && !canSeeVanished(viewer)) {
                viewer.hidePlayer(plugin, vanishedPlayer);
            } else {
                viewer.showPlayer(plugin, vanishedPlayer);
            }
        }
    }


    public void applyVisibilityForViewer(Player viewer) {
        boolean bypass = canSeeVanished(viewer);
        for (UUID id : new HashSet<>(vanished)) {
            Player vp = Bukkit.getPlayer(id);
            if (vp == null || !vp.isOnline()) continue;
            if (!bypass) viewer.hidePlayer(plugin, vp);
            else viewer.showPlayer(plugin, vp);
        }
    }


    public boolean applyOnJoin(Player p) {
        boolean state = isVanished(p.getUniqueId());


        boolean autoEnabled = plugin.getConfig().getBoolean("vanish.auto.enabled", false);
        String autoPerm = plugin.getConfig().getString("vanish.auto.permission", "syntrix.vanish.auto");
        if (autoEnabled && (autoPerm == null || autoPerm.isBlank() || p.hasPermission(autoPerm))) {
            if (!state) {
                setVanished(p.getUniqueId(), true);
                state = true;
            }
        }


        applyVisibility(p);
        applyVisibilityForViewer(p);

        return state;
    }


    public void refreshAll() {

        for (UUID id : new HashSet<>(vanished)) {
            Player vp = Bukkit.getPlayer(id);
            if (vp != null && vp.isOnline()) applyVisibility(vp);
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyVisibilityForViewer(viewer);
        }
    }
}
