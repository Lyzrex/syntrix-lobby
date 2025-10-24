package net.lyzrex.syntrix.lobby.core;

import net.lyzrex.syntrix.lobby.SyntrixLobby;
import net.lyzrex.syntrix.lobby.db.DBManager.SessionCompletion;
import net.lyzrex.syntrix.lobby.db.DBManager.SessionLocation;
import net.lyzrex.syntrix.lobby.db.DBManager.SessionStart;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSessionService {

    private static final double MOVEMENT_EPSILON = 0.01D;
    private static final Map<Integer, String> PROTOCOL_VERSION_MAP = Map.ofEntries(
            Map.entry(765, "1.20.4"),
            Map.entry(764, "1.20.2-1.20.3"),
            Map.entry(763, "1.20-1.20.1"),
            Map.entry(762, "1.19.4"),
            Map.entry(761, "1.19.3"),
            Map.entry(760, "1.19.1-1.19.2"),
            Map.entry(759, "1.19"),
            Map.entry(758, "1.18.2"),
            Map.entry(757, "1.18"),
            Map.entry(756, "1.17.1"),
            Map.entry(755, "1.17"),
            Map.entry(754, "1.16.5"),
            Map.entry(753, "1.16.4"),
            Map.entry(751, "1.16.2-1.16.3"),
            Map.entry(736, "1.16-1.16.1"),
            Map.entry(735, "1.15.2"),
            Map.entry(734, "1.15"),
            Map.entry(578, "1.14.4"),
            Map.entry(498, "1.14.3"),
            Map.entry(490, "1.13.2"),
            Map.entry(389, "1.12.2"),
            Map.entry(340, "1.12"),
            Map.entry(316, "1.11"),
            Map.entry(210, "1.10"),
            Map.entry(110, "1.9"),
            Map.entry(47, "1.8")
    );

    private final SyntrixLobby plugin;
    private final Map<UUID, SessionData> sessions = new ConcurrentHashMap<>();
    private long afkThresholdMillis;
    private int taskId = -1;
    private Method protocolVersionMethod;

    public PlayerSessionService(SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public void init() {
        refreshAll();
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 20L);
    }

    public void refreshAll() {
        long seconds = plugin.getConfig().getLong("commands.playerinfo.afk-threshold-seconds", 120L);
        this.afkThresholdMillis = Math.max(1000L, seconds * 1000L);
    }

    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        sessions.clear();
    }

    public SessionStart startSession(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("startSession must be called on the main thread");
        }

        SessionData data = new SessionData(player.getUniqueId());
        data.joinPing = safePing(player);
        data.joinTps = currentServerTps();
        data.clientProtocol = resolveProtocol(player);
        data.clientVersion = resolveClientVersion(data.clientProtocol);
        data.clientBrand = safeBrand(player);
        data.clientMods = detectClientMods(player);
        data.bedrock = detectBedrock(player, data.clientBrand);
        data.lastKnownLocation = snapshotLocation(player.getLocation());
        data.lastMovementLocation = player.getLocation().clone();
        sessions.put(player.getUniqueId(), data);

        return new SessionStart(
                data.joinPing,
                Double.isNaN(data.joinTps) ? null : data.joinTps,
                data.clientVersion,
                data.clientProtocol,
                data.clientBrand,
                data.clientMods,
                data.bedrock
        );
    }

    public SessionCompletion finishSession(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("finishSession must be called on the main thread");
        }

        SessionData data = sessions.remove(player.getUniqueId());
        if (data == null) {
            return null;
        }
        data.flushAfk(System.currentTimeMillis());
        return data.toCompletion();
    }

    public void handleMovement(Player player, Location to) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleMovement(player, to));
            return;
        }
        SessionData data = sessions.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        data.updateLocation(player, to, now, afkThresholdMillis);
    }

    public void handleInteraction(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleInteraction(player));
            return;
        }
        SessionData data = sessions.get(player.getUniqueId());
        if (data != null) {
            data.markActive(System.currentTimeMillis());
        }
    }

    public void handleResourcePack(PlayerResourcePackStatusEvent event) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleResourcePack(event));
            return;
        }
        Player player = event.getPlayer();
        SessionData data = sessions.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        data.resourcePackStatus = event.getStatus().name();
        data.resourcePackHash = tryGetPackHash(event);
        data.resourcePackId = tryGetPackId(event);
    }

    public void handleDeath(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handleDeath(player));
            return;
        }
        SessionData data = sessions.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        Location loc = player.getLocation();
        data.lastDeathLocation = snapshotLocation(loc);
        data.lastDeathTimeMillis = System.currentTimeMillis();
    }

    public SessionSnapshot snapshot(UUID uuid) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("snapshot must be called on the main thread");
        }
        SessionData data = sessions.get(uuid);
        if (data == null) {
            return null;
        }
        return data.toSnapshot(System.currentTimeMillis());
    }

    private void tick() {
        double tps = currentServerTps();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            SessionData data = sessions.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            data.sample(player, now, tps, afkThresholdMillis);
        }
    }

    private double currentServerTps() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            Object value = method.invoke(Bukkit.getServer());
            if (value instanceof double[] arr && arr.length > 0) {
                return arr[0];
            }
        } catch (Throwable ignored) {
        }
        return Double.NaN;
    }

    private int safePing(Player player) {
        try {
            return player.spigot().getPing();
        } catch (Throwable ignored) {
        }
        try {
            Method method = player.getClass().getMethod("getPing");
            Object result = method.invoke(player);
            if (result instanceof Number number) {
                return number.intValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private String safeBrand(Player player) {
        try {
            return player.getClientBrandName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer resolveProtocol(Player player) {
        try {
            if (protocolVersionMethod == null) {
                protocolVersionMethod = player.getClass().getMethod("getProtocolVersion");
                protocolVersionMethod.setAccessible(true);
            }
            Object result = protocolVersionMethod.invoke(player);
            if (result instanceof Number number) {
                return number.intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String resolveClientVersion(Integer protocol) {
        if (protocol == null) {
            return null;
        }
        return PROTOCOL_VERSION_MAP.get(protocol);
    }

    private String detectClientMods(Player player) {
        Set<String> raw;
        try {
            raw = player.getListeningPluginChannels();
        } catch (Throwable ignored) {
            raw = Collections.emptySet();
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Set<String> detected = new LinkedHashSet<>();
        for (String channel : raw) {
            String lower = channel.toLowerCase(Locale.ROOT);
            if (lower.contains("fml") || lower.contains("forge")) {
                detected.add("Forge");
            } else if (lower.contains("fabric")) {
                detected.add("Fabric");
            } else if (lower.contains("quilt")) {
                detected.add("Quilt");
            } else if (lower.contains("laby")) {
                detected.add("LabyMod");
            }
        }
        if (detected.isEmpty()) {
            return null;
        }
        return String.join(", ", detected);
    }

    private boolean detectBedrock(Player player, String brand) {
        if (brand != null) {
            String lower = brand.toLowerCase(Locale.ROOT);
            if (lower.contains("geyser") || lower.contains("floodgate") || lower.contains("bedrock")) {
                return true;
            }
        }
        try {
            Set<String> channels = player.getListeningPluginChannels();
            if (channels != null) {
                for (String channel : channels) {
                    String lower = channel.toLowerCase(Locale.ROOT);
                    if (lower.contains("geyser") || lower.contains("floodgate")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private SessionLocation snapshotLocation(Location location) {
        if (location == null) {
            return null;
        }
        String world = location.getWorld() != null ? location.getWorld().getName() : null;
        return new SessionLocation(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private String tryGetPackHash(PlayerResourcePackStatusEvent event) {
        try {
            Method method = event.getClass().getMethod("getHash");
            Object result = method.invoke(event);
            if (result instanceof String hash && !hash.isBlank()) {
                return hash;
            }
            if (result instanceof byte[] bytes && bytes.length > 0) {
                StringBuilder sb = new StringBuilder(bytes.length * 2);
                for (byte b : bytes) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String tryGetPackId(PlayerResourcePackStatusEvent event) {
        try {
            Method method = event.getClass().getMethod("getID");
            Object result = method.invoke(event);
            if (result instanceof String id && !id.isBlank()) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public record SessionSnapshot(long startMillis,
                                  long durationMillis,
                                  long afkMillis,
                                  int joinPing,
                                  Integer currentPing,
                                  Integer averagePing,
                                  Integer minPing,
                                  Integer maxPing,
                                  Double joinTps,
                                  Double lastTps,
                                  String clientVersion,
                                  Integer clientProtocol,
                                  String clientBrand,
                                  String clientMods,
                                  boolean bedrock,
                                  SessionLocation currentLocation,
                                  SessionLocation lastDeathLocation,
                                  Long lastDeathTimeMillis,
                                  String resourcePackStatus,
                                  String resourcePackId,
                                  String resourcePackHash) { }

    private final class SessionData {
        private final UUID uuid;
        private final long startMillis = System.currentTimeMillis();
        private long lastActivityMillis = startMillis;
        private long afkAccumulatedMillis = 0L;
        private long afkStartMillis = -1L;
        private int pingSamples = 0;
        private long pingSum = 0L;
        private int pingMin = Integer.MAX_VALUE;
        private int pingMax = Integer.MIN_VALUE;
        private int currentPing = -1;
        private int joinPing = -1;
        private double joinTps = Double.NaN;
        private double lastTps = Double.NaN;
        private Integer clientProtocol;
        private String clientVersion;
        private String clientBrand;
        private String clientMods;
        private boolean bedrock = false;
        private SessionLocation lastKnownLocation;
        private Location lastMovementLocation;
        private SessionLocation lastDeathLocation;
        private long lastDeathTimeMillis = -1L;
        private String resourcePackStatus;
        private String resourcePackHash;
        private String resourcePackId;

        private SessionData(UUID uuid) {
            this.uuid = uuid;
        }

        private void sample(Player player, long now, double serverTps, long afkThresholdMillis) {
            this.currentPing = safePing(player);
            if (currentPing >= 0) {
                pingSamples++;
                pingSum += currentPing;
                pingMin = Math.min(pingMin, currentPing);
                pingMax = Math.max(pingMax, currentPing);
            }
            updateLocation(player, player.getLocation(), now, afkThresholdMillis);
            this.lastTps = serverTps;
        }

        private void updateLocation(Player player, Location to, long now, long threshold) {
            if (to == null) {
                return;
            }
            if (lastMovementLocation == null) {
                lastMovementLocation = to.clone();
            }
            boolean moved = moved(lastMovementLocation, to);
            lastKnownLocation = snapshotLocation(to);
            if (moved) {
                lastMovementLocation = to.clone();
                markActive(now);
            } else {
                evaluateAfk(now, threshold);
            }
        }

        private boolean moved(Location from, Location to) {
            if (from == null || to == null) {
                return false;
            }
            if (from.getWorld() != null && to.getWorld() != null && !from.getWorld().equals(to.getWorld())) {
                return true;
            }
            return from.distanceSquared(to) > MOVEMENT_EPSILON;
        }

        private void markActive(long now) {
            lastActivityMillis = now;
            if (afkStartMillis != -1L) {
                afkAccumulatedMillis += Math.max(0L, now - afkStartMillis);
                afkStartMillis = -1L;
            }
        }

        private void evaluateAfk(long now, long threshold) {
            if (threshold <= 0L) {
                return;
            }
            long inactive = now - lastActivityMillis;
            if (inactive >= threshold) {
                if (afkStartMillis == -1L) {
                    afkStartMillis = lastActivityMillis + threshold;
                    if (afkStartMillis > now) {
                        afkStartMillis = now;
                    }
                }
            } else if (afkStartMillis != -1L) {
                afkAccumulatedMillis += Math.max(0L, now - afkStartMillis);
                afkStartMillis = -1L;
            }
        }

        private void flushAfk(long now) {
            if (afkStartMillis != -1L) {
                afkAccumulatedMillis += Math.max(0L, now - afkStartMillis);
                afkStartMillis = -1L;
            }
        }

        private SessionCompletion toCompletion() {
            long now = System.currentTimeMillis();
            long duration = Math.max(0L, now - startMillis);
            long afk = totalAfk(now);
            Integer avgPing = (pingSamples > 0) ? (int) Math.round((double) pingSum / pingSamples) : null;
            Integer min = (pingSamples > 0) ? pingMin : null;
            Integer max = (pingSamples > 0) ? pingMax : null;
            Integer current = currentPing >= 0 ? currentPing : null;
            Double join = Double.isNaN(joinTps) ? null : joinTps;
            Double last = Double.isNaN(lastTps) ? null : lastTps;
            Long deathTime = lastDeathTimeMillis > 0L ? lastDeathTimeMillis : null;

            return new SessionCompletion(
                    duration,
                    afk,
                    avgPing,
                    min,
                    max,
                    current,
                    last,
                    lastKnownLocation,
                    lastDeathLocation,
                    deathTime,
                    resourcePackId,
                    resourcePackStatus,
                    resourcePackHash,
                    null,
                    clientVersion,
                    clientProtocol,
                    clientBrand,
                    clientMods,
                    bedrock
            );
        }

        private SessionSnapshot toSnapshot(long now) {
            long duration = Math.max(0L, now - startMillis);
            return new SessionSnapshot(
                    startMillis,
                    duration,
                    totalAfk(now),
                    joinPing,
                    currentPing >= 0 ? currentPing : null,
                    (pingSamples > 0) ? (int) Math.round((double) pingSum / pingSamples) : null,
                    (pingSamples > 0) ? pingMin : null,
                    (pingSamples > 0) ? pingMax : null,
                    Double.isNaN(joinTps) ? null : joinTps,
                    Double.isNaN(lastTps) ? null : lastTps,
                    clientVersion,
                    clientProtocol,
                    clientBrand,
                    clientMods,
                    bedrock,
                    lastKnownLocation,
                    lastDeathLocation,
                    lastDeathTimeMillis > 0L ? lastDeathTimeMillis : null,
                    resourcePackStatus,
                    resourcePackId,
                    resourcePackHash
            );
        }

        private long totalAfk(long now) {
            long total = afkAccumulatedMillis;
            if (afkStartMillis != -1L) {
                total += Math.max(0L, now - afkStartMillis);
            }
            return total;
        }
    }
}