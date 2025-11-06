package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class InventoryGuardListener implements Listener {

    private static final int OFFHAND_PLAYER_SLOT = 40;
    private static final int OFFHAND_RAW_SLOT = 45;
    private final SyntrixLobby plugin;

    public InventoryGuardListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }



    private boolean isLocked(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        return meta.getPersistentDataContainer().has(SyntrixLobby.ITEM_LOCK, PersistentDataType.BYTE);
    }

    private boolean bypass(Player player) {
        if (player == null) {
            return false;
        }
        if (!player.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE)) {
            return false;
        }
        String perm = plugin.getConfig().getString("protections.bypass-permission", "syntrix.protections.bypass");
        return perm == null || perm.isBlank() || player.hasPermission(perm);
    }


    @EventHandler
    public void onClick(InventoryClickEvent e) {

        Player actor = null;
        if (e.getWhoClicked() instanceof Player player) {
            actor = player;
        }
        if (bypass(actor)) {
            return;
        }

        if (e.isShiftClick() && isLocked(e.getCurrentItem())) {
            e.setCancelled(true);
            return;
        }


        if (e.getHotbarButton() != -1 && actor != null) {
            ItemStack fromHotbar = actor.getInventory().getItem(e.getHotbarButton());
            if (isLocked(fromHotbar) || isLocked(e.getCurrentItem())) {
                e.setCancelled(true);
                return;
            }
        }


        if (isLocked(e.getCursor()) || isLocked(e.getCurrentItem())) {
            e.setCancelled(true);
            return;
        }


        if (e.getClickedInventory() instanceof PlayerInventory && e.getSlot() == OFFHAND_PLAYER_SLOT) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Player actor = null;
        if (e.getWhoClicked() instanceof Player player) {
            actor = player;
        }
        if (bypass(actor)) {
            return;
        }

        if (isLocked(e.getOldCursor())) {
            e.setCancelled(true);
            return;
        }

        InventoryView view = e.getView();
        for (int raw : e.getRawSlots()) {
            if (raw == OFFHAND_RAW_SLOT) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (bypass(e.getPlayer())) {
            return;
        }
        if (isLocked(e.getMainHandItem()) || isLocked(e.getOffHandItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (bypass(e.getPlayer())) {
            return;
        }
        if (isLocked(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }
}