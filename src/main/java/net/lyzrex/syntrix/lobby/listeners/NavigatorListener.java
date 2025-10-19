package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NavigatorListener implements Listener {

    private final SyntrixLobby plugin;
    private static final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<UUID, Long> lastUseMs = new ConcurrentHashMap<>();

    public NavigatorListener(SyntrixLobby plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getItem() == null || !e.getItem().hasItemMeta()) return;
        ItemMeta meta = e.getItem().getItemMeta();
        if (!meta.getPersistentDataContainer().has(SyntrixLobby.NAV_TAG, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        openGui(e.getPlayer());
    }

    private void openGui(Player p) {
        if (!plugin.getConfig().getBoolean("navigator.enabled", true)) return;

        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("navigator.gui.rows", 3)));
        Inventory inv = Bukkit.createInventory(
                p,
                rows * 9,
                mm.deserialize(plugin.messages().getString("navigator.title", "<green>Navigator</green>"))
        );

        applyFiller(inv);

        List<Map<?, ?>> raw = plugin.getConfig().getMapList("navigator.servers");
        for (Map<?, ?> entry : raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) entry;

            int slot = asInt(map.get("slot"), 0);
            String matName = asStr(map.get("material"), "PAPER");
            String server = asStr(map.get("server"), "");
            String name = asStr(map.get("name"), "Server");

            Material mat = Material.matchMaterial(matName);
            if (mat == null) mat = Material.PAPER;

            ItemStack icon = new ItemStack(mat);
            ItemMeta meta = icon.getItemMeta();

            String disp = plugin.messages().getString("navigator.item-name",
                    "<white><bold>⯈ {name}</bold></white>").replace("{name}", name);
            meta.displayName(mm.deserialize(disp));

            List<String> loreLines = plugin.messages().getStringList("navigator.item-lore");
            if (!loreLines.isEmpty()) {
                List<net.kyori.adventure.text.Component> advLore = new ArrayList<>();
                for (String line : loreLines) {
                    advLore.add(mm.deserialize(line.replace("{server}", server).replace("{name}", name)));
                }
                meta.lore(advLore);
            }

            meta.getPersistentDataContainer().set(SyntrixLobby.NAV_SERVER, PersistentDataType.STRING, server);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            icon.setItemMeta(meta);

            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, icon);
        }

        playSound(p, "navigator.sounds.open", "navigator.sounds.volume", "navigator.sounds.pitch-open");
        p.openInventory(inv);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getCurrentItem() == null) return;

        String expected = plugin.messages().getString("navigator.title", "Navigator")
                .replaceAll("<[^>]+>", "");
        String current = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (!current.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) return;

        e.setCancelled(true);

        ItemMeta meta = e.getCurrentItem().getItemMeta();
        if (meta == null) return;
        if (!meta.getPersistentDataContainer().has(SyntrixLobby.NAV_SERVER, PersistentDataType.STRING)) return;

        String server = meta.getPersistentDataContainer().get(SyntrixLobby.NAV_SERVER, PersistentDataType.STRING);
        if (server == null || server.isBlank()) return;

        playSound(p, "navigator.sounds.click", "navigator.sounds.volume", "navigator.sounds.pitch-click");
        String action = plugin.messages().getString("navigator.actionbar",
                "<gray>Connecting to <white>{server}</white>...</gray>").replace("{server}", server);
        p.sendActionBar(mm.deserialize(action));

        connectBungee(p, server);
    }

    private void applyFiller(Inventory inv) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("navigator.gui");
        if (root == null) return;

        ConfigurationSection filler = root.getConfigurationSection("filler");
        if (filler != null && filler.getBoolean("enabled", false)) {
            List<String> pattern = filler.getStringList("pattern");
            if (pattern != null && !pattern.isEmpty()) {
                int rows = inv.getSize() / 9;
                for (int r = 0; r < Math.min(rows, pattern.size()); r++) {
                    String line = pattern.get(r);
                    for (int c = 0; c < Math.min(9, line.length()); c++) {
                        char key = line.charAt(c);
                        String keyStr = String.valueOf(key);
                        ConfigurationSection cell = filler.getConfigurationSection("map." + keyStr);
                        if (cell == null) continue;
                        ItemStack pane = buildFiller(cell);
                        if (pane != null) inv.setItem(r * 9 + c, pane);
                    }
                }
                return;
            }
        }

        if (root.getBoolean("filler", false)) {
            ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = pane.getItemMeta();
            m.displayName(mm.deserialize("<gray>"));
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            pane.setItemMeta(m);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        }
    }

    private ItemStack buildFiller(ConfigurationSection sec) {
        String matName = sec.getString("material", "GRAY_STAINED_GLASS_PANE");
        Material mat = Material.matchMaterial(matName);
        if (mat == null || mat == Material.AIR) return null;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        String name = sec.getString("name", "");
        if (name != null && !name.isBlank()) meta.displayName(mm.deserialize(name));
        else meta.displayName(null);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(meta);
        return it;
    }

    private void playSound(Player p, String soundPath, String volPath, String pitchPath) {
        String sn = plugin.getConfig().getString(soundPath, "UI_BUTTON_CLICK");
        float vol = (float) plugin.getConfig().getDouble(volPath, 0.8);
        float pit = (float) plugin.getConfig().getDouble(pitchPath, 1.2);
        try {
            p.playSound(p.getLocation(), Sound.valueOf(sn.toUpperCase(Locale.ROOT)), vol, pit);
        } catch (IllegalArgumentException ignored) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, vol, pit);
        }
    }

    private void connectBungee(Player p, String server) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(server);
            p.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to connect " + p.getName() + " to " + server + ": " + ex.getMessage());
        }
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ignored) { return def; }
    }

    private static String asStr(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
