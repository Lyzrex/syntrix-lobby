package net.lyzrex.syntrix.lobby;

import net.lyzrex.syntrix.lobby.core.DeathLogService;
import net.lyzrex.syntrix.lobby.core.JumpAndRunService;
import net.lyzrex.syntrix.lobby.core.PlayerHiderService;
import net.lyzrex.syntrix.lobby.core.VanishService;
import net.lyzrex.syntrix.lobby.manager.CommandManager;
import net.lyzrex.syntrix.lobby.manager.ListenerManager;
import net.lyzrex.syntrix.lobby.core.DoubleJumpService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class SyntrixLobby extends JavaPlugin {


    public static NamespacedKey ITEM_LOCK;
    public static NamespacedKey NAV_TAG;
    public static NamespacedKey NAV_SERVER;
    public static NamespacedKey SILENT_TOG;
    public static NamespacedKey JNR_ACTIVE;
    public static NamespacedKey AUTOJOIN_OFF;
    public static NamespacedKey BUILD_MODE;


    private FileConfiguration config;
    private YamlConfiguration messages;


    private BukkitTask timeTask;
    private BukkitTask particleTask;
    private BukkitTask weatherTask;


    private VanishService vanish;
    private PlayerHiderService playerHider;
    private DeathLogService deathLogs;
    private JumpAndRunService jumpAndRun;

    private DoubleJumpService doubleJump;

    public VanishService vanish() {
        return vanish;
    }

    public PlayerHiderService playerHider() {
        return playerHider;
    }

    public DeathLogService deathLogs() {
        return deathLogs;
    }

    public JumpAndRunService jumpAndRun() {
        return jumpAndRun;
    }

    public DoubleJumpService doubleJump() {
        return doubleJump;
    }

    @Override
    public FileConfiguration getConfig() {
        return config;
    }

    public YamlConfiguration messages() {
        return messages;
    }


    @Override
    public void onEnable() {
        initKeys();
        setupConfig();
        setupMessages();



        this.vanish = new VanishService(this);
        this.vanish.init();

        this.playerHider = new PlayerHiderService(this);


        this.deathLogs = new DeathLogService(this);
        this.deathLogs.init();

        this.jumpAndRun = new JumpAndRunService(this);
        this.jumpAndRun.init();

        this.doubleJump = new DoubleJumpService(this);

        ListenerManager.registerAll(this);
        CommandManager.registerAll(this);


        startTimeControl();
        startSpawnParticles();
        startWeatherControl();

        getLogger().info("Syntrix-Lobby enabled.");
    }

    @Override
    public void onDisable() {
        cancelTask(timeTask);
        cancelTask(particleTask);
        cancelTask(weatherTask);

        if (deathLogs != null) deathLogs.shutdown();
        if (jumpAndRun != null) jumpAndRun.shutdown();
        timeTask = null;
        particleTask = null;
        weatherTask = null;
        getLogger().info("Syntrix-Lobby disabled.");
    }


    private void initKeys() {
        ITEM_LOCK = new NamespacedKey(this, "item_lock");
        NAV_TAG = new NamespacedKey(this, "nav_trigger");
        NAV_SERVER = new NamespacedKey(this, "nav_server");
        SILENT_TOG = new NamespacedKey(this, "silent_toggle");
        JNR_ACTIVE = new NamespacedKey(this, "jnr_trigger");
        AUTOJOIN_OFF = new NamespacedKey(this, "autojoin_off");
        BUILD_MODE = new NamespacedKey(this, "build_mode");
    }

    private void setupConfig() {
        saveDefaultConfig();
        this.config = super.getConfig();
    }

    private void setupMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        this.messages = new YamlConfiguration();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            this.messages.load(r);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Could not load messages.yml: " + e.getMessage());
        }
    }

    public long reloadAll() {
        long start = System.currentTimeMillis();

        super.reloadConfig();
        this.config = super.getConfig();


        setupMessages();


        if (this.vanish != null) this.vanish.refreshAll();
        if (this.playerHider != null) this.playerHider.refreshAll();
        if (this.deathLogs == null) this.deathLogs = new DeathLogService(this);
        this.deathLogs.reload();
        if (this.jumpAndRun == null) this.jumpAndRun = new JumpAndRunService(this);
        this.jumpAndRun.reload();
        if (this.doubleJump == null) this.doubleJump = new DoubleJumpService(this);
        this.doubleJump.reload();


        restartTimeControl();
        restartSpawnParticles();
        restartWeatherControl();


        return System.currentTimeMillis() - start;
    }


    public void startTimeControl() {
        cancelTask(timeTask);
        if (!config.getBoolean("timeControl.enabled", true)) return;

        final long interval = config.getLong("timeControl.intervalTicks", 200L);
        final long fixed = config.getLong("timeControl.fixed-time", 1000L);
        final String worldCfg = config.getString("timeControl.world", "");

        Runnable job = () -> {
            World w = (worldCfg == null || worldCfg.isBlank())
                    ? Bukkit.getWorld(config.getString("lobby.world", "world"))
                    : Bukkit.getWorld(worldCfg);
            if (w != null && w.getTime() != fixed) w.setTime(fixed);
        };

        timeTask = Bukkit.getScheduler()
                .runTaskTimer(this, job, 20L, Math.max(1L, interval));
    }

    public void restartTimeControl() {
        startTimeControl();
    }

    public void startSpawnParticles() {
        cancelTask(particleTask);
        if (!config.getBoolean("spawnParticles.enabled", true)) return;

        final String worldName = config.getString("lobby.world", "world");
        final World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        final String typeName = config.getString("spawnParticles.type", "FLAME");
        Particle particleType;
        try {
            particleType = Particle.valueOf(typeName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid particle type '" + typeName + "', defaulting to FLAME.");
            particleType = Particle.FLAME;
        }

        final double radius = config.getDouble("spawnParticles.radius", 2.5);
        final int points = Math.max(8, config.getInt("spawnParticles.points", 60));
        final long interval = Math.max(1L, config.getLong("spawnParticles.intervalTicks", 5));
        final double x = config.getDouble("lobby.spawn.x", 0.5);
        final double y = config.getDouble("lobby.spawn.y", 80.0);
        final double z = config.getDouble("lobby.spawn.z", 0.5);


        final Particle type = particleType;

        particleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            final double cy = y + 0.1;
            final double cx = x;
            final double cz = z;

            for (int i = 0; i < points; i++) {
                double angle = (2 * Math.PI) * (i / (double) points);
                double px = cx + Math.cos(angle) * radius;
                double pz = cz + Math.sin(angle) * radius;
                world.spawnParticle(type, px, cy, pz, 1, 0, 0, 0, 0);
            }
        }, 40L, interval);
    }

    private void restartSpawnParticles() {

        startSpawnParticles();
    }

    public void startWeatherControl() {
        cancelTask(weatherTask);
        if (!config.getBoolean("weatherControl.enabled", true)) {
            return;
        }
        final long interval = Math.max(1L, config.getLong("weatherControl.intervalTicks", 200L));
        final String configuredWorld = config.getString("weatherControl.world", "");
        Runnable job = () -> {
            String worldName = (configuredWorld == null || configuredWorld.isBlank())
                    ? config.getString("lobby.world", "world")
                    : configuredWorld;
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return;
            }
            if (config.getBoolean("weatherControl.lock-sun", true)) {
                if (world.hasStorm()) {
                    world.setStorm(false);
                }
                if (world.isThundering()) {
                    world.setThundering(false);
                }
                world.setWeatherDuration(0);
                world.setThunderDuration(0);
            }
        };
        weatherTask = Bukkit.getScheduler()
                .runTaskTimer(this, job, 20L, interval);
    }

    public void restartWeatherControl() {
        startWeatherControl();
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}