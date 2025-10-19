package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.PlayerHiderService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class PlayerJoinListener implements Listener {

    private final SyntrixLobby plugin;
    private static final MiniMessage mm = MiniMessage.miniMessage();

    public PlayerJoinListener(SyntrixLobby plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        FileConfiguration cfg = plugin.getConfig();


        if (plugin.db() != null && plugin.db().isEnabled()) {
            String ip = "unknown";
            try {
                var addr = p.getAddress();
                if (addr != null && addr.getAddress() != null) {
                    ip = addr.getAddress().getHostAddress();
                }
            } catch (Throwable ignored) {}
            final String ipFinal = ip;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> plugin.db().markJoin(p.getUniqueId(), p.getName(), ipFinal));
        }


        if (plugin.vanish() != null && cfg.getBoolean("vanish.enabled", true)) {
            plugin.vanish().applyVisibilityForViewer(p);
        }
        if (plugin.playerHider() != null && cfg.getBoolean("playerHider.enabled", true)) {
            plugin.playerHider().applyForViewer(p);
        }


        boolean global = cfg.getBoolean("join.teleport-to-spawn.enabled", true);
        boolean allowToggle = cfg.getBoolean("join.teleport-to-spawn.allow-player-toggle", true);
        boolean playerOff = allowToggle && p.getPersistentDataContainer()
                .has(SyntrixLobby.AUTOJOIN_OFF, PersistentDataType.BYTE);

        if (global && !playerOff) {
            Location loc = new Location(
                    plugin.getServer().getWorld(cfg.getString("lobby.world", "world")),
                    cfg.getDouble("lobby.spawn.x", 0.5),
                    cfg.getDouble("lobby.spawn.y", 80.0),
                    cfg.getDouble("lobby.spawn.z", 0.5),
                    (float) cfg.getDouble("lobby.spawn.yaw", 0.0),
                    (float) cfg.getDouble("lobby.spawn.pitch", 0.0)
            );
            Bukkit.getScheduler().runTask(plugin, () -> p.teleport(loc));
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (cfg.getBoolean("join.clear-inventory", true)) {
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
            }
            giveLobbyLoadout(plugin, p);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.db() == null || !plugin.db().isEnabled()) return;
        var p = e.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.db().markQuit(p.getUniqueId()));
    }


    public static void giveLobbyLoadout(SyntrixLobby plugin, Player p) {
        PlayerInventory inv = p.getInventory();

        // Navigator
        if (plugin.getConfig().getBoolean("navigator.enabled", true)) {
            String matName = plugin.getConfig().getString("navigator.trigger.material", "COMPASS");
            int slot = clamp(plugin.getConfig().getInt("navigator.trigger.slot", 4));
            String name = plugin.messages().getString("navigator.items.navigator.name", "<green>✦ Navigator</green>");
            List<String> lore = plugin.messages().getStringList("navigator.item-lore");
            inv.setItem(slot, createTaggedItem(plugin, matName, name, lore, SyntrixLobby.NAV_TAG, true));
        }


        if (plugin.getConfig().getBoolean("playerHider.enabled", true) && plugin.playerHider() != null) {
            int slot = clamp(plugin.getConfig().getInt("playerHider.item.slot", 7));
            var listener = new PlayerHiderListener(plugin, plugin.playerHider());
            inv.setItem(slot, listener.buildHotbarItem());
        }


        if (plugin.getConfig().getBoolean("items.jumpAndRun.enabled", true)) {
            String matName = plugin.getConfig().getString("items.jumpAndRun.activator-material", "SLIME_BALL");
            int slot = clamp(plugin.getConfig().getInt("items.jumpAndRun.slot", 1));
            String name = plugin.messages().getString("jumpandrun.item-name", "<green>🞂 Jump & Run</green>");
            List<String> lore = plugin.messages().getStringList("jumpandrun.item-lore");
            inv.setItem(slot, createTaggedItem(plugin, matName, name, lore, SyntrixLobby.JNR_ACTIVE, true));
        }
    }

    private static ItemStack createTaggedItem(SyntrixLobby plugin, String matName, String displayName, List<String> lore,
                                              NamespacedKey tag, boolean lock) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.PAPER;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm.deserialize(displayName));
        if (lore != null && !lore.isEmpty()) meta.lore(lore.stream().map(mm::deserialize).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(tag, PersistentDataType.BYTE, (byte) 1);
        if (lock) pdc.set(SyntrixLobby.ITEM_LOCK, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private static int clamp(int s) { return Math.max(0, Math.min(8, s)); }
}
