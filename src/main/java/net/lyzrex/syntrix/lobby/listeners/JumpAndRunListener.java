package net.lyzrex.syntrix.lobby.listeners;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService.LeaderboardResult;
import net.lyzrex.syntrix.lobby.utils.MessageUtil;
import net.lyzrex.syntrix.lobby.utils.SoundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class JumpAndRunListener implements Listener {

    private static final double STEP_REACH_DISTANCE_SQ = 0.96D;

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
        final boolean restoreFlight;
        final boolean restoreFlying;
        final boolean restoreDoubleJump;
        final int totalSteps;
        int lastReachedIndex;
        int nextIndex;
        int checkpointIndex;
        BukkitRunnable task;
        long startNano;

        Run(List<Location> steps,
            Material platformMaterial,
            double cancelThreshold,
            Set<Integer> checkpoints,
            boolean restoreFlight,
            boolean restoreFlying,
            boolean restoreDoubleJump) {
            this.steps = steps;
            this.platformMaterial = platformMaterial;
            this.cancelThreshold = cancelThreshold;
            this.checkpoints = checkpoints;
            this.restoreFlight = restoreFlight;
            this.restoreFlying = restoreFlying;
            this.restoreDoubleJump = restoreDoubleJump;
            this.totalSteps = Math.max(1, steps.size());
            this.lastReachedIndex = 0;
            this.nextIndex = Math.min(1, steps.size() - 1);
            this.checkpointIndex = 0;
            this.startNano = System.nanoTime();
        }
    }

    private enum StepDirectionType {
        STRAIGHT,
        DIAGONAL
    }

    private record StepCandidate(Location location, Vector heading, StepDirectionType type) {}

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
        Run active = activeRuns.get(id);
        if (active != null) {
            if (player.isSneaking()) {
                stopRun(player, true);
            } else {
                MessageUtil.send(player, plugin, "jumpandrun.already-running",
                        "<red>You already have an active Jump & Run. Sneak-right-click to cancel.</red>");
            }
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
        if (steps.size() < 2) {
            steps = buildEmergencyPath(player, totalSteps);
        }
        if (steps.size() < 2) {
            MessageUtil.send(player, plugin, "jumpandrun.failed",
                    "<red>No valid Jump & Run path could be generated here.</red>");
            return;
        }

        UUID id = player.getUniqueId();
        boolean doubleJumpActive = !plugin.doubleJump().isTemporarilyDisabled(id);
        boolean restoreFlight = !doubleJumpActive && player.getAllowFlight();
        boolean restoreFlying = player.isFlying();
        if (doubleJumpActive) {
            plugin.doubleJump().disableForFlight(player);
        }
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0.0F);

        Run run = new Run(steps, platformMaterial, cancelBelow, checkpoints, restoreFlight, restoreFlying, doubleJumpActive);
        activeRuns.put(player.getUniqueId(), run);

        placePlatform(run, steps.get(0));

        if (run.nextIndex > 0) {
            placePlatform(run, steps.get(run.nextIndex));
        }
        player.teleport(steps.get(0).clone().add(0, 0.2, 0));

        playSound(player, "start", "block.note_block.pling");
        showActionBar(player, run, "jumpandrun.actionbar.start", "<green>• Go!</green>");

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
                if (reachedStep(player, currentRun)) {
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

    private boolean reachedStep(Player player, Run run) {
        if (run.nextIndex <= run.lastReachedIndex || run.nextIndex >= run.steps.size()) {
            return false;
        }
        Location target = run.steps.get(run.nextIndex);
        Location current = player.getLocation();
        if (target.getWorld() == null || !target.getWorld().equals(current.getWorld())) {
            return false;
        }
        double verticalDelta = target.getY() - current.getY();
        if (verticalDelta > 0.75) {
            return false;
        }
        if (Math.abs(verticalDelta) > 1.25) {
            return false;
        }

        double dx = current.getX() - target.getX();
        double dz = current.getZ() - target.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq > STEP_REACH_DISTANCE_SQ) {
            return false;
        }

        Block playerBlock = current.clone().add(0, -0.6, 0).getBlock();
        Block targetBlock = target.clone().add(0, -1, 0).getBlock();
        if (playerBlock.getX() == targetBlock.getX()
                && playerBlock.getY() == targetBlock.getY()
                && playerBlock.getZ() == targetBlock.getZ()) {
            return true;
        }

        return horizontalSq <= 0.49D && Math.abs(verticalDelta) <= 0.75D;
    }

    private void advance(Player player, Run run) {
        player.setFallDistance(0.0F);
        restorePlatform(run, run.steps.get(run.lastReachedIndex));
        run.lastReachedIndex = run.nextIndex;

        if (run.checkpoints.contains(run.lastReachedIndex)) {
            run.checkpointIndex = run.lastReachedIndex;
            if (run.lastReachedIndex != 0) {
                showActionBar(player, run, "jumpandrun.actionbar.checkpoint", "<green>Checkpoint reached!</green>");
                playSound(player, "checkpoint", "block.note_block.bell");
            }
        }

        if (run.lastReachedIndex >= run.steps.size() - 1) {
            finish(player, run);
            return;
        }

        run.nextIndex = Math.min(run.lastReachedIndex + 1, run.steps.size() - 1);
        placePlatform(run, run.steps.get(run.nextIndex));
        showActionBar(player, run, null, null);
        playSound(player, "step", "block.note_block.hat");
    }

    private void showActionBar(Player player, Run run, String statusKey, String defaultStatus) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - run.startNano).toMillis();
        int totalSteps = Math.max(1, run.totalSteps);
        int currentIndex = Math.max(0, Math.min(run.steps.size() - 1, run.lastReachedIndex));
        int currentStep = Math.min(totalSteps, currentIndex + 1);
        int nextCheckpointIndex = findNextCheckpointIndex(run);
        int checkpointStep = Math.min(totalSteps, nextCheckpointIndex + 1);
        int remaining = Math.max(0, nextCheckpointIndex - currentIndex);

        String leftText = remaining <= 0
                ? plugin.messages().getString("jumpandrun.actionbar.left-finish", "Goal")
                : Integer.toString(remaining);

        String base = plugin.messages().getString("jumpandrun.actionbar.base",
                "<gray>[{time}] - Step {current}/{total} ({left} left to Checkpoint)</gray>{status}");

        String status = null;
        if (statusKey != null) {
            status = plugin.messages().getString(statusKey, defaultStatus);
        } else if (defaultStatus != null) {
            status = defaultStatus;
        }
        status = fillPlaceholders(status, elapsedMillis, currentStep, totalSteps, leftText, checkpointStep);
        if (status != null && !status.isBlank()) {
            if (!status.startsWith(" ")) {
                status = " " + status;
            }
        } else {
            status = "";
        }

        String message = fillPlaceholders(base, elapsedMillis, currentStep, totalSteps, leftText, checkpointStep)
                .replace("{status}", status);
        MessageUtil.sendActionBar(player, message);
    }

    private String fillPlaceholders(String template,
                                    long elapsedMillis,
                                    int currentStep,
                                    int totalSteps,
                                    String leftText,
                                    int checkpointStep) {
        if (template == null) {
            return null;
        }
        return template
                .replace("{time}", service.formatDuration(elapsedMillis))
                .replace("{current}", Integer.toString(currentStep))
                .replace("{total}", Integer.toString(totalSteps))
                .replace("{left}", leftText)
                .replace("{next}", Integer.toString(checkpointStep))
                .replace("{checkpoint}", Integer.toString(checkpointStep));
    }

    private int findNextCheckpointIndex(Run run) {
        int goalIndex = Math.max(0, run.steps.size() - 1);
        int next = goalIndex;
        for (Integer candidate : run.checkpoints) {
            if (candidate == null) {
                continue;
            }
            if (candidate > run.lastReachedIndex && candidate < next) {
                next = candidate;
            }
        }
        return next;
    }

    private void finish(Player player, Run run) {
        Run removed = removeRun(player);
        if (removed == null) {
            return;
        }
        cleanupPlatforms(removed);
        restoreMovement(player, removed);
        player.setFallDistance(0.0F);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - removed.startNano).toMillis();
        LeaderboardResult result = service.recordCompletion(player, elapsedMillis);

        playSound(player, "finish", "ui.toast.challenge_complete");

        String statusKey = result.newRecord()
                ? "jumpandrun.actionbar.finish-record"
                : "jumpandrun.actionbar.finish";
        String statusDefault = result.newRecord()
                ? "<gold>New personal best!</gold>"
                : "<gradient:#00ff88:#00aaff>Finished!</gradient>";
        showActionBar(player, removed, statusKey, statusDefault);
    }

    private void resetToCheckpoint(Player player, Run run) {
        cleanupPlatforms(run);
        playSound(player, "fail", "entity.villager.no");
        showActionBar(player, run, "jumpandrun.actionbar.reset", "<red>Reset to checkpoint.</red>");

        run.lastReachedIndex = run.checkpointIndex;
        run.nextIndex = Math.min(run.lastReachedIndex + 1, run.steps.size() - 1);
        placePlatform(run, run.steps.get(run.lastReachedIndex));
        if (run.nextIndex > run.lastReachedIndex) {
            placePlatform(run, run.steps.get(run.nextIndex));
        }
        player.teleport(run.steps.get(run.lastReachedIndex).clone().add(0, 0.2, 0));
        player.setFallDistance(0.0F);
        showActionBar(player, run, null, null);
    }

    private void stopRun(Player player, boolean cancelled) {
        Run run = removeRun(player);
        if (run == null) {
            return;
        }
        cleanupPlatforms(run);
        restoreMovement(player, run);
        player.setFallDistance(0.0F);
        if (cancelled) {
            playSound(player, "cancel", "entity.armor_stand.break");
            showActionBar(player, run, "jumpandrun.actionbar.cancel", "<red>Run canceled.</red>");
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

        Vector direction = normalisedHorizontal(player.getLocation().getDirection());
        Vector left = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        DifficultySegment fallback = segments.isEmpty()
                ? new DifficultySegment(1, steps, 2.35, 0.65)
                : segments.get(segments.size() - 1);

        Location current = base;
        StepDirectionType previousType = StepDirectionType.STRAIGHT;
        for (int stepIndex = 2; stepIndex <= steps; stepIndex++) {
            DifficultySegment segment = findSegment(segments, stepIndex, fallback);
            StepCandidate candidate = findNextStep(current, direction, left, segment, previousType);
            if (candidate == null) {
                candidate = fallbackCandidate(current, direction, left);
            }
            if (candidate == null) {
                break;
            }
            locations.add(candidate.location());
            direction = candidate.heading();
            left = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
            current = candidate.location();
            previousType = candidate.type();
        }
        if (locations.size() < steps) {
            List<Location> padded = padWithFallback(locations, steps);
            if (!padded.isEmpty()) {
                return padded;
            }
        }
        return locations;
    }

    private List<Location> buildEmergencyPath(Player player, int steps) {
        Location base = alignToBlockCenter(player.getLocation());
        base.setPitch(0);
        List<Location> seed = new ArrayList<>(List.of(base));
        List<Location> padded = padWithFallback(seed, steps);
        if (!padded.isEmpty()) {
            return padded;
        }
        List<Location> fallback = new ArrayList<>(steps);
        fallback.add(base);
        Vector forward = normalisedHorizontal(player.getLocation().getDirection());
        if (forward.lengthSquared() < 1.0E-4) {
            forward = new Vector(1, 0, 0);
        }
        Vector left = new Vector(-forward.getZ(), 0, forward.getX()).normalize();
        Location current = base;
        for (int i = 1; i < steps; i++) {
            Vector heading = forward.clone();
            if (i % 3 == 0) {
                heading.add(left.clone().multiply(0.6));
            } else if (i % 3 == 1) {
                heading.subtract(left.clone().multiply(0.4));
            }
            heading = normalisedHorizontal(heading);

            double distance = 1.85;
            double height = 0.55 + (i % 2 == 0 ? 0.1 : -0.05);
            Location candidate = alignToBlockCenter(current.clone().add(heading.clone().multiply(distance)).add(0, height, 0));

            int attempts = 0;
            while (!isPathClear(current, candidate) && attempts++ < 6) {
                candidate.add(0, 0.3, 0);
            }
            if (!isPathClear(current, candidate)) {
                candidate = alignToBlockCenter(current.clone().add(0, 0.6, 0));
            }

            fallback.add(candidate);
            current = candidate;
            forward = heading;
            left = new Vector(-forward.getZ(), 0, forward.getX()).normalize();
        }
        return fallback;
    }

    private StepCandidate findNextStep(Location current,
                                       Vector forward,
                                       Vector left,
                                       DifficultySegment segment,
                                       StepDirectionType previousType) {
        double baseDistance = Math.max(1.8, Math.min(3.1, segment.distance()));
        double distanceScale = ThreadLocalRandom.current().nextDouble(0.95, 1.1);
        double adjustedDistance = Math.max(1.7, Math.min(3.2, baseDistance * distanceScale));
        double adjustedHeight = Math.max(0.25,
                Math.min(1.05, segment.height() + ThreadLocalRandom.current().nextDouble(-0.05, 0.25)));

        List<StepCandidate> candidates = new ArrayList<>();
        Vector straight = normalisedHorizontal(forward);
        Vector rightDiagonal = normalisedHorizontal(forward.clone().add(left));
        Vector leftDiagonal = normalisedHorizontal(forward.clone().subtract(left));
        candidates.add(new StepCandidate(candidateLocation(current, straight, adjustedDistance, adjustedHeight),
                straight, StepDirectionType.STRAIGHT));
        candidates.add(new StepCandidate(candidateLocation(current, rightDiagonal, adjustedDistance, adjustedHeight),
                rightDiagonal, StepDirectionType.DIAGONAL));
        candidates.add(new StepCandidate(candidateLocation(current, leftDiagonal, adjustedDistance, adjustedHeight),
                leftDiagonal, StepDirectionType.DIAGONAL));

        Collections.shuffle(candidates, ThreadLocalRandom.current());
        List<StepCandidate> prioritised = new ArrayList<>();
        for (StepCandidate candidate : candidates) {
            if (candidate.type() != previousType) {
                prioritised.add(candidate);
            }
        }
        for (StepCandidate candidate : candidates) {
            if (candidate.type() == previousType) {
                prioritised.add(candidate);
            }
        }

        for (StepCandidate candidate : prioritised) {
            if (candidate.location() != null && isPathClear(current, candidate.location())) {
                if (Math.abs(candidate.location().getY() - current.getY()) <= 1.25D) {
                    return new StepCandidate(candidate.location(), candidate.heading(), candidate.type());
                }
            }
        }

        double fallbackDistance = Math.max(1.6, Math.min(3.0, baseDistance * 0.9));
        double fallbackHeight = Math.min(1.15, adjustedHeight + ThreadLocalRandom.current().nextDouble(0.2, 0.45));
        for (StepCandidate candidate : prioritised) {
            Location location = candidateLocation(current, candidate.heading(), fallbackDistance, fallbackHeight);
            if (location != null && isPathClear(current, location)) {
                if (Math.abs(location.getY() - current.getY()) <= 1.25D) {
                    return new StepCandidate(location, candidate.heading(), candidate.type());
                }
            }
        }
        return null;
    }

    private StepCandidate fallbackCandidate(Location current,
                                            Vector forward,
                                            Vector left) {
        Vector straight = normalisedHorizontal(forward);
        Vector rightDiagonal = normalisedHorizontal(forward.clone().add(left));
        Vector leftDiagonal = normalisedHorizontal(forward.clone().subtract(left));
        Vector[] directions = {straight, rightDiagonal, leftDiagonal};
        StepDirectionType[] types = {StepDirectionType.STRAIGHT, StepDirectionType.DIAGONAL, StepDirectionType.DIAGONAL};
        double distance = 1.9;
        double height = 0.55;
        for (int i = 0; i < directions.length; i++) {
            Vector dir = directions[i];
            Location target = alignToBlockCenter(current.clone().add(dir.clone().multiply(distance)).add(0, height, 0));
            int attempts = 0;
            while (!isPathClear(current, target) && attempts++ < 8) {
                target.add(0, 0.5, 0);
            }
            if (isPathClear(current, target) && Math.abs(target.getY() - current.getY()) <= 1.25D) {
                return new StepCandidate(target, dir, types[i]);
            }
        }
        return null;
    }

    private List<Location> padWithFallback(List<Location> seed, int desired) {
        if (seed.isEmpty()) {
            return List.of();
        }
        List<Location> path = new ArrayList<>(seed);
        while (path.size() < desired) {
            Location current = path.get(path.size() - 1);
            Vector heading;
            if (path.size() >= 2) {
                heading = current.toVector().subtract(path.get(path.size() - 2).toVector());
            } else {
                heading = new Vector(1, 0, 0);
            }
            heading = normalisedHorizontal(heading);
            Vector left = new Vector(-heading.getZ(), 0, heading.getX()).normalize();
            StepCandidate fallback = fallbackCandidate(current, heading, left);
            if (fallback == null) {
                break;
            }
            path.add(fallback.location());
        }
        return path.size() >= desired ? path : List.of();
    }

    private Location candidateLocation(Location origin, Vector direction, double distance, double height) {
        Location next = origin.clone()
                .add(direction.clone().multiply(distance))
                .add(0, height, 0);
        if (ThreadLocalRandom.current().nextBoolean()) {
            double tilt = ThreadLocalRandom.current().nextDouble(-0.35, 0.35);
            next.add(direction.clone().crossProduct(new Vector(0, 1, 0)).normalize().multiply(tilt));
        }
        return alignToBlockCenter(next);
    }

    private boolean isPathClear(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(4, (int) Math.ceil(delta.length() / 0.55));
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            Vector point = from.toVector().clone().add(delta.clone().multiply(t));
            Location probe = new Location(from.getWorld(), point.getX(), point.getY(), point.getZ());
            if (!isSpaceFree(probe)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSpaceFree(Location location) {
        Location base = alignToBlockCenter(location);
        World world = base.getWorld();
        if (world == null) {
            return false;
        }
        int centerX = base.getBlockX();
        int centerY = base.getBlockY();
        int centerZ = base.getBlockZ();

        for (int y = centerY - 1; y <= centerY + 2; y++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (y == centerY - 1) {
                        Material type = block.getType();
                        if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) {
                            return false;
                        }
                        continue;
                    }
                    if (!isPassable(block)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isPassable(Block block) {
        if (block.isEmpty()) {
            return true;
        }
        Material type = block.getType();
        return !type.isSolid() && !block.isLiquid();
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

    private Vector normalisedHorizontal(Vector vector) {
        Vector copy = vector.clone().setY(0);
        if (copy.lengthSquared() < 1.0E-4) {
            return new Vector(1, 0, 0);
        }
        return copy.normalize();
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
        defaults.add(new DifficultySegment(1, easyEnd, 2.25, 0.55));
        if (easyEnd >= steps) {
            return defaults;
        }
        int mediumEnd = Math.min(steps, Math.max(easyEnd + 1, 24));
        defaults.add(new DifficultySegment(easyEnd + 1, mediumEnd, 2.65, 0.75));
        if (mediumEnd >= steps) {
            return defaults;
        }
        defaults.add(new DifficultySegment(mediumEnd + 1, steps, 2.95, 0.95));
        return defaults;
    }

    private Set<Integer> loadCheckpoints(int steps) {
        Set<Integer> checkpoints = new HashSet<>();
        checkpoints.add(0); // always include the start
        int maxIndex = Math.max(1, steps - 1);
        for (int step = 5; step <= maxIndex; step += 5) {
            checkpoints.add(step);
        }
        List<Integer> configured = plugin.getConfig().getIntegerList("items.jumpAndRun.checkpoints");
        for (Integer value : configured) {
            if (value == null) {
                continue;
            }
            int normalized = Math.max(1, Math.min(maxIndex, value));
            checkpoints.add(normalized);
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

    private void restoreMovement(Player player, Run run) {
        if (run.restoreDoubleJump) {
            plugin.doubleJump().restoreAfterFlight(player);
        }
        if (run.restoreFlight) {
            player.setAllowFlight(true);
            if (run.restoreFlying) {
                player.setFlying(true);
            }
            plugin.doubleJump().disableForFlight(player);
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
