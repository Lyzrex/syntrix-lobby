package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.FlightUtil;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class PlayerJoinListener implements Listener {

    private final SyntrixLobby plugin;
    private static final MiniMessage mm = MiniMessage.miniMessage();

    public PlayerJoinListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        FileConfiguration cfg = plugin.getConfig();

        if (plugin.vanish() != null && cfg.getBoolean("vanish.enabled", true)) {
            plugin.vanish().applyVisibilityForViewer(p);
        }

        boolean global = cfg.getBoolean("join.teleport-to-spawn.enabled", true);
        boolean allowToggle = cfg.getBoolean("join.teleport-to-spawn.allow-player-toggle", true);
        boolean playerOff = allowToggle && p.getPersistentDataContainer().has(SyntrixLobby.AUTOJOIN_OFF, PersistentDataType.BYTE);

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
            applyAutoFlight(p);
        });

        // FIX: Nutzt jetzt den GameLogManager!
        if (plugin.gameLogManager() != null) {
            plugin.gameLogManager().logPlayerSession(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // FIX: Nutzt jetzt den GameLogManager!
        if (plugin.gameLogManager() != null) {
            plugin.gameLogManager().removePlayerSession(e.getPlayer());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        byte state = 0;
        if (player.getAllowFlight()) state |= 0x1;
        if (player.isFlying()) state |= 0x2;
        if (state != 0) player.getPersistentDataContainer().set(SyntrixLobby.FLY_STATE, PersistentDataType.BYTE, state);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            giveLobbyLoadout(plugin, player);
            var doubleJump = plugin.doubleJump();
            if (doubleJump != null) doubleJump.refresh(player);
            applyAutoFlight(player);
            restorePersistedFlight(player);
        });
    }

    public static void giveLobbyLoadout(SyntrixLobby plugin, Player p) {
        PlayerInventory inv = p.getInventory();

        // Navigator (Slot 4)
        if (plugin.getConfig().getBoolean("navigator.enabled", true)) {
            String matName = plugin.getConfig().getString("navigator.trigger.material", "RECOVERY_COMPASS");
            int slot = clamp(plugin.getConfig().getInt("navigator.trigger.slot", 4));
            String name = "<gradient:#2AF598:#009EFD><bold>✦ Server Selector ✦</bold></gradient>";
            inv.setItem(slot, createTaggedItem(plugin, matName, name, List.of("<#8799ae>Right-click to open"), SyntrixLobby.NAV_TAG, true));
        }

        // Gadgets & Profil (Spielerkopf, Slot 7)
        int slotGadget = clamp(plugin.getConfig().getInt("playerHider.item.slot", 7));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(p); // Setzt deinen Kopf!
        meta.displayName(mm.deserialize("<gradient:#FF00FF:#00FFFF><bold>✨ Gadgets & Profile</bold></gradient>"));
        meta.lore(List.of(
                mm.deserialize(""),
                mm.deserialize("<#8799ae>Equip fun gadgets or"),
                mm.deserialize("<#8799ae>change your visibility!"),
                mm.deserialize(""),
                mm.deserialize("<#EEDD44>➤ Right-click to open")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(SyntrixLobby.GADGET_TAG, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(SyntrixLobby.ITEM_LOCK, PersistentDataType.BYTE, (byte) 1);
        head.setItemMeta(meta);
        inv.setItem(slotGadget, head);

        // Jump & Run (Slot 1)
        if (plugin.getConfig().getBoolean("items.jumpAndRun.enabled", true)) {
            String matName = plugin.getConfig().getString("items.jumpAndRun.activator-material", "SLIME_BALL");
            int slot = clamp(plugin.getConfig().getInt("items.jumpAndRun.slot", 1));
            String name = "<gradient:#00c6ff:#0072ff><bold>🞂 Jump & Run</bold></gradient>";
            inv.setItem(slot, createTaggedItem(plugin, matName, name, List.of("<#8799ae>Right-click to start"), SyntrixLobby.JNR_ACTIVE, true));
        }
    }

    private static ItemStack createTaggedItem(SyntrixLobby plugin, String matName, String displayName, List<String> lore, NamespacedKey tag, boolean lock) {
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

    private void applyAutoFlight(Player player) {
        if (!plugin.getConfig().getBoolean("fly.auto-on-join.enabled", true)) return;
        String perm = plugin.getConfig().getString("fly.auto-on-join.permission", "syntrix.fly.auto");
        if (perm != null && !perm.isBlank() && !player.hasPermission(perm)) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        boolean changed = FlightUtil.setFlight(player, true);
        var doubleJump = plugin.doubleJump();
        if (doubleJump != null) doubleJump.disableForFlight(player);
        if (changed && plugin.getConfig().getBoolean("fly.messages", true)) {
            MessageUtil.send(player, plugin, "fly.auto-enabled", "<gray>Flight enabled automatically.</gray>");
        }
    }

    private void restorePersistedFlight(Player player) {
        var pdc = player.getPersistentDataContainer();
        Byte data = pdc.get(SyntrixLobby.FLY_STATE, PersistentDataType.BYTE);
        if (data == null) return;
        pdc.remove(SyntrixLobby.FLY_STATE);
        boolean allow = (data & 0x1) != 0;
        boolean flying = (data & 0x2) != 0;
        if (!allow) return;

        player.setAllowFlight(true);
        player.setFallDistance(0.0F);
        player.setFlying(flying);
        var doubleJump = plugin.doubleJump();
        if (doubleJump != null) doubleJump.disableForFlight(player);
    }
}