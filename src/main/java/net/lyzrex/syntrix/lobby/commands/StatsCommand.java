package net.lyzrex.syntrix.lobby.commands;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService.PlayerStats;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class StatsCommand extends BaseCommand {

    private static final DateTimeFormatter RECORD_FORMAT = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    public StatsCommand(SyntrixLobby plugin) {
        super(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin, "errors.player-only",
                    "<red>This command can only be used by players.</red>");
            return true;
        }

        if (!player.hasPermission("syntrix.stats")) {
            MessageUtil.send(player, plugin, "errors.no-permission",
                    "<red>You do not have permission to do this.</red>");
            return true;
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("jumpandrun")) {
            String usage = plugin.messages().getString("stats.jumpandrun.usage",
                    "<gradient:#2AF598:#009EFD>Stats</gradient> <#8799ae>usage:</#8799ae> <white>/stats jumpandrun</white>");
            MessageUtil.sendActionBar(player, usage);
            return true;
        }

        JumpAndRunService service = plugin.jumpAndRun();
        if (service == null) {
            MessageUtil.sendActionBar(player, plugin.messages().getString("stats.jumpandrun.unavailable",
                    "<gradient:#F6C35D:#F57200>Jump & Run</gradient> <#8799ae>statistics are currently unavailable.</#8799ae>"));
            return true;
        }

        PlayerStats stats = service.stats(player.getUniqueId());
        if (stats.completions() <= 0) {
            MessageUtil.sendActionBar(player, plugin.messages().getString("stats.jumpandrun.none",
                    "<#8799ae>No Jump & Run runs recorded yet.</#8799ae>"));
            return true;
        }

        String timeFormatted = stats.bestTimeMillis() == null
                ? plugin.messages().getString("stats.jumpandrun.no-record",
                "<#8799ae>no record</#8799ae>")
                : service.formatDuration(stats.bestTimeMillis());

        String dateFormatted = stats.bestTimestampMillis() == null
                ? plugin.messages().getString("stats.jumpandrun.no-date",
                "<#8799ae>unknown</#8799ae>")
                : RECORD_FORMAT.format(Instant.ofEpochMilli(stats.bestTimestampMillis()));

        String template = plugin.messages().getString("stats.jumpandrun.self",
                "<gradient:#2AF598:#009EFD>Jump & Run</gradient> <#8799ae>Runs:</#8799ae> <white>{runs}</white> "
                        + "<#8799ae>| PB:</#8799ae> <white>{time}</white> <#8799ae>({date})</#8799ae>");

        String message = template
                .replace("{runs}", Integer.toString(Math.max(0, stats.completions())))
                .replace("{time}", timeFormatted)
                .replace("{date}", dateFormatted);

        MessageUtil.sendActionBar(player, message);
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("jumpandrun");
        }
        return List.of();
    }
}