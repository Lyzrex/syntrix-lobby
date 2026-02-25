package net.lyzrex.syntrix.lobby.manager;

import de.murmelmeister.murmelapi.MurmelAPI;
import de.murmelmeister.murmelapi.group.Group;
import de.murmelmeister.murmelapi.group.color.GroupColor;
import de.murmelmeister.murmelapi.group.color.GroupColorType;
import de.murmelmeister.murmelapi.user.User;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.util.*;

public class GadgetManager implements Listener {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final NamespacedKey GADGET_ID;

    private final Map<UUID, Long> dashCooldown = new HashMap<>();
    private final Map<UUID, Boolean> hiddenPlayers = new HashMap<>();
    private final DecimalFormat df = new DecimalFormat("#,##0.00");

    public static class GadgetHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    public GadgetManager(SyntrixLobby plugin) {
        this.plugin = plugin;
        this.GADGET_ID = new NamespacedKey(plugin, "gadget_id");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInteractHead(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getItemMeta().getPersistentDataContainer().has(SyntrixLobby.GADGET_TAG, PersistentDataType.BYTE)) {
            e.setCancelled(true);
            openGUI(e.getPlayer());
        }
    }

    private void openGUI(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = MurmelAPI.getUserProvider().findByMojangId(p.getUniqueId());
            double money = 0, bank = 0;
            int level = 0;
            String rankDisplay = "<#8799ae>Player";

            if (user != null) {
                Double m = MurmelAPI.getDatabase().query("SELECT balance FROM economy_balances WHERE user_id = ?", null, rs -> rs.getDouble("balance"), s -> s.setInt(1, user.id()));
                if (m != null) money = m;

                Double b = MurmelAPI.getDatabase().query("SELECT balance FROM bank_accounts WHERE owner_id = ?", null, rs -> rs.getDouble("balance"), s -> s.setInt(1, user.id()));
                if (b != null) bank = b;

                Double l = MurmelAPI.getDatabase().query("SELECT global_level FROM player_skills WHERE user_id = ?", null, rs -> rs.getDouble("global_level"), s -> s.setInt(1, user.id()));
                if (l != null) level = l.intValue();

                var parents = MurmelAPI.getUserParentProvider().getParents(user.id());
                Group group = parents.isEmpty() ? MurmelAPI.getGroupProvider().findById(1) : parents.stream().map(parent -> MurmelAPI.getGroupProvider().findById(parent.parentId())).filter(Objects::nonNull).max(Comparator.comparingInt(Group::priority)).orElse(null);
                if (group != null) {
                    String prettyName = group.groupName().substring(0, 1).toUpperCase() + group.groupName().substring(1).toLowerCase();
                    GroupColor colorData = MurmelAPI.getGroupColorProvider().getGroupColor(group.id(), GroupColorType.CHAT_COLOR.getId());
                    rankDisplay = (colorData != null && colorData.value() != null) ? convertLegacyColor(colorData.value()) + prettyName : "<#8799ae>" + prettyName;
                }
            }

            final double fMoney = money;
            final double fBank = bank;
            final int fLevel = level;
            final String fRank = rankDisplay;

            Bukkit.getScheduler().runTask(plugin, () -> {
                Inventory gui = Bukkit.createInventory(new GadgetHolder(), 27, mm.deserialize("<gradient:#FF00FF:#00FFFF><bold>Gadgets & Profile</bold></gradient>"));

                ItemStack fill = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
                ItemMeta fm = fill.getItemMeta();
                fm.displayName(mm.deserialize(" "));
                fill.setItemMeta(fm);
                for(int i=0; i<27; i++) gui.setItem(i, fill);

                gui.setItem(10, createGadget(Material.ENDER_PEARL, "<#D580FF>Pearl Ride", "pearl", "<#8799ae>Ride your Enderpearls!"));
                gui.setItem(11, createGadget(Material.FISHING_ROD, "<#009EFD>Grappling Hook", "hook", "<#8799ae>Pull yourself like Batman."));

                ItemStack profile = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta pm = (SkullMeta) profile.getItemMeta();
                pm.setOwningPlayer(p);
                pm.displayName(mm.deserialize("<gradient:#2AF598:#009EFD><bold>Your Profile</bold></gradient>"));
                pm.lore(List.of(
                        mm.deserialize(""),
                        mm.deserialize("<gray>Rank: " + fRank),
                        mm.deserialize("<gray>Level: <#00C9FF>⭐ " + fLevel),
                        mm.deserialize("<gray>Cash: <#2AF598>" + df.format(fMoney) + " $"),
                        mm.deserialize("<gray>Bank: <#2AF598>" + df.format(fBank) + " $")
                ));
                profile.setItemMeta(pm);
                gui.setItem(13, profile);

                gui.setItem(15, createGadget(Material.FEATHER, "<#2AF598>Dash", "dash", "<#8799ae>Boost yourself forward."));

                boolean isHidden = hiddenPlayers.getOrDefault(p.getUniqueId(), false);
                Material visMat = isHidden ? Material.RED_DYE : Material.LIME_DYE;
                String visName = isHidden ? "<#FF4D4F>Players: Hidden" : "<#2AF598>Players: Visible";
                gui.setItem(16, createGadget(visMat, visName, "visibility", "<#8799ae>Click to toggle player visibility."));

                gui.setItem(22, createGadget(Material.BARRIER, "<#FF4D4F>Clear Gadget", "clear", "<#8799ae>Remove your active gadget."));

                p.openInventory(gui);
                p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
            });
        });
    }

    @EventHandler
    public void onGUIClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof GadgetHolder) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            String id = clicked.getItemMeta().getPersistentDataContainer().get(GADGET_ID, PersistentDataType.STRING);
            if (id == null) return;

            if (id.equals("visibility")) {
                boolean hidden = hiddenPlayers.getOrDefault(p.getUniqueId(), false);
                hiddenPlayers.put(p.getUniqueId(), !hidden);
                updateVisibility(p);
                openGUI(p);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                return;
            }

            p.closeInventory();
            p.getInventory().setItem(2, null);

            if (id.equals("clear")) {
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                return;
            }

            ItemStack gadget = clicked.clone();
            ItemMeta m = gadget.getItemMeta();
            m.getPersistentDataContainer().set(SyntrixLobby.ITEM_LOCK, PersistentDataType.BYTE, (byte)1);
            gadget.setItemMeta(m);
            p.getInventory().setItem(2, gadget);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        }
    }

    // --- PEARL RIDE FIX ---
    @EventHandler
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.EnderPearl pearl && e.getEntity().getShooter() instanceof Player p) {
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.hasItemMeta() && "pearl".equals(item.getItemMeta().getPersistentDataContainer().get(GADGET_ID, PersistentDataType.STRING))) {
                pearl.addPassenger(p);
                // Ersetzt die verbrauchte Enderperle sofort!
                ItemStack gadget = item.clone();
                gadget.setAmount(1);
                Bukkit.getScheduler().runTask(plugin, () -> p.getInventory().setItem(2, gadget));
            }
        }
    }

    // Verhindert echten Teleport, damit der Spieler beim Reiten der Perle nicht in Blöcke glitcht
    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            e.setCancelled(true);
        }
    }

    // --- GRAPPLING HOOK ---
    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.hasItemMeta() && "hook".equals(item.getItemMeta().getPersistentDataContainer().get(GADGET_ID, PersistentDataType.STRING))) {
            if (e.getState() == PlayerFishEvent.State.IN_GROUND || e.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
                Location hookLoc = e.getHook().getLocation();
                Location playerLoc = p.getLocation();
                Vector v = hookLoc.toVector().subtract(playerLoc.toVector()).normalize().multiply(1.8).setY(0.6);
                p.setVelocity(v);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1f);
            }
        }
    }

    // --- DASH FIX ---
    @EventHandler
    public void onDash(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;

        if ("dash".equals(item.getItemMeta().getPersistentDataContainer().get(GADGET_ID, PersistentDataType.STRING))) {
            e.setCancelled(true);
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            if (dashCooldown.containsKey(p.getUniqueId()) && now - dashCooldown.get(p.getUniqueId()) < 2000) {
                p.sendActionBar(mm.deserialize("<#FF4D4F>Gadget is on cooldown..."));
                return;
            }
            dashCooldown.put(p.getUniqueId(), now);
            Vector v = p.getLocation().getDirection().normalize().multiply(2.0).setY(0.5);
            p.setVelocity(v);
            p.playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1f);
            p.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, p.getLocation().add(0, 1, 0), 20, 0.2, 0.2, 0.2, 0.1);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (Player online : Bukkit.getOnlinePlayers()) updateVisibility(online);
    }

    private void updateVisibility(Player viewer) {
        boolean hide = hiddenPlayers.getOrDefault(viewer.getUniqueId(), false);
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(viewer)) continue;
            if (hide) viewer.hidePlayer(plugin, target);
            else viewer.showPlayer(plugin, target);
        }
    }

    private ItemStack createGadget(Material mat, String name, String id, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(mm.deserialize(name));
        meta.lore(List.of(mm.deserialize(desc)));
        meta.getPersistentDataContainer().set(GADGET_ID, PersistentDataType.STRING, id);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private String convertLegacyColor(String colorCode) {
        if (colorCode == null) return "";
        if (colorCode.startsWith("&") || colorCode.startsWith("§")) {
            char code = colorCode.charAt(1);
            return switch (code) {
                case 'c' -> "<red>"; case 'a' -> "<green>"; case 'b' -> "<aqua>"; case 'e' -> "<yellow>";
                case '6' -> "<gold>"; case 'd' -> "<light_purple>"; case '9' -> "<blue>"; case 'f' -> "<white>";
                case '7' -> "<gray>"; case '8' -> "<dark_gray>"; case '4' -> "<dark_red>"; case '5' -> "<dark_purple>";
                case '2' -> "<dark_green>"; case '3' -> "<dark_aqua>"; case '1' -> "<dark_blue>"; case '0' -> "<black>";
                default -> "";
            };
        }
        return colorCode;
    }
}