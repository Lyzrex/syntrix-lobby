package net.lyzrex.syntrix.lobby.manager;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.listeners.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;

public final class ListenerRegistry {
    public static void registerAll(SyntrixLobby plugin) {
        PluginManager pm = Bukkit.getPluginManager();

        pm.registerEvents(new PlayerJoinListener(plugin), plugin);
        pm.registerEvents(new WelcomeListener(plugin), plugin);
        pm.registerEvents(new LobbyProtectionListener(plugin), plugin);
        pm.registerEvents(new InventoryGuardListener(plugin), plugin);
        pm.registerEvents(new NavigatorListener(plugin), plugin);
        pm.registerEvents(new LobbyItemEnforcerListener(plugin), plugin);
        pm.registerEvents(new JumpAndRunListener(plugin, plugin.jumpAndRun()), plugin);
        pm.registerEvents(new JumpPadListener(plugin), plugin);
        if (plugin.doubleJump() != null) pm.registerEvents(plugin.doubleJump(), plugin);
        pm.registerEvents(new HeightGuardListener(plugin), plugin);
        pm.registerEvents(new SignColorListener(plugin), plugin);

        // --- Logging Listeners Direkt Registriert ---

        // 1. Chat & Command Logger
        pm.registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onChat(AsyncChatEvent e) {
                plugin.chatLogManager().log(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()));
            }
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onCmd(PlayerCommandPreprocessEvent e) {
                plugin.chatLogManager().log(e.getPlayer(), e.getMessage());
            }
        }, plugin);

        // 2. Block Logger
        pm.registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onBreak(BlockBreakEvent e) {
                plugin.gameLogManager().logBlockBreak(e.getPlayer(), e.getBlock());
            }
        }, plugin);

        // 3. Container Logger
        pm.registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onClick(InventoryClickEvent e) {
                if (!(e.getWhoClicked() instanceof Player p)) return;
                Inventory top = e.getView().getTopInventory();
                InventoryType type = top.getType();
                if (type == InventoryType.CHEST || type == InventoryType.ENDER_CHEST || type == InventoryType.SHULKER_BOX || type == InventoryType.BARREL || type == InventoryType.HOPPER || type == InventoryType.DISPENSER || type == InventoryType.DROPPER) {
                    Location loc = top.getLocation() != null ? top.getLocation() : p.getLocation();
                    InventoryAction action = e.getAction();
                    if (e.getClickedInventory() == top) {
                        if (action.name().contains("PLACE") || action == InventoryAction.SWAP_WITH_CURSOR) {
                            if (e.getCursor() != null && e.getCursor().getType() != Material.AIR) plugin.gameLogManager().logContainer(p, "ADD", type.name(), loc, e.getCursor());
                        } else if (action.name().contains("PICKUP") || action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                            if (e.getCurrentItem() != null && e.getCurrentItem().getType() != Material.AIR) plugin.gameLogManager().logContainer(p, "REMOVE", type.name(), loc, e.getCurrentItem());
                        }
                    } else if (e.getClickedInventory() != null && e.getClickedInventory().getType() == InventoryType.PLAYER && action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                        if (e.getCurrentItem() != null && e.getCurrentItem().getType() != Material.AIR) plugin.gameLogManager().logContainer(p, "ADD", type.name(), loc, e.getCurrentItem());
                    }
                }
            }
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onDrag(InventoryDragEvent e) {
                if (!(e.getWhoClicked() instanceof Player p)) return;
                Inventory top = e.getView().getTopInventory();
                InventoryType type = top.getType();
                if (type == InventoryType.CHEST || type == InventoryType.ENDER_CHEST || type == InventoryType.SHULKER_BOX || type == InventoryType.BARREL) {
                    if (e.getRawSlots().stream().anyMatch(s -> s < top.getSize())) {
                        Location loc = top.getLocation() != null ? top.getLocation() : p.getLocation();
                        plugin.gameLogManager().logContainer(p, "ADD", type.name(), loc, e.getOldCursor());
                    }
                }
            }
        }, plugin);

        // 4. Item Logger
        pm.registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onPickup(EntityPickupItemEvent e) {
                if (e.getEntity() instanceof Player p) plugin.gameLogManager().logItemAction(p, "PICKUP", e.getItem().getItemStack(), p.getLocation());
            }
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onDrop(PlayerDropItemEvent e) {
                plugin.gameLogManager().logItemAction(e.getPlayer(), "DROP", e.getItemDrop().getItemStack(), e.getPlayer().getLocation());
            }
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onInvMove(InventoryClickEvent e) {
                if (!(e.getWhoClicked() instanceof Player p)) return;
                if (e.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
                    if (e.getCursor() != null && e.getCursor().getType() != Material.AIR && (e.getAction().name().contains("PLACE") || e.getAction() == InventoryAction.SWAP_WITH_CURSOR)) {
                        plugin.gameLogManager().logItemAction(p, "MOVE", e.getCursor(), p.getLocation());
                    } else if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && e.getCurrentItem() != null && e.getCurrentItem().getType() != Material.AIR) {
                        plugin.gameLogManager().logItemAction(p, "SHIFT_MOVE", e.getCurrentItem(), p.getLocation());
                    }
                }
            }
        }, plugin);
    }
}