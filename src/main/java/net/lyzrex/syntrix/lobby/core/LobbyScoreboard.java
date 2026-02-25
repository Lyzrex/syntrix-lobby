package net.lyzrex.syntrix.lobby.core;

import de.murmelmeister.murmelapi.MurmelAPI;
import de.murmelmeister.murmelapi.group.Group;
import de.murmelmeister.murmelapi.group.color.GroupColor;
import de.murmelmeister.murmelapi.group.color.GroupColorType;
import de.murmelmeister.murmelapi.user.User;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LobbyScoreboard {

    private final SyntrixLobby plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final DecimalFormat df = new DecimalFormat("#,##0.00");
    private int taskID = -1;

    private static class PlayerDataCache {
        double money = 0;
        double bank = 0;
        int level = 0;
    }

    private final ConcurrentHashMap<UUID, PlayerDataCache> cache = new ConcurrentHashMap<>();

    public LobbyScoreboard(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (taskID != -1) return;

        // Live-Sync: Fragt die Daten jetzt asynchron JEDE SEKUNDE ab (20L)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                User user = MurmelAPI.getUserProvider().findByMojangId(p.getUniqueId());
                if (user != null) {
                    PlayerDataCache data = new PlayerDataCache();

                    Double money = MurmelAPI.getDatabase().query("SELECT balance FROM economy_balances WHERE user_id = ?", null, rs -> rs.getDouble("balance"), s -> s.setInt(1, user.id()));
                    if (money != null) data.money = money;

                    Double bank = MurmelAPI.getDatabase().query("SELECT balance FROM bank_accounts WHERE owner_id = ?", null, rs -> rs.getDouble("balance"), s -> s.setInt(1, user.id()));
                    if (bank != null) data.bank = bank;

                    Double level = MurmelAPI.getDatabase().query("SELECT global_level FROM player_skills WHERE user_id = ?", null, rs -> rs.getDouble("global_level"), s -> s.setInt(1, user.id()));
                    if (level != null) data.level = level.intValue();

                    cache.put(p.getUniqueId(), data);
                }
            }
        }, 40L, 20L); // 40L Start-Delay für Sicherheit, danach alle 20L = 1 Sekunde Update!

        taskID = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updateScoreboard(player);
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (taskID != -1) {
            Bukkit.getScheduler().cancelTask(taskID);
            taskID = -1;
        }
    }

    private void updateScoreboard(Player player) {
        Scoreboard board = player.getScoreboard();
        if (board.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }

        Objective sidebar = board.getObjective("LobbySB");
        if (sidebar == null) {
            sidebar = board.registerNewObjective("LobbySB", Criteria.DUMMY, mm.deserialize("   <gradient:#00C9FF:#92FE9D><bold>LYTHRION</bold></gradient>   "));
            sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
            try { sidebar.numberFormat(NumberFormat.blank()); } catch (NoSuchMethodError ignored) {}
        }

        String rankDisplay = getRankDisplay(player);
        PlayerDataCache data = cache.getOrDefault(player.getUniqueId(), new PlayerDataCache());

        setLine(board, sidebar, 13, " ");
        setLine(board, sidebar, 12, " <gray>┌ <#00C9FF>Profile");
        setLine(board, sidebar, 11, " <gray>│ <white>Rank: " + rankDisplay);
        setLine(board, sidebar, 10, " <gray>│ <white>Level: <#00C9FF>⭐ " + data.level);

        setLine(board, sidebar, 9, "  ");

        setLine(board, sidebar, 8, " <gray>┌ <#F6C35D>Finance");
        setLine(board, sidebar, 7, " <gray>│ <white>Cash: <#2AF598>" + df.format(data.money) + " $");
        setLine(board, sidebar, 6, " <gray>│ <white>Bank: <#2AF598>" + df.format(data.bank) + " $");

        setLine(board, sidebar, 5, "   ");

        setLine(board, sidebar, 4, " <gray>┌ <#FF5555>Network");
        setLine(board, sidebar, 3, " <gray>│ <white>Players: <#2AF598>" + Bukkit.getOnlinePlayers().size());
        setLine(board, sidebar, 2, " <gray>│ <white>Ping: <#009EFD>" + player.getPing() + "ms");

        setLine(board, sidebar, 1, "    ");
        setLine(board, sidebar, 0, "<gray>» <gradient:#00C9FF:#92FE9D>v0.0.3-beta</gradient> <gray>«");
    }

    private String getRankDisplay(Player player) {
        try {
            User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
            if (user == null) return "<#8799ae>Player";

            var parents = MurmelAPI.getUserParentProvider().getParents(user.id());
            Group group = parents.isEmpty() ? MurmelAPI.getGroupProvider().findById(1) : parents.stream().map(parent -> MurmelAPI.getGroupProvider().findById(parent.parentId())).filter(Objects::nonNull).max(Comparator.comparingInt(Group::priority)).orElse(null);

            if (group == null) return "<#8799ae>Player";
            String prettyName = group.groupName().substring(0, 1).toUpperCase() + group.groupName().substring(1).toLowerCase();
            GroupColor colorData = MurmelAPI.getGroupColorProvider().getGroupColor(group.id(), GroupColorType.CHAT_COLOR.getId());

            return (colorData != null && colorData.value() != null) ? convertLegacyColor(colorData.value()) + prettyName : "<#8799ae>" + prettyName;
        } catch (Exception e) { return "<#8799ae>Player"; }
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

    private void setLine(Scoreboard board, Objective obj, int score, String content) {
        String entry = ChatColor.values()[score].toString();
        String teamName = "line_" + score;
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.addEntry(entry);
            obj.getScore(entry).setScore(score);
        }
        team.prefix(mm.deserialize(content));
    }
}