package net.lyzrex.syntrix.lobby.listeners;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.PlayerHiderService;
import net.lyzrex.syntrix.lobby.core.PlayerHiderService.Mode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerHiderListener implements Listener {

    private final SyntrixLobby plugin;
    private final PlayerHiderService service;
    private static final MiniMessage mm = MiniMessage.miniMessage();


    private final NamespacedKey GUI_KEY;
    private final NamespacedKey ITEM_TAG;


    private final Map<UUID, Long> cooldownMs = new ConcurrentHashMap<>();

    public PlayerHiderListener(SyntrixLobby plugin, PlayerHiderService service) {
        this.plugin = plugin;
        this.service = service;
        this.GUI_KEY  = new NamespacedKey(plugin, "player_hider_gui");
        this.ITEM_TAG = new NamespacedKey(plugin, "player_hider_item");
    }



    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getPlayer().getInventory().getItemInMainHand();
        if (item == null || !item.hasItemMeta()) return;

        Byte mark = item.getItemMeta().getPersistentDataContainer().get(ITEM_TAG, PersistentDataType.BYTE);
        if (mark == null) return;

        e.setCancelled(true);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);

        long now = System.currentTimeMillis();
        long cd = Math.max(0L, plugin.getConfig().getLong("playerHider.item.cooldownMs", 300));
        long last = cooldownMs.getOrDefault(e.getPlayer().getUniqueId(), 0L);
        if (now - last < cd) return;
        cooldownMs.put(e.getPlayer().getUniqueId(), now);

        openGui(e.getPlayer());
    }



    private void openGui(Player p) {
        String title = plugin.messages().getString("playerHider.gui.title", "<#2AF598>Player Hider</#2AF598>");
        Inventory inv = Bukkit.createInventory(p, 9, mm.deserialize(title));


        applyFiller(inv);


        inv.setItem(2, guiItem(Material.LIME_DYE,   "playerHider.gui.all",  "<green>Show everyone</green>", Mode.ALL));
        inv.setItem(4, guiItem(Material.GOLD_INGOT, "playerHider.gui.vip",  "<gold>VIP only</gold>",        Mode.VIP));
        inv.setItem(6, guiItem(Material.BARRIER,    "playerHider.gui.none", "<red>Show no one</red>",       Mode.NONE));

        if (plugin.getConfig().getBoolean("playerHider.gui.sound.open.enabled", true)) {
            play(p,
                    plugin.getConfig().getString("playerHider.gui.sound.open.type", "UI_BUTTON_CLICK"),
                    (float) plugin.getConfig().getDouble("playerHider.gui.sound.open.volume", 0.8),
                    (float) plugin.getConfig().getDouble("playerHider.gui.sound.open.pitch", 1.2)
            );
        }

        p.openInventory(inv);
    }


    private boolean isOurGui(org.bukkit.inventory.InventoryView view) {
        String expectedMini = plugin.messages().getString("playerHider.gui.title", "<#2AF598>Player Hider</#2AF598>");
        String expectedPlain = PlainTextComponentSerializer.plainText().serialize(mm.deserialize(expectedMini));
        String currentPlain  = PlainTextComponentSerializer.plainText().serialize(view.title());
        return currentPlain.equalsIgnoreCase(expectedPlain);
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (!isOurGui(e.getView())) return;


        e.setCancelled(true);


        if (e.getClickedInventory() == null || e.getView().getTopInventory() != e.getClickedInventory()) return;

        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;

        String modeStr = it.getItemMeta().getPersistentDataContainer().get(GUI_KEY, PersistentDataType.STRING);
        if (modeStr == null) return;

        Mode mode;
        try { mode = Mode.valueOf(modeStr.toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { return; }

        service.setMode(p, mode);

        String key = switch (mode) {
            case ALL  -> "playerHider.set.all";
            case VIP  -> "playerHider.set.vip";
            case NONE -> "playerHider.set.none";
        };
        p.sendActionBar(mm.deserialize(plugin.messages().getString(key, "<#8799ae>Updated.</#8799ae>")));

        if (plugin.getConfig().getBoolean("playerHider.gui.sound.select.enabled", true)) {
            play(p,
                    plugin.getConfig().getString("playerHider.gui.sound.select.type", "BLOCK_NOTE_BLOCK_BELL"),
                    (float) plugin.getConfig().getDouble("playerHider.gui.sound.select.volume", 0.9),
                    (float) plugin.getConfig().getDouble("playerHider.gui.sound.select.pitch", 1.5)
            );
        }

        p.closeInventory();
    }


    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (isOurGui(e.getView())) {
            e.setCancelled(true);
        }
    }



    private void applyFiller(Inventory inv) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("playerHider.gui.filler");
        if (sec == null || !sec.getBoolean("enabled", false)) return;

        var pattern = sec.getStringList("pattern");
        ConfigurationSection map = sec.getConfigurationSection("map");
        if (pattern == null || pattern.isEmpty() || map == null) return;

        int rows = Math.min(inv.getSize() / 9, Math.max(1, pattern.size()));

        for (int r = 0; r < rows; r++) {
            String line = pattern.get(r);
            int cols = Math.min(9, line.length());
            for (int c = 0; c < cols; c++) {
                String key = String.valueOf(line.charAt(c));
                if (!map.isConfigurationSection(key)) continue;

                ConfigurationSection cell = map.getConfigurationSection(key);
                String matName = cell.getString("material", "GRAY_STAINED_GLASS_PANE");
                Material mat = Material.matchMaterial(matName);
                if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;

                ItemStack pane = new ItemStack(mat);
                ItemMeta meta = pane.getItemMeta();
                String name = cell.getString("name", "");
                if (name != null) meta.displayName(mm.deserialize(name));
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
                pane.setItemMeta(meta);

                inv.setItem(r * 9 + c, pane);
            }
        }


        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int r = 0; r < rows; r++) {
                String line = pattern.get(r);
                int cols = Math.min(9, line.length());
                for (int c = 0; c < cols; c++) {
                    int idx = r * 9 + c;
                    if (inv.getItem(idx) != null) continue;

                    String key = String.valueOf(line.charAt(c));
                    if (!map.isConfigurationSection(key)) continue;

                    ConfigurationSection cell = map.getConfigurationSection(key);
                    String matName = cell.getString("material", "GRAY_STAINED_GLASS_PANE");
                    Material mat = Material.matchMaterial(matName);
                    if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;

                    ItemStack pane = new ItemStack(mat);
                    ItemMeta meta = pane.getItemMeta();
                    String name = cell.getString("name", "");
                    if (name != null) meta.displayName(mm.deserialize(name));
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
                    pane.setItemMeta(meta);

                    inv.setItem(idx, pane);
                }
            }
        });
    }



    private ItemStack guiItem(Material mat, String msgKey, String fallback, Mode mode) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm.deserialize(plugin.messages().getString(msgKey, fallback)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(GUI_KEY, PersistentDataType.STRING, mode.name());
        it.setItemMeta(meta);
        return it;
    }

    private void play(Player p, String name, float vol, float pitch) {
        Sound s;
        try { s = Sound.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { s = Sound.UI_BUTTON_CLICK; }
        p.playSound(p.getLocation(), s, vol, pitch);
    }


    public ItemStack buildHotbarItem() {
        String matName = plugin.getConfig().getString("playerHider.item.material", "NETHER_STAR");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            plugin.getLogger().warning("[PlayerHider] Unknown material '" + matName + "', using NETHER_STAR.");
            mat = Material.NETHER_STAR;
        }

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(mm.deserialize(plugin.messages().getString(
                "playerHider.item.name", "<#FFFFFF><bold>Player Hider</bold>"
        )));
        var lore = plugin.messages().getStringList("playerHider.item.lore");
        if (!lore.isEmpty()) meta.lore(lore.stream().map(mm::deserialize).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(ITEM_TAG, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }
}
