package net.lyzrex.syntrix.lobby;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lyzrex.syntrix.lobby.core.PlayerHiderService;
import net.lyzrex.syntrix.lobby.core.VanishService;
import net.lyzrex.syntrix.lobby.db.DBManager;
import net.lyzrex.syntrix.lobby.manager.CommandManager;
import net.lyzrex.syntrix.lobby.manager.ListenerManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

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


    private int timeTaskId     = -1;
    private int particleTaskId = -1;

    @SuppressWarnings("FieldCanBeLocal")
    private final MiniMessage mm = MiniMessage.miniMessage();


    private DBManager db;
    private VanishService vanish;
    private PlayerHiderService playerHider;

    public DBManager db()                { return db; }
    public VanishService vanish()        { return vanish; }
    public PlayerHiderService playerHider() { return playerHider; }
    @Override public FileConfiguration getConfig() { return config; }
    public YamlConfiguration messages()  { return messages; }


    @Override
    public void onEnable() {
        initKeys();
        setupConfig();
        setupMessages();


        this.db = new DBManager(this);
        this.db.init();

        this.vanish = new VanishService(this);
        this.vanish.init();

        this.playerHider = new PlayerHiderService(this);


        ListenerManager.registerAll(this);
        CommandManager.registerAll(this);


        startTimeControl();
        startSpawnParticles();

        getLogger().info("Syntrix-Lobby enabled.");
    }

    @Override
    public void onDisable() {
        if (timeTaskId != -1)     Bukkit.getScheduler().cancelTask(timeTaskId);
        if (particleTaskId != -1) Bukkit.getScheduler().cancelTask(particleTaskId);
        timeTaskId = -1;
        particleTaskId = -1;
        getLogger().info("Syntrix-Lobby disabled.");
    }


    private void initKeys() {
        ITEM_LOCK    = new NamespacedKey(this, "item_lock");
        NAV_TAG      = new NamespacedKey(this, "nav_trigger");
        NAV_SERVER   = new NamespacedKey(this, "nav_server");
        SILENT_TOG   = new NamespacedKey(this, "silent_toggle");
        JNR_ACTIVE   = new NamespacedKey(this, "jnr_trigger");
        AUTOJOIN_OFF = new NamespacedKey(this, "autojoin_off");
        BUILD_MODE   = new NamespacedKey(this, "build_mode");
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


        if (this.db == null) this.db = new DBManager(this);
        this.db.init();


        if (this.vanish != null)      this.vanish.refreshAll();
        if (this.playerHider != null) this.playerHider.refreshAll();


        restartTimeControl();
        restartSpawnParticles();


        return System.currentTimeMillis() - start;
    }


    public void startTimeControl() {
        if (!config.getBoolean("timeControl.enabled", true)) return;

        final long interval   = config.getLong("timeControl.intervalTicks", 200L);
        final long fixed      = config.getLong("timeControl.fixed-time", 1000L);
        final String worldCfg = config.getString("timeControl.world", "");

        Runnable job = () -> {
            World w = (worldCfg == null || worldCfg.isBlank())
                    ? Bukkit.getWorld(config.getString("lobby.world", "world"))
                    : Bukkit.getWorld(worldCfg);
            if (w != null && w.getTime() != fixed) w.setTime(fixed);
        };

        timeTaskId = Bukkit.getScheduler()
                .scheduleSyncRepeatingTask(this, job, 20L, Math.max(1L, interval));
    }

    public void restartTimeControl() {
        if (timeTaskId != -1) Bukkit.getScheduler().cancelTask(timeTaskId);
        timeTaskId = -1;
        startTimeControl();
    }

    public void startSpawnParticles() {
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
        final int points    = Math.max(8, config.getInt("spawnParticles.points", 60));
        final long interval = Math.max(1L, config.getLong("spawnParticles.intervalTicks", 5));
        final double x      = config.getDouble("lobby.spawn.x", 0.5);
        final double y      = config.getDouble("lobby.spawn.y", 80.0);
        final double z      = config.getDouble("lobby.spawn.z", 0.5);


        final Particle type = particleType;

        particleTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
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
        if (particleTaskId != -1) Bukkit.getScheduler().cancelTask(particleTaskId);
        particleTaskId = -1;
        startSpawnParticles();
    }
}