package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.commands.BuildCommand;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.persistence.PersistentDataType;


public final class LobbyProtectionListener implements Listener {

    private final SyntrixLobby plugin;

    public LobbyProtectionListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }



    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!plugin.getConfig().getBoolean("flags.noBuild", true)) return;
        Player p = e.getPlayer();
        if (BuildCommand.hasBypass(p.getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (!plugin.getConfig().getBoolean("flags.noBuild", true)) return;
        Player p = e.getPlayer();
        if (BuildCommand.hasBypass(p.getUniqueId())) return;
        e.setCancelled(true);
    }



    @EventHandler
    public void onItemDrop(PlayerDropItemEvent e) {
        if (!plugin.getConfig().getBoolean("flags.noDrop", true)) return;

        e.setCancelled(true);
    }

    @EventHandler
    public void onItemPickup(PlayerAttemptPickupItemEvent e) {
        if (!plugin.getConfig().getBoolean("flags.noDrop", true)) return;
        e.setCancelled(true);
    }



    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;

        boolean damagerIsPlayer = e.getDamager() instanceof Player;
        boolean damagerProjectileFromPlayer =
                (e.getDamager() instanceof Projectile proj) && (proj.getShooter() instanceof Player);

        if (damagerIsPlayer || damagerProjectileFromPlayer) {
            e.setCancelled(true);
        }
    }



    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (!plugin.getConfig().getBoolean("protections.no-hunger", true)) return;
        if (e.getEntity() instanceof Player p) {
            if (e.getFoodLevel() < p.getFoodLevel()) {
                e.setCancelled(true);
                p.setFoodLevel(20);
                p.setSaturation(20f);
            }
        }
    }



    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Material type = e.getClickedBlock().getType();

        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (plugin.getConfig().getBoolean("protections.block-doors", true) && Tag.DOORS.isTagged(type)) {
                e.setCancelled(true); return;
            }
            if (plugin.getConfig().getBoolean("protections.block-trapdoors", true) && Tag.TRAPDOORS.isTagged(type)) {
                e.setCancelled(true); return;
            }
            if (plugin.getConfig().getBoolean("protections.block-fence-gates", true) && Tag.FENCE_GATES.isTagged(type)) {
                e.setCancelled(true); return;
            }
            if (plugin.getConfig().getBoolean("protections.block-note-blocks", true) && type == Material.NOTE_BLOCK) {
                e.setCancelled(true); return;
            }
            if (plugin.getConfig().getBoolean("protections.block-jukebox", true) && type == Material.JUKEBOX) {
                e.setCancelled(true); return;
            }
        }

        if (e.getAction() == Action.PHYSICAL
                && plugin.getConfig().getBoolean("protections.protect-farmland", true)
                && type == Material.FARMLAND) {
            e.setCancelled(true);
        }
    }
}
