package net.lyzrex.syntrix.lobby.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import net.lyzrex.syntrix.lobby.utils.SkinResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class SkullCommand implements TabExecutor {

    private final SyntrixLobby plugin;
    private final SkinResolver resolver;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SkullCommand(@NotNull SyntrixLobby plugin) {
        this.plugin = plugin;
        this.resolver = new SkinResolver(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command cmd,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player p)) {
            MessageUtil.send(sender, plugin, "errors.player-only",
                    "<red>This command can only be used by players.</red>");
            return true;
        }

        if (args.length < 1) {
            MessageUtil.send(p, plugin, "errors.not-found",
                    "<red>Usage: /skull <player> [amount]</red>");
            return true;
        }

        final String targetName = args[0];
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {}
        }

        final int finalAmount = amount;


        resolver.resolveByName(targetName).whenComplete((profile, err) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null || profile == null) {
                    MessageUtil.send(p, plugin, "commands.skull.error",
                            "<red>Could not load skin for <white>" + targetName + "</white>.</red>");
                    return;
                }


                ItemStack head = new ItemStack(Material.PLAYER_HEAD, finalAmount);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setPlayerProfile(profile);


                String disp = plugin.messages().getString(
                        "commands.skull.display",
                        "<white><bold>Head</bold></white>");
                meta.displayName(mm.deserialize(disp));
                head.setItemMeta(meta);


                Map<Integer, ItemStack> leftover = p.getInventory().addItem(head);
                leftover.values().forEach(it ->
                        p.getWorld().dropItemNaturally(p.getLocation(), it));


                String done = plugin.messages().getString(
                        "commands.skull.done",
                        "<green>Gave <white>{receiver}</white> the head of <white>{name}</white> x{amount}.</green>");
                done = done
                        .replace("{receiver}", p.getName())
                        .replace("{name}", targetName)
                        .replace("{amount}", Integer.toString(finalAmount));
                MessageUtil.sendRaw(p, plugin, done);
            });
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            final String start = args[0].toLowerCase();
            Stream<String> online = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName);
            Stream<String> offline = Stream.of(Bukkit.getOfflinePlayers())
                    .map(OfflinePlayer::getName)
                    .filter(n -> n != null);
            return Stream.concat(online, offline)
                    .distinct()
                    .filter(n -> n.toLowerCase().startsWith(start))
                    .sorted(Comparator.naturalOrder())
                    .limit(20)
                    .toList();
        }
        return List.of();
    }
}
