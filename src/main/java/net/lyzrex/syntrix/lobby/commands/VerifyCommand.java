package net.lyzrex.syntrix.lobby.commands;

import de.murmelmeister.murmelapi.MurmelAPI;
import de.murmelmeister.murmelapi.user.User;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

public class VerifyCommand extends BaseCommand {
    public VerifyCommand(SyntrixLobby plugin) { super(plugin); }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;

        User user = MurmelAPI.getUserProvider().findByMojangId(player.getUniqueId());
        if (user == null) return true;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Check if already verified
            boolean isLinked = MurmelAPI.getDatabase().exists(
                    "SELECT 1 FROM discord_verify WHERE user_id = ? AND verified = TRUE",
                    s -> s.setInt(1, user.id())
            );

            if (isLinked) {
                player.sendMessage(mm.deserialize("<#FF4D4F>Your Minecraft account is already linked to Discord!"));
                return;
            }

            // Generate a 5-digit code
            String code = String.format("%05d", new Random().nextInt(99999));

            // Insert Player ID (user_id) and Code into the database
            MurmelAPI.getDatabase().update(
                    "INSERT INTO discord_verify (user_id, verify_code, verified, reward_claimed) VALUES (?, ?, FALSE, FALSE) " +
                            "ON DUPLICATE KEY UPDATE verify_code = ?, verified = FALSE, reward_claimed = FALSE",
                    s -> {
                        s.setInt(1, user.id());
                        s.setString(2, code);
                        s.setString(3, code);
                    }
            );

            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(mm.deserialize(""));
                player.sendMessage(mm.deserialize("<gradient:#2AF598:#009EFD><bold>Discord Verification</bold></gradient>"));
                player.sendMessage(mm.deserialize("<#8799ae>Your personal code is: <#FFFFFF><bold>" + code + "</bold>"));
                player.sendMessage(mm.deserialize("<#8799ae>Send this code in our Discord channel <#5865F2>#verify"));
                player.sendMessage(mm.deserialize(""));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            });
        });
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}