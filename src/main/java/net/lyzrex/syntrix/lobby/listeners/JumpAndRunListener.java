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
    private static final double MIN_STEP_HORIZONTAL_DISTANCE_SQ = 3.05D;
    private static final int MIN_STEP_BLOCK_SEPARATION = 2;
    private static final double MIN_STEP_VERTICAL_DELTA = 0.6D;
    private static final long STATUS_DISPLAY_DURATION_MS = 3000L;
    private static final int TOTAL_STEPS = 40;
    private static final double HEIGHT_CAP_EPSILON = 0.01D;
    private static final Material CHECKPOINT_MATERIAL = Material.BLACK_STAINED_GLASS;
    private static final Material[] STEP_COLORS = {
            Material.WHITE_STAINED_GLASS,
            Material.ORANGE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS,
            Material.YELLOW_STAINED_GLASS,
            Material.LIME_STAINED_GLASS,
            Material.PINK_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS,
            Material.LIGHT_GRAY_STAINED_GLASS,
            Material.CYAN_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS,
            Material.BROWN_STAINED_GLASS,
            Material.GREEN_STAINED_GLASS,
            Material.RED_STAINED_GLASS
    };

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
        final Material playerMaterial;
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
        long displayBaseNano;
        String persistentStatus;
        long statusExpiryMillis;

        Run(List<Location> steps,
            Material playerMaterial,
            double cancelThreshold,
            Set<Integer> checkpoints,
            boolean restoreFlight,
            boolean restoreFlying,
            boolean restoreDoubleJump) {
            this.steps = steps;
            this.playerMaterial = playerMaterial;
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
            this.displayBaseNano = this.startNano;
            this.persistentStatus = "";
            this.statusExpiryMillis = 0L;
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

    private double resolveMaxStepY(Location location) {
        World world = location.getWorld();
        return world == null ? Double.POSITIVE_INFINITY : world.getMaxHeight();
    }

    private boolean isNearHeightCap(double y, double maxStepY) {
        return y >= maxStepY - HEIGHT_CAP_EPSILON;
    }

    private boolean hasBuildMode(Player player) {
        return player.getPersistentDataContainer().has(SyntrixLobby.BUILD_MODE, PersistentDataType.BYTE);
    }

    private void sendFailureActionBar(Player player, String messageKey, String fallback) {
        String message = plugin.messages().getString(messageKey, fallback);
        if (message == null || message.isBlank()) {
            message = fallback;
        }
        MessageUtil.sendActionBar(player, message);
    }

    private Material selectPlayerMaterial(Player player, Material fallback) {
        if (STEP_COLORS.length == 0) {
            return fallback;
        }
        int index = Math.floorMod(player.getUniqueId().hashCode(), STEP_COLORS.length);
        Material chosen = STEP_COLORS[index];
        return chosen != null ? chosen : fallback;
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
            sendFailureActionBar(player, "jumpandrun.no-permission",
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
                sendFailureActionBar(player, "jumpandrun.already-running",
                        "<red>You already have an active Jump & Run. Sneak-right-click to cancel.</red>");
            }
            return;
        }

        startRun(player);
    }

    private void startRun(Player player) {
        int totalSteps = TOTAL_STEPS;
        double cancelBelow = plugin.getConfig().getDouble("items.jumpAndRun.cancel-fall-below", 2.0);

        Material defaultMaterial = Material.matchMaterial(
                plugin.getConfig().getString("items.jumpAndRun.block-material", "LIGHT_BLUE_STAINED_GLASS")
        );
        if (defaultMaterial == null) {
            defaultMaterial = Material.LIGHT_BLUE_STAINED_GLASS;
        }
        Material playerMaterial = selectPlayerMaterial(player, defaultMaterial);

        List<DifficultySegment> segments = loadSegments(totalSteps);
        Set<Integer> checkpoints = loadCheckpoints(totalSteps);
        List<Location> steps = buildSteps(player, totalSteps, segments);
        if (steps.size() != totalSteps) {
            steps = buildEmergencyPath(player, totalSteps);
        }
        if (steps.size() != totalSteps) {
            sendFailureActionBar(player, "jumpandrun.failed",
                    "<red>No valid Jump & Run path could be generated here.</red>");
            return;
        }

        UUID id = player.getUniqueId();
        var doubleJump = plugin.doubleJump();
        boolean doubleJumpActive = doubleJump != null && !doubleJump.isTemporarilyDisabled(id);
        boolean restoreFlight = !doubleJumpActive && player.getAllowFlight();
        boolean restoreFlying = player.isFlying();
        if (doubleJumpActive && doubleJump != null) {
            doubleJump.disableForFlight(player);
        }
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0.0F);

        Run run = new Run(steps, playerMaterial, cancelBelow, checkpoints, restoreFlight, restoreFlying, doubleJumpActive);
        activeRuns.put(player.getUniqueId(), run);

        placePlatform(run, 0);

        if (run.nextIndex > 0) {
            placePlatform(run, run.nextIndex);
        }
        teleportToStep(player, run, run.lastReachedIndex);

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
                } else {
                    showActionBar(player, currentRun, null, null);
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
        restorePlatform(run, run.lastReachedIndex);
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
        placePlatform(run, run.nextIndex);
        showActionBar(player, run, null, null);
        playSound(player, "step", "block.note_block.hat");
    }

    private void showActionBar(Player player, Run run, String statusKey, String defaultStatus) {
        long nowNano = System.nanoTime();
        long elapsedMillis = Duration.ofNanos(nowNano - run.displayBaseNano).toMillis();
        long nowMillis = System.currentTimeMillis();
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
        if (status != null) {
            status = fillPlaceholders(status, elapsedMillis, currentStep, totalSteps, leftText, checkpointStep);
            run.persistentStatus = status == null ? "" : status;
            run.statusExpiryMillis = nowMillis + STATUS_DISPLAY_DURATION_MS;
        } else if (run.statusExpiryMillis > nowMillis && run.persistentStatus != null && !run.persistentStatus.isBlank()) {
            status = fillPlaceholders(run.persistentStatus, elapsedMillis, currentStep, totalSteps, leftText, checkpointStep);
        } else {
            run.persistentStatus = "";
        }

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
        placePlatform(run, run.lastReachedIndex);
        if (run.nextIndex > run.lastReachedIndex) {
            placePlatform(run, run.nextIndex);
        }
        teleportToStep(player, run, run.lastReachedIndex);
        player.setFallDistance(0.0F);
        showActionBar(player, run, null, null);
    }

    private void teleportToStep(Player player, Run run, int index) {
        if (run.steps.isEmpty()) {
            return;
        }

        int clampedIndex = Math.max(0, Math.min(index, run.steps.size() - 1));
        Location origin = run.steps.get(clampedIndex);
        Location target = origin.clone().add(0, 0.2, 0);

        int lookIndex = run.nextIndex > clampedIndex
                ? run.nextIndex
                : Math.min(run.steps.size() - 1, clampedIndex + 1);
        if (lookIndex != clampedIndex) {
            Location lookAt = run.steps.get(lookIndex);
            if (lookAt.getWorld() != null && origin.getWorld() != null && lookAt.getWorld().equals(origin.getWorld())) {
                Vector direction = lookAt.toVector().subtract(origin.toVector());
                if (direction.lengthSquared() > 1.0E-4) {
                    target.setDirection(direction);
                }
            }
        }

        player.teleport(target);
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

    private List<Location> buildSteps(Player player, int steps, List<DifficultySegment> segments) {
        List<Location> locations = new ArrayList<>(steps);
        Location base = alignToBlockCenter(player.getLocation());
        base.setPitch(0);
        double raisedY = Math.floor(base.getY()) + 1.0D;
        base.setY(raisedY);
        locations.add(base);

        Vector direction = normalisedHorizontal(player.getLocation().getDirection());
        Vector left = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
        DifficultySegment fallback = segments.isEmpty()
                ? new DifficultySegment(1, steps, 2.75, 0.65)
                : segments.get(segments.size() - 1);

        Location current = base;
        StepDirectionType previousType = StepDirectionType.STRAIGHT;
        double maxStepY = resolveMaxStepY(base);
        for (int stepIndex = 2; stepIndex <= steps; stepIndex++) {
            DifficultySegment segment = findSegment(segments, stepIndex, fallback);
            StepCandidate candidate = findNextStep(current, direction, left, segment, previousType, maxStepY);
            if (candidate == null) {
                candidate = fallbackCandidate(current, direction, left, maxStepY);
            }
            if (candidate == null) {
                break;
            }
            Location next = candidate.location();
            if (!isAcceptableStep(current, next, maxStepY)) {
                stepIndex--;
                continue;
            }
            locations.add(next);
            direction = candidate.heading();
            left = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
            current = next;
            previousType = candidate.type();
        }
        if (locations.size() != steps) {
            List<Location> padded = padWithFallback(locations, steps, maxStepY);
            if (padded.size() == steps) {
                return padded;
            }
            List<Location> extended = fillStraightPath(locations, steps, maxStepY);
            if (extended.size() == steps) {
                return extended;
            }
            return List.of();
        }
        return locations;
    }

    private List<Location> buildEmergencyPath(Player player, int steps) {
        Location base = alignToBlockCenter(player.getLocation());
        base.setPitch(0);
        base.setY(Math.floor(base.getY()) + 1.0D);
        double maxStepY = resolveMaxStepY(base);
        List<Location> seed = new ArrayList<>(List.of(base));
        List<Location> padded = padWithFallback(seed, steps, maxStepY);
        if (padded.size() == steps) {
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
                heading.add(left.clone().multiply(0.75));
            } else if (i % 3 == 1) {
                heading.subtract(left.clone().multiply(0.55));
            }
            heading = normalisedHorizontal(heading);

            double distance = 2.35;
            double height = 0.6 + (i % 2 == 0 ? 0.15 : -0.1);
            Location candidate = alignToBlockCenter(current.clone().add(heading.clone().multiply(distance)).add(0, height, 0));
            candidate = ensureMinimumVariation(current, candidate, maxStepY);

            int attempts = 0;
            while (!isPathClear(current, candidate, maxStepY) && attempts++ < 6) {
                candidate.add(0, 0.3, 0);
            }
            if (!isPathClear(current, candidate, maxStepY)) {
                candidate = alignToBlockCenter(current.clone().add(0, 0.6, 0));
            }

            if (!isAcceptableStep(current, candidate, maxStepY)) {
                break;
            }

            fallback.add(candidate);
            current = candidate;
            forward = heading;
            left = new Vector(-forward.getZ(), 0, forward.getX()).normalize();
        }
        if (fallback.size() < steps) {
            fallback = fillStraightPath(fallback, steps, maxStepY);
        }
        return fallback.size() == steps ? fallback : List.of();
    }

    private StepCandidate findNextStep(Location current,
                                       Vector forward,
                                       Vector left,
                                       DifficultySegment segment,
                                       StepDirectionType previousType,
                                       double maxStepY) {
        double baseDistance = Math.max(2.35, Math.min(3.2, segment.distance()));
        double distanceScale = ThreadLocalRandom.current().nextDouble(0.94, 1.08);
        double adjustedDistance = Math.max(2.2, Math.min(3.35, baseDistance * distanceScale));
        double adjustedHeight = Math.max(0.25,
                Math.min(0.9, segment.height() + ThreadLocalRandom.current().nextDouble(-0.05, 0.2)));

        List<StepCandidate> candidates = new ArrayList<>();
        Vector straight = normalisedHorizontal(forward);
        Vector rightDiagonal = normalisedHorizontal(forward.clone().add(left));
        Vector leftDiagonal = normalisedHorizontal(forward.clone().subtract(left));
        candidates.add(new StepCandidate(candidateLocation(current, straight, adjustedDistance, adjustedHeight, maxStepY),
                straight, StepDirectionType.STRAIGHT));
        candidates.add(new StepCandidate(candidateLocation(current, rightDiagonal, adjustedDistance, adjustedHeight, maxStepY),
                rightDiagonal, StepDirectionType.DIAGONAL));
        candidates.add(new StepCandidate(candidateLocation(current, leftDiagonal, adjustedDistance, adjustedHeight, maxStepY),
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
            Location candidateLocation = ensureMinimumVariation(current, candidate.location(), maxStepY);
            if (candidateLocation != null && isPathClear(current, candidateLocation, maxStepY)) {
                if (Math.abs(candidateLocation.getY() - current.getY()) <= 1.25D
                        && isAcceptableStep(current, candidateLocation, maxStepY)) {
                    return new StepCandidate(candidateLocation, candidate.heading(), candidate.type());
                }
            }
        }

        double fallbackDistance = Math.max(2.1, Math.min(3.0, baseDistance * 0.98));
        double fallbackHeight = Math.min(1.0, adjustedHeight + ThreadLocalRandom.current().nextDouble(0.1, 0.3));
        for (StepCandidate candidate : prioritised) {
            Location location = ensureMinimumVariation(current,
                    candidateLocation(current, candidate.heading(), fallbackDistance, fallbackHeight, maxStepY), maxStepY);
            if (location != null && isPathClear(current, location, maxStepY)) {
                if (Math.abs(location.getY() - current.getY()) <= 1.25D
                        && isAcceptableStep(current, location, maxStepY)) {
                    return new StepCandidate(location, candidate.heading(), candidate.type());
                }
            }
        }
        return null;
    }

    private StepCandidate fallbackCandidate(Location current,
                                            Vector forward,
                                            Vector left,
                                            double maxStepY) {
        Vector straight = normalisedHorizontal(forward);
        Vector rightDiagonal = normalisedHorizontal(forward.clone().add(left));
        Vector leftDiagonal = normalisedHorizontal(forward.clone().subtract(left));
        Vector[] directions = {straight, rightDiagonal, leftDiagonal};
        StepDirectionType[] types = {StepDirectionType.STRAIGHT, StepDirectionType.DIAGONAL, StepDirectionType.DIAGONAL};
        double distance = 2.35;
        double height = 0.6;
        for (int i = 0; i < directions.length; i++) {
            Vector dir = directions[i];
            Location target = alignToBlockCenter(current.clone().add(dir.clone().multiply(distance)).add(0, height, 0));
            target = ensureMinimumVariation(current, target, maxStepY);
            int attempts = 0;
            while (!isPathClear(current, target, maxStepY) && attempts++ < 8) {
                target.add(0, 0.5, 0);
            }
            if (isPathClear(current, target, maxStepY) && Math.abs(target.getY() - current.getY()) <= 1.25D
                    && isAcceptableStep(current, target, maxStepY)) {
                return new StepCandidate(target, dir, types[i]);
            }
        }
        return null;
    }

    private List<Location> padWithFallback(List<Location> seed, int desired, double maxStepY) {
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
            StepCandidate fallback = fallbackCandidate(current, heading, left, maxStepY);
            if (fallback == null) {
                break;
            }
            Location next = ensureMinimumVariation(current, fallback.location(), maxStepY);
            if (!isAcceptableStep(current, next, maxStepY)) {
                break;
            }
            path.add(next);
        }
        return path.size() >= desired ? new ArrayList<>(path.subList(0, desired)) : List.of();
    }

    private List<Location> fillStraightPath(List<Location> seed, int desired, double maxStepY) {
        if (seed.isEmpty()) {
            return List.of();
        }
        List<Location> path = new ArrayList<>(seed);
        Location current = seed.get(seed.size() - 1).clone();
        Vector heading;
        if (path.size() >= 2) {
            heading = normalisedHorizontal(current.toVector().clone()
                    .subtract(path.get(path.size() - 2).toVector()));
        } else {
            heading = new Vector(1, 0, 0);
        }
        if (heading.lengthSquared() < 1.0E-4) {
            heading = new Vector(1, 0, 0);
        }
        while (path.size() < desired) {
            Vector offset = heading.clone().multiply(2.4);
            Location next = current.clone().add(offset).add(0, 0.65, 0);
            next = ensureMinimumVariation(current, next, maxStepY);
            next = alignToBlockCenter(next);
            int attempts = 0;
            while (!isPathClear(current, next, maxStepY) && attempts++ < 6) {
                next.add(0, 0.3, 0);
            }
            if (!isAcceptableStep(current, next, maxStepY)) {
                break;
            }
            path.add(next);
            current = next.clone();
            if (path.size() >= 2) {
                heading = normalisedHorizontal(path.get(path.size() - 1).toVector()
                        .subtract(path.get(path.size() - 2).toVector()));
                if (heading.lengthSquared() < 1.0E-4) {
                    heading = new Vector(1, 0, 0);
                }
            }
        }
        return path.size() == desired ? path : List.of();
    }

    private Location candidateLocation(Location origin, Vector direction, double distance, double height, double maxStepY) {
        Location next = origin.clone()
                .add(direction.clone().multiply(distance))
                .add(0, height, 0);
        if (ThreadLocalRandom.current().nextBoolean()) {
            double tilt = ThreadLocalRandom.current().nextDouble(-0.25, 0.25);
            next.add(direction.clone().crossProduct(new Vector(0, 1, 0)).normalize().multiply(tilt));
        }
        if (next.getY() > maxStepY) {
            next.setY(maxStepY);
        }
        return alignToBlockCenter(next);
    }

    private boolean isPathClear(Location from, Location to, double maxStepY) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(4, (int) Math.ceil(delta.length() / 0.55));
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            Vector point = from.toVector().clone().add(delta.clone().multiply(t));
            Location probe = new Location(from.getWorld(), point.getX(), point.getY(), point.getZ());
            if (probe.getY() > maxStepY + 1.0 || !isSpaceFree(probe)) {
                return false;
            }
        }
        return true;
    }

    private Location ensureMinimumVariation(Location current, Location candidate, double maxStepY) {
        if (candidate == null) {
            return null;
        }
        Location adjusted = candidate.clone();
        if (adjusted.getY() > maxStepY) {
            adjusted.setY(maxStepY);
        }
        double verticalDelta = adjusted.getY() - current.getY();
        double magnitude = Math.abs(verticalDelta);
        boolean nearCap = isNearHeightCap(adjusted.getY(), maxStepY) || isNearHeightCap(current.getY(), maxStepY);
        if (nearCap) {
            if (adjusted.getY() > maxStepY) {
                adjusted.setY(maxStepY);
            }
            if (current.getY() > maxStepY) {
                adjusted.setY(maxStepY);
            }
            if (adjusted.getY() < maxStepY && current.getY() >= maxStepY) {
                adjusted.setY(maxStepY);
            }
            return adjusted;
        }
        if (magnitude >= MIN_STEP_VERTICAL_DELTA) {
            return adjusted;
        }
        double direction = verticalDelta >= 0 ? 1.0 : -1.0;
        if (magnitude < 1.0E-3) {
            direction = ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0;
        }
        double adjustment = (MIN_STEP_VERTICAL_DELTA - magnitude) + 0.1;
        double targetY = adjusted.getY() + direction * adjustment;
        if (targetY > maxStepY) {
            targetY = maxStepY;
        }
        adjusted.setY(targetY);
        return adjusted;
    }

    private boolean isAcceptableStep(Location current, Location candidate, double maxStepY) {
        if (candidate == null) {
            return false;
        }
        if (current.getWorld() == null || candidate.getWorld() == null
                || !current.getWorld().equals(candidate.getWorld())) {
            return false;
        }
        if (candidate.getY() > maxStepY + 1.0E-3) {
            return false;
        }
        double dx = candidate.getX() - current.getX();
        double dz = candidate.getZ() - current.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq < MIN_STEP_HORIZONTAL_DISTANCE_SQ) {
            return false;
        }
        int blockDx = Math.abs(candidate.getBlockX() - current.getBlockX());
        int blockDz = Math.abs(candidate.getBlockZ() - current.getBlockZ());
        if (Math.max(blockDx, blockDz) < MIN_STEP_BLOCK_SEPARATION) {
            return false;
        }
        double verticalDelta = Math.abs(candidate.getY() - current.getY());
        boolean nearCap = isNearHeightCap(candidate.getY(), maxStepY) || isNearHeightCap(current.getY(), maxStepY);
        if (!nearCap && verticalDelta < MIN_STEP_VERTICAL_DELTA) {
            return false;
        }
        if (nearCap && candidate.getY() > maxStepY + 1.0E-3) {
            return false;
        }
        return nearCap || verticalDelta >= MIN_STEP_VERTICAL_DELTA;
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
                        if (!canSupportPlatform(block)) {
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

    private boolean canSupportPlatform(Block block) {
        if (block.isEmpty()) {
            return true;
        }
        return !block.isLiquid();
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
        defaults.add(new DifficultySegment(1, easyEnd, 2.6, 0.6));
        if (easyEnd >= steps) {
            return defaults;
        }
        int mediumEnd = Math.min(steps, Math.max(easyEnd + 1, 24));
        defaults.add(new DifficultySegment(easyEnd + 1, mediumEnd, 2.95, 0.8));
        if (mediumEnd >= steps) {
            return defaults;
        }
        defaults.add(new DifficultySegment(mediumEnd + 1, steps, 3.2, 0.95));
        return defaults;
    }

    private Set<Integer> loadCheckpoints(int steps) {
        Set<Integer> checkpoints = new HashSet<>();
        checkpoints.add(0); // start
        int[] desired = {5, 10, 15, 20, 25, 30, 35};
        for (int step : desired) {
            int clamped = Math.max(1, Math.min(steps, step));
            checkpoints.add(clamped - 1);
        }
        return checkpoints;
    }

    private void placePlatform(Run run, int index) {
        Location stepLocation = run.steps.get(Math.max(0, Math.min(index, run.steps.size() - 1)));
        Block block = stepLocation.clone().add(0, -1, 0).getBlock();
        run.originals.computeIfAbsent(block,
                key -> new BlockSnapshot(key.getType(), key.getBlockData().clone()));
        Material target = run.checkpoints.contains(index) ? CHECKPOINT_MATERIAL : run.playerMaterial;
        block.setType(target, false);
    }

    private void restorePlatform(Run run, int index) {
        Location stepLocation = run.steps.get(Math.max(0, Math.min(index, run.steps.size() - 1)));
        Block block = stepLocation.clone().add(0, -1, 0).getBlock();
        BlockSnapshot snapshot = run.originals.remove(block);
        if (snapshot != null) {
            snapshot.restore(block);
        }
    }

    private void cleanupPlatforms(Run run) {
        if (run == null) {
            return;
        }
        for (Map.Entry<Block, BlockSnapshot> entry : run.originals.entrySet()) {
            entry.getValue().restore(entry.getKey());
        }
        run.originals.clear();
    }

    private void restoreMovement(Player player, Run run) {
        var doubleJump = plugin.doubleJump();
        if (run.restoreDoubleJump && doubleJump != null) {
            doubleJump.restoreAfterFlight(player);
        }
        if (run.restoreFlight) {
            player.setAllowFlight(true);
            if (run.restoreFlying) {
                player.setFlying(true);
            }
            if (doubleJump != null) {
                doubleJump.disableForFlight(player);
            }
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
