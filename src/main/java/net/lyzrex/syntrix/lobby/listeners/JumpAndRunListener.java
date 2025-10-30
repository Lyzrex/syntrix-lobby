package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService.LeaderboardResult;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import net.lyzrex.syntrix.lobby.utils.SoundUtil;
import org.bukkit.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JumpAndRunListener implements Listener {

    private static final double STEP_REACH_DISTANCE_SQ = 0.36D;

    private final SyntrixLobby plugin;
    private final JumpAndRunService service;
    private final Map<UUID, Run> activeRuns = new HashMap<>();

    public JumpAndRunListener(SyntrixLobby plugin, JumpAndRunService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private static final class BlockSnapshot {
        final Material type;
        final BlockData data;

        BlockSnapshot(Material type, BlockData data) {
            this.type = type;
            this.data = data;
        }

        void restore(Block block) {
            if (type == Material.AIR) {

                block.setType(Material.AIR, false);
            } else {

                block.setType(type, false);
                block.setBlockData(data, false);
            }
        }
    }

    private static final class Run {
        final List<Location> steps;
        final Material platformMaterial;
        final Map<Block, BlockSnapshot> originals = new HashMap<>();
        final double cancelThreshold;
        final Set<Integer> checkpoints; // 0-based indexes
        int lastReachedIndex;
        int nextIndex;
        int checkpointIndex;
        BukkitRunnable task;
        long startNano;

        Run(List<Location> steps,
            Material platformMaterial,
            double cancelThreshold,
            Set<Integer> checkpoints) {
            this.steps = steps;
            this.platformMaterial = platformMaterial;
            this.cancelThreshold = cancelThreshold;
            this.checkpoints = checkpoints;
            this.lastReachedIndex = 0;
            this.nextIndex = Math.min(1, steps.size() - 1);
            this.checkpointIndex = 0;
            this.startNano = System.nanoTime();
        }
    }

    private record DifficultySegment(int start, int end, double distance, double height) {
        boolean contains(int index) {
            return index >= start && index <= end;
        }
    }

    private boolean hasBuildMode(Player player) {
        return player.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
    }

    @EventHandler

    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.getConfig().getBoolean("items.jumpAndRun.enabled", true)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (hasBuildMode(player)) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Material activator = Material.matchMaterial(
                plugin.getConfig().getString("items.jumpAndRun.activator-material", "SLIME_BALL")
        );

        if (activator == null) {
            activator = Material.SLIME_BALL;
        }
        if (item.getType() != activator) {
            return;
        }


        String permission = plugin.getConfig().getString("items.jumpAndRun.permission", "");
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            MessageUtil.send(player, plugin, "jumpandrun.no-permission",
                    "<red>You do not have permission to start Jump & Run.</red>");

            event.setCancelled(true);
            return;
        }


        event.setCancelled(true);
        UUID id = player.getUniqueId();
        if (activeRuns.containsKey(id)) {
            MessageUtil.send(player, plugin, "jumpandrun.already-running",
                    "<red>You already have an active Jump & Run.</red>");
            return;
        }

        startRun(player);
    }

    private void startRun(Player player) {
        int totalSteps = Math.max(2, plugin.getConfig().getInt("items.jumpAndRun.steps", 40));
        double cancelBelow = plugin.getConfig().getDouble("items.jumpAndRun.cancel-fall-below", 2.0);

        Material platformMaterial = Material.matchMaterial(
                plugin.getConfig().getString("items.jumpAndRun.block-material", "LIGHT_BLUE_STAINED_GLASS")
        );

        if (platformMaterial == null) {
            platformMaterial = Material.LIGHT_BLUE_STAINED_GLASS;
        }

        List<DifficultySegment> segments = loadSegments(totalSteps);
        Set<Integer> checkpoints = loadCheckpoints(totalSteps);
        List<Location> steps = buildSteps(player, totalSteps, segments);

        Run run = new Run(steps, platformMaterial, cancelBelow, checkpoints);
        activeRuns.put(player.getUniqueId(), run);

        placePlatform(run, steps.get(0));

        if (run.nextIndex > 0) {
            placePlatform(run, steps.get(run.nextIndex));
        }
        player.teleport(steps.get(0).clone().add(0, 0.2, 0));

        MessageUtil.send(player, plugin, "jumpandrun.start",
                "<green>Jump & Run started! Reach the highlighted blocks.</green>");

        playSound(player, "start", "block.note_block.pling");

        run.task = new BukkitRunnable() {

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    stopRun(player, false);
                    cancel();
                    return;
                }

                Run currentRun = activeRuns.get(player.getUniqueId());
                if (currentRun == null) {
                    cancel();
                    return;
                }
                if (shouldReset(player, currentRun)) {
                    resetToCheckpoint(player, currentRun);
                    return;
                }
                if (player.getLocation().distanceSquared(currentRun.steps.get(currentRun.nextIndex)) <= STEP_REACH_DISTANCE_SQ) {
                    advance(player, currentRun);
                }
            }
        };
        run.task.runTaskTimer(plugin, 2L, 2L);
    }

    private boolean shouldReset(Player player, Run run) {
        Location last = run.steps.get(run.lastReachedIndex);
        double platformSurface = Math.floor(last.getY());
        return player.getLocation().getY() < platformSurface - run.cancelThreshold;
    }

    private void advance(Player player, Run run) {
        restorePlatform(run, run.steps.get(run.lastReachedIndex));
        run.lastReachedIndex = run.nextIndex;

        if (run.checkpoints.contains(run.lastReachedIndex)) {
            run.checkpointIndex = run.lastReachedIndex;
            if (run.lastReachedIndex != 0) {
                String template = plugin.messages().getString("jumpandrun.checkpoint",
                        "<green>Checkpoint reached: Step {current}/{total}.</green>");
                String msg = template.replace("{current}", Integer.toString(run.lastReachedIndex + 1))
                        .replace("{total}", Integer.toString(run.steps.size()));
                MessageUtil.sendRaw(player, plugin, msg);
                playSound(player, "checkpoint", "block.note_block.bell");
            }
        }

        if (run.lastReachedIndex >= run.steps.size() - 1) {
            finish(player, run);
            return;
        }

        run.nextIndex = Math.min(run.lastReachedIndex + 1, run.steps.size() - 1);
        placePlatform(run, run.steps.get(run.nextIndex));

        String template = plugin.messages().getString("jumpandrun.step",
                "<gray>Step {current}/{total}</gray>");
        String msg = template.replace("{current}", Integer.toString(run.lastReachedIndex + 1))
                .replace("{total}", Integer.toString(run.steps.size()));
        MessageUtil.sendRaw(player, plugin, msg);
        playSound(player, "step", "block.note_block.hat");
    }

    private void finish(Player player, Run run) {
        Run removed = removeRun(player);
        if (removed == null) {
            return;
        }
        cleanupPlatforms(removed);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - removed.startNano).toMillis();
        LeaderboardResult result = service.recordCompletion(player, elapsedMillis);

        String template = plugin.messages().getString("jumpandrun.finish",
                "<gradient:#00ff88:#00aaff>Finished in {time}.</gradient>");
        String message = template.replace("{time}", service.formatDuration(elapsedMillis));
        MessageUtil.sendRaw(player, plugin, message);
        playSound(player, "finish", "ui.toast.challenge_complete");

        if (result.newRecord()) {
            MessageUtil.send(player, plugin, "jumpandrun.new-record",
                    "<gold>New personal best!</gold>");
        }
    }

    private void resetToCheckpoint(Player player, Run run) {
        cleanupPlatforms(run);
        playSound(player, "fail", "entity.villager.no");
        MessageUtil.send(player, plugin, "jumpandrun.reset",
                "<red>You fell! Returning to the last checkpoint.</red>");

        run.lastReachedIndex = run.checkpointIndex;
        run.nextIndex = Math.min(run.lastReachedIndex + 1, run.steps.size() - 1);
        placePlatform(run, run.steps.get(run.lastReachedIndex));
        if (run.nextIndex > run.lastReachedIndex) {
            placePlatform(run, run.steps.get(run.nextIndex));
        }
        player.teleport(run.steps.get(run.lastReachedIndex).clone().add(0, 0.2, 0));
    }

    private void stopRun(Player player, boolean cancelled) {
        Run run = removeRun(player);
        if (run == null) {
            return;
        }
        cleanupPlatforms(run);
        if (cancelled) {
            playSound(player, "cancel", "entity.armor_stand.break");
            MessageUtil.send(player, plugin, "jumpandrun.cancel",
                    "<red>Jump & Run canceled.</red>");
        }
    }


    private Run removeRun(Player player) {
        Run run = activeRuns.remove(player.getUniqueId());
        if (run != null && run.task != null) {
            run.task.cancel();
        }
        return run;
    }

    private void cleanupPlatforms(Run run) {
        for (Map.Entry<Block, BlockSnapshot> entry : run.originals.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        run.originals.clear();
    }

    private List<Location> buildSteps(Player player, int steps, List<DifficultySegment> segments) {
        List<Location> locations = new ArrayList<>(steps);
        Location base = alignToBlockCenter(player.getLocation());
        base.setPitch(0);
        locations.add(base);

        Vector direction = horizontalDirection(player.getLocation());
        DifficultySegment fallback = segments.isEmpty()
                ? new DifficultySegment(1, steps, 2.0, 0.5)
                : segments.get(segments.size() - 1);

        Location current = base;
        for (int stepIndex = 2; stepIndex <= steps; stepIndex++) {
            DifficultySegment segment = findSegment(segments, stepIndex, fallback);
            Location next = current.clone()
                    .add(direction.clone().multiply(segment.distance()))
                    .add(0, segment.height(), 0);
            next = alignToBlockCenter(next);
            locations.add(next);
            current = next;
        }
        return locations;
    }

    private DifficultySegment findSegment(List<DifficultySegment> segments, int index, DifficultySegment fallback) {
        if (segments.isEmpty()) {
            return fallback;
        }
        for (DifficultySegment segment : segments) {
            if (segment.contains(index) || index < segment.start()) {
                return segment;
            }
        }
        return fallback;
    }

    private Vector horizontalDirection(Location source) {
        Vector dir = source.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            return new Vector(1, 0, 0);
        }
        return dir.normalize();
    }

    private Location alignToBlockCenter(Location location) {
        Location copy = location.clone();
        copy.setX(copy.getBlockX() + 0.5);
        copy.setZ(copy.getBlockZ() + 0.5);
        return copy;
    }

    private List<DifficultySegment> loadSegments(int steps) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("items.jumpAndRun.difficulties");
        if (section == null) {
            return defaultSegments(steps);
        }
        List<DifficultySegment> segments = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) {
                continue;
            }
            List<Integer> range = child.getIntegerList("range");
            if (range.size() < 2) {
                continue;
            }
            int start = Math.max(1, range.get(0));
            int end = Math.min(steps, range.get(range.size() - 1));
            if (start > end) {
                continue;
            }
            double distance = child.getDouble("distance", 2.0);
            double height = child.getDouble("height", 0.5);
            segments.add(new DifficultySegment(start, end, distance, height));
        }
        if (segments.isEmpty()) {
            return defaultSegments(steps);
        }
        segments.sort(Comparator.comparingInt(DifficultySegment::start));
        return segments;
    }

    private List<DifficultySegment> defaultSegments(int steps) {
        List<DifficultySegment> defaults = new ArrayList<>();
        int easyEnd = Math.min(steps, 12);
        defaults.add(new DifficultySegment(1, easyEnd, 1.6, 0.3));
        if (easyEnd >= steps) {
            return defaults;
        }
        int mediumEnd = Math.min(steps, Math.max(easyEnd + 1, 24));
        defaults.add(new DifficultySegment(easyEnd + 1, mediumEnd, 2.0, 0.6));
        if (mediumEnd >= steps) {
            return defaults;
        }
        defaults.add(new DifficultySegment(mediumEnd + 1, steps, 2.4, 0.9));
        return defaults;
    }

    private Set<Integer> loadCheckpoints(int steps) {
        Set<Integer> checkpoints = new HashSet<>();
        checkpoints.add(0); // always include the start
        List<Integer> configured = plugin.getConfig().getIntegerList("items.jumpAndRun.checkpoints");
        for (Integer value : configured) {
            if (value == null) {
                continue;
            }
            int index = Math.max(1, Math.min(steps, value));
            checkpoints.add(index - 1);
        }
        return checkpoints;
    }

    private void placePlatform(Run run, Location stepLocation) {
        Block block = stepLocation.clone().add(0, -1, 0).getBlock();
        run.originals.computeIfAbsent(block,
                key -> new BlockSnapshot(key.getType(), key.getBlockData().clone()));
        block.setType(run.platformMaterial, false);
    }


    private void restorePlatform(Run run, Location stepLocation) {
        Block block = stepLocation.clone().add(0, -1, 0).getBlock();
        BlockSnapshot snapshot = run.originals.remove(block);
        if (snapshot != null) {
            snapshot.restore(block);
        }
    }

    private void playSound(Player player, String key, String fallback) {
        String path = "items.jumpAndRun.sounds." + key;
        String raw = plugin.getConfig().getString(path, fallback);
        float volume = (float) plugin.getConfig().getDouble("items.jumpAndRun.sounds.volume", 0.8);
        float pitch = (float) plugin.getConfig().getDouble("items.jumpAndRun.sounds.pitch", 1.0);
        Sound sound = SoundUtil.resolve(raw, Sound.UI_BUTTON_CLICK);
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopRun(event.getPlayer(), false);
    }


    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        stopRun(event.getEntity(), false);
    }
}