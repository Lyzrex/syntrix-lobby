package net.lyzrex.syntrix.lobby.utils;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class FlightUtil {
    private FlightUtil() {
    }

    public static boolean isFlightEnabled(Player player) {
        return player.getAllowFlight() || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    public static boolean setFlight(Player player, boolean enable) {
        if (enable) {
            boolean changed = !player.getAllowFlight() || !player.isFlying();
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setFallDistance(0.0F);
            return changed;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        boolean changed = player.getAllowFlight() || player.isFlying();
        player.setFlying(false);
        player.setAllowFlight(false);
        return changed;
    }

    public static boolean toggleFlight(Player player) {
        boolean enable = !isFlightEnabled(player) || (!player.isFlying() && !player.getAllowFlight());
        return setFlight(player, enable);
    }
}