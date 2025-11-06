package net.lyzrex.syntrix.lobby.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerHiderService implements Listener {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Mode> playerModes = new ConcurrentHashMap<>();

    public PlayerHiderService(SyntrixLobby plugin) { this.plugin = plugin; }

    public enum Mode { ALL, VIP, NONE }

    public void setMode(Player viewer, Mode mode, boolean notify) {
        if (viewer == null || mode == null) return;
        playerModes.put(viewer.getUniqueId(), mode);
        updateVisibility(viewer);

        if (notify) {
            String key = switch (mode) {
                case ALL  -> "playerHider.set.all";
                case VIP  -> "playerHider.set.vip";
                case NONE -> "playerHider.set.none";
            };
            viewer.sendActionBar(mm.deserialize(plugin.messages().getString(key, "<gray>Updated</gray>")));
        }
    }

    public void setMode(Player viewer, Mode mode) { setMode(viewer, mode, false); }

    public Mode getMode(Player viewer) {
        String def = plugin.getConfig().getString("playerHider.default", "ALL");
        try { return playerModes.getOrDefault(viewer.getUniqueId(), Mode.valueOf(def.toUpperCase())); }
        catch (Exception ignored) { return Mode.ALL; }
    }

    private void updateVisibility(Player viewer) {
        Mode mode = getMode(viewer);
        boolean vipOnly = mode == Mode.VIP;
        boolean showNone = mode == Mode.NONE;

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(viewer)) continue;

            String exempt = plugin.getConfig().getString("playerHider.exempt-permission", "syntrix.hider.exempt");
            if (exempt != null && !exempt.isBlank() && target.hasPermission(exempt)) {
                viewer.showPlayer(plugin, target);
                continue;
            }

            if (showNone) {
                viewer.hidePlayer(plugin, target);
            } else if (vipOnly) {
                String vipPerm = plugin.getConfig().getString("playerHider.vip-permission", "syntrix.vip");
                if (vipPerm != null && !vipPerm.isBlank() && target.hasPermission(vipPerm)) {
                    viewer.showPlayer(plugin, target);
                } else viewer.hidePlayer(plugin, target);
            } else {
                viewer.showPlayer(plugin, target);
            }
        }
    }

    public void applyForViewer(Player viewer) { updateVisibility(viewer); }
    public void refreshAllViewers() { for (Player p : Bukkit.getOnlinePlayers()) updateVisibility(p); }
    public void refreshAll() { refreshAllViewers(); }

    public void openGui(Player p) {
        int rows = 1;
        Inventory inv = Bukkit.createInventory(
                p, rows * 9,
                mm.deserialize(plugin.messages().getString("playerHider.gui.title", "<green>Player Hider</green>"))
        );

        applyFiller(inv);

        inv.setItem(2, makeGuiItem(Material.SLIME_BALL,
                plugin.messages().getString("playerHider.gui.all", "<green>Show everyone</green>"), Mode.ALL));
        inv.setItem(4, makeGuiItem(Material.GOLD_INGOT,
                plugin.messages().getString("playerHider.gui.vip", "<gold>VIP only</gold>"), Mode.VIP));
        inv.setItem(6, makeGuiItem(Material.BARRIER,
                plugin.messages().getString("playerHider.gui.none", "<red>Show no one</red>"), Mode.NONE));

        if (plugin.getConfig().getBoolean("playerHider.gui.sound.open.enabled", true)) {
            play(p,
                    plugin.getConfig().getString("playerHider.gui.sound.open.type", "UI_BUTTON_CLICK"),
                    (float) plugin.getConfig().getDouble("playerHider.gui.sound.open.volume", 0.8),
                    (float) plugin.getConfig().getDouble("playerHider.gui.sound.open.pitch", 1.2)
            );
        }

        p.openInventory(inv);
    }

    private void applyFiller(Inventory inv) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("playerHider.gui.filler");
        if (sec == null || !sec.getBoolean("enabled", false)) {
            return;
        }

        List<String> pattern = sec.getStringList("pattern");
        ConfigurationSection map = sec.getConfigurationSection("map");
        if (pattern == null || pattern.isEmpty() || map == null) {
            return;
        }

        final int size = inv.getSize();
        final int rows = Math.min(size / 9, Math.max(1, pattern.size()));

        Runnable setter = () -> {
            for (int r = 0; r < rows; r++) {
                String line = pattern.get(r);
                int cols = Math.min(9, line.length());

                for (int c = 0; c < cols; c++) {
                    char ch = line.charAt(c);
                    if (ch == '.' || ch == ' ') continue;

                    ConfigurationSection cell = map.getConfigurationSection(String.valueOf(ch));
                    if (cell == null) {
                        plugin.getLogger().fine("[PlayerHider] filler: no cell for key '" + ch + "'");
                        continue;
                    }

                    String matName = cell.getString("material", "GRAY_STAINED_GLASS_PANE");
                    Material mat = Material.matchMaterial(matName);
                    if (mat == null) {
                        plugin.getLogger().warning("[PlayerHider] unknown material '" + matName + "', using GRAY_STAINED_GLASS_PANE");
                        mat = Material.GRAY_STAINED_GLASS_PANE;
                    }

                    ItemStack pane = new ItemStack(mat);
                    ItemMeta meta = pane.getItemMeta();
                    String name = cell.getString("name", "");
                    if (name != null && !name.isEmpty()) {
                        meta.displayName(MiniMessage.miniMessage().deserialize(name));
                    }
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                    pane.setItemMeta(meta);

                    int idx = r * 9 + c;
                    if (idx >= 0 && idx < size) inv.setItem(idx, pane);
                }
            }
        };

        setter.run();
        Bukkit.getScheduler().runTask(plugin, setter);
    }


    private ItemStack makeGuiItem(Material mat, String name, Mode mode) {
        ItemStack item = new ItemStack(mat == null ? Material.PAPER : mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(mm.deserialize(name == null ? "<white>Option</white>" : name));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        meta.getPersistentDataContainer().set(SyntrixLobby.SILENT_TOG, PersistentDataType.STRING, mode.name());
        item.setItemMeta(meta);
        return item;
    }

    private void play(Player p, String sound, float vol, float pitch) {
        Sound resolved = SoundUtil.resolve(sound, Sound.UI_BUTTON_CLICK);
        p.playSound(p.getLocation(), resolved, vol, pitch);
    }
}