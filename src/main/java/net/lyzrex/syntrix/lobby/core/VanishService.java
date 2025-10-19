package net.lyzrex.syntrix.lobby.core;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.db.DBManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public final class VanishService {

    private final SyntrixLobby plugin;
    private final DBManager db;


    private final Set<UUID> vanished = new HashSet<>();

    public VanishService(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
    }


    public void init() {
        try (Connection c = db.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS syntrix_vanish (" +
                             "uuid BINARY(16) PRIMARY KEY," +
                             "vanished TINYINT(1) NOT NULL" +
                             ")")) {
            st.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to ensure vanish table: " + ex.getMessage());
        }

        try (Connection c = db.getConnection();
             var rs = c.createStatement().executeQuery(
                     "SELECT uuid FROM syntrix_vanish WHERE vanished=1")) {
            while (rs.next()) {
                UUID id = db.fromBytes(rs.getBytes(1));
                vanished.add(id);
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to load vanish cache: " + ex.getMessage());
        }
    }


    private boolean canSeeVanished(Player viewer) {
        String perm = plugin.getConfig().getString("vanish.see-permission", "syntrix.vanish.see");
        return perm != null && !perm.isBlank() && viewer.hasPermission(perm);
    }


    public boolean isVanished(UUID id) { return vanished.contains(id); }


    public void setVanished(UUID id, boolean state) {
        if (state) vanished.add(id); else vanished.remove(id);

        try (Connection c = db.getConnection();
             PreparedStatement st = c.prepareStatement(
                     "INSERT INTO syntrix_vanish (uuid, vanished) VALUES(?,?) " +
                             "ON DUPLICATE KEY UPDATE vanished=VALUES(vanished)")) {
            st.setBytes(1, db.toBytes(id));
            st.setBoolean(2, state);
            st.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to write vanish state: " + ex.getMessage());
        }

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
