package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import net.lyzrex.syntrix.lobby.utils.SoundUtil;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class JumpAndRunListener implements Listener {

    private final SyntrixLobby plugin;

    private static final class BlockSnapshot {
        final Material type;
        final BlockData data;

        BlockSnapshot(Material type, BlockData data) {
            this.type = type; this.data = data;
        }
        void restore(Block b) {
            if (type == Material.AIR) {
                b.setType(Material.AIR, false);
            } else {
                b.setType(type, false);
                b.setBlockData(data, false);
            }
        }
    }

    private static final class Run {
        final List<Location> steps;
        final Material platformMat;
        final Map<Block, BlockSnapshot> originals = new HashMap<>();
        final double cancelBelowBlocks;
        int index;
        BukkitRunnable task;

        Run(List<Location> steps, Material platformMat, double cancelBelowBlocks) {
            this.steps = steps;
            this.platformMat = platformMat;
            this.cancelBelowBlocks = cancelBelowBlocks;
            this.index = 1;
        }
    }

    private final Map<UUID, Run> activeRuns = new HashMap<>();

    public JumpAndRunListener(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    private boolean hasBuildMode(Player p) {
        return p.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
    }


    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("items.jumpAndRun.enabled", true)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (hasBuildMode(p)) return;

        ItemStack it = e.getItem();
        if (it == null) return;

        Material activator = Material.matchMaterial(
                plugin.getConfig().getString("items.jumpAndRun.activator-material", "SLIME_BALL")
        );
        if (activator == null) activator = Material.SLIME_BALL;
        if (it.getType() != activator) return;


        String perm = plugin.getConfig().getString("items.jumpAndRun.permission", "");
        if (perm != null && !perm.isBlank() && !p.hasPermission(perm)) {
            MessageUtil.send(p, plugin, "jumpandrun.no-permission",
                    "<red>You do not have permission to start Jump & Run.</red>");
            e.setCancelled(true);
            return;
        }

        e.setCancelled(true);

        UUID id = p.getUniqueId();
        if (activeRuns.containsKey(id)) {
            MessageUtil.send(p, plugin, "jumpandrun.already-running",
                    "<red>You already have an active Jump & Run.</red>");
            return;
        }

        startRun(p);
    }

    private void startRun(Player p) {
        final int count = Math.max(2, plugin.getConfig().getInt("items.jumpAndRun.steps", 12));
        final double distance = plugin.getConfig().getDouble("items.jumpAndRun.step-distance", 2.0);
        final double height = plugin.getConfig().getDouble("items.jumpAndRun.step-height", 1.0);
        final double cancelBelowBlocks = plugin.getConfig().getDouble("items.jumpAndRun.cancel-fall-below", 2.0);

        Material blockMat = Material.matchMaterial(
                plugin.getConfig().getString("items.jumpAndRun.block-material", "LIGHT_BLUE_STAINED_GLASS")
        );
        if (blockMat == null) blockMat = Material.LIGHT_BLUE_STAINED_GLASS;


        List<Location> steps = new ArrayList<>(count);
        Location base = p.getLocation().clone();
        Vector forward = base.getDirection().setY(0).normalize(); // nur horizontal
        for (int i = 0; i < count; i++) {
            Location loc = base.clone()
                    .add(forward.clone().multiply(i * distance))
                    .add(0, i * height, 0);
            loc.setX(loc.getBlockX() + 0.5);
            loc.setZ(loc.getBlockZ() + 0.5);
            steps.add(loc);
        }

        Run run = new Run(steps, blockMat, cancelBelowBlocks);


        placePlatform(run, steps.get(0));
        placePlatform(run, steps.get(1));


        p.teleport(steps.get(0).clone().add(0, 0.2, 0));

        MessageUtil.send(p, plugin, "jumpandrun.start",
                "<green>Jump & Run started! Reach the highlighted blocks.</green>");
        playCfgSound(p, "items.jumpAndRun.sounds.start", "block.note_block.pling");

        activeRuns.put(p.getUniqueId(), run);

        run.task = new BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { stopRun(p, false); cancel(); return; }


                int lastPlatformIdx = Math.max(0, run.index - 1);
                Location lastStep = run.steps.get(lastPlatformIdx);
                double platformSurfaceY = Math.floor(lastStep.getY());
                if (p.getLocation().getY() < platformSurfaceY - run.cancelBelowBlocks) {
                    stopRun(p, true);
                    cancel();
                    return;
                }


                if (p.getLocation().distanceSquared(run.steps.get(run.index)) < 0.36) {

                    restorePlatform(run, run.steps.get(run.index - 1));


                    run.index++;


                    if (run.index >= run.steps.size()) {
                        stopRun(p, false);
                        playCfgSound(p, "items.jumpAndRun.sounds.finish", "ui.toast.challenge_complete");
                        MessageUtil.send(p, plugin, "jumpandrun.finish",
                                "<gradient:#00ff88:#00aaff>Congratulations! You finished the Jump & Run.</gradient>");
                        cancel();
                        return;
                    }


                    placePlatform(run, run.steps.get(run.index));

                    String tmpl = plugin.messages().getString("jumpandrun.step", "<gray>Step {current}/{total}</gray>");
                    String msg = tmpl.replace("{current}", String.valueOf(run.index))
                            .replace("{total}", String.valueOf(run.steps.size()));
                    MessageUtil.sendRaw(p, plugin, msg);

                    playCfgSound(p, "items.jumpAndRun.sounds.step", "block.note_block.hat");
                }
            }
        };
        run.task.runTaskTimer(plugin, 2L, 2L);
    }



    private void stopRun(Player p, boolean cancelled) {
        Run run = activeRuns.remove(p.getUniqueId());
        if (run == null) return;

        if (run.task != null) run.task.cancel();


        for (Map.Entry<Block, BlockSnapshot> e : run.originals.entrySet()) {
            e.getValue().restore(e.getKey());
        }
        run.originals.clear();

        if (cancelled) {
            playCfgSound(p, "items.jumpAndRun.sounds.cancel", "entity.armor_stand.break");
            MessageUtil.send(p, plugin, "jumpandrun.cancel",
                    "<red>Jump & Run canceled.</red>");
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { stopRun(e.getPlayer(), false); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { stopRun(e.getEntity(), false); }



    private Block getPlatformBlock(Location stepLoc) {
        return stepLoc.clone().add(0, -1, 0).getBlock();
    }

    private void placePlatform(Run run, Location stepLoc) {
        Block b = getPlatformBlock(stepLoc);
        run.originals.computeIfAbsent(b, key -> new BlockSnapshot(key.getType(), key.getBlockData().clone()));
        b.setType(run.platformMat, false);
    }

    private void restorePlatform(Run run, Location stepLoc) {
        Block b = getPlatformBlock(stepLoc);
        BlockSnapshot snap = run.originals.remove(b);
        if (snap != null) snap.restore(b);
        else b.setType(Material.AIR, false);
    }



    private void playCfgSound(Player p, String cfgPath, String defKey) {
        String raw = plugin.getConfig().getString(cfgPath, defKey);
        float vol = (float) plugin.getConfig().getDouble("items.jumpAndRun.sounds.volume", 0.8);
        float pit = (float) plugin.getConfig().getDouble("items.jumpAndRun.sounds.pitch", 1.0);
        Sound s = SoundUtil.resolve(raw, Sound.UI_BUTTON_CLICK);
        p.playSound(p.getLocation(), s, SoundCategory.MASTER, vol, pit);
    }
}
