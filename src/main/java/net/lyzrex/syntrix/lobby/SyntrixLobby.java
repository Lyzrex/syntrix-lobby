package net.lyzrex.syntrix.lobby;

import net.lyzrex.syntrix.lobby.core.*;
import net.lyzrex.syntrix.lobby.manager.*;
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

    public static NamespacedKey ITEM_LOCK, NAV_TAG, NAV_SERVER, GADGET_TAG, JNR_ACTIVE, AUTOJOIN_OFF, BUILD_MODE, FLY_STATE;

    private FileConfiguration config;
    private YamlConfiguration messages;

    private BukkitTask timeTask, particleTask, weatherTask;

    private VanishService vanish;
    private JumpAndRunService jumpAndRun;
    private DoubleJumpService doubleJump;

    private GameLogManager gameLogManager;
    private ChatLogManager chatLogManager;
    private LobbyScoreboard lobbyScoreboard;
    private LobbyActionBar lobbyActionBar;
    private GadgetManager gadgetManager;

    public VanishService vanish() { return vanish; }
    public JumpAndRunService jumpAndRun() { return jumpAndRun; }
    public DoubleJumpService doubleJump() { return doubleJump; }
    public GameLogManager gameLogManager() { return gameLogManager; }
    public ChatLogManager chatLogManager() { return chatLogManager; }

    @Override
    public FileConfiguration getConfig() { return config; }
    public YamlConfiguration messages() { return messages; }

    @Override
    public void onEnable() {
        initKeys();
        setupConfig();
        setupMessages();

        DatabaseSetup.init(this);

        this.gameLogManager = new GameLogManager(this);
        this.chatLogManager = new ChatLogManager(this);

        this.lobbyScoreboard = new LobbyScoreboard(this);
        this.lobbyScoreboard.start();

        this.lobbyActionBar = new LobbyActionBar(this);
        this.lobbyActionBar.start();

        this.vanish = new VanishService(this);
        this.vanish.init();

        this.jumpAndRun = new JumpAndRunService(this);
        this.jumpAndRun.init();

        this.doubleJump = new DoubleJumpService(this);
        this.gadgetManager = new GadgetManager(this);

        ListenerRegistry.registerAll(this);
        CommandRegistry.registerAll(this);

        startTimeControl();
        startSpawnParticles();
        startWeatherControl();
        startVerifyAutoChecker();

        // --- NEU: MOB KILLER BEIM START ---
        clearMobs();

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        getLogger().info("Syntrix-Lobby enabled with fully connected Database Logging & Verify!");
    }

    @Override
    public void onDisable() {
        if (timeTask != null) timeTask.cancel();
        if (particleTask != null) particleTask.cancel();
        if (weatherTask != null) weatherTask.cancel();

        if (lobbyScoreboard != null) lobbyScoreboard.stop();
        if (lobbyActionBar != null) lobbyActionBar.stop();
        if (jumpAndRun != null) jumpAndRun.shutdown();

        getLogger().info("Syntrix-Lobby disabled.");
    }

    private void initKeys() {
        ITEM_LOCK = new NamespacedKey(this, "item_lock"); NAV_TAG = new NamespacedKey(this, "nav_trigger"); NAV_SERVER = new NamespacedKey(this, "nav_server");
        GADGET_TAG = new NamespacedKey(this, "gadget_trigger"); JNR_ACTIVE = new NamespacedKey(this, "jnr_trigger"); AUTOJOIN_OFF = new NamespacedKey(this, "autojoin_off");
        BUILD_MODE = new NamespacedKey(this, "build_mode"); FLY_STATE = new NamespacedKey(this, "fly_state");
    }

    private void setupConfig() { saveDefaultConfig(); this.config = super.getConfig(); }

    private void setupMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        this.messages = new YamlConfiguration();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { this.messages.load(r); }
        catch (IOException | InvalidConfigurationException e) { getLogger().severe("Could not load messages.yml: " + e.getMessage()); }
    }

    public long reloadAll() {
        long start = System.currentTimeMillis();
        super.reloadConfig(); this.config = super.getConfig(); setupMessages();
        if (this.vanish != null) this.vanish.refreshAll();
        if (this.jumpAndRun != null) this.jumpAndRun.reload();
        if (this.doubleJump != null) this.doubleJump.reload();
        startTimeControl(); startSpawnParticles(); startWeatherControl();
        return System.currentTimeMillis() - start;
    }
    public void startVerifyAutoChecker() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                de.murmelmeister.murmelapi.user.User user = de.murmelmeister.murmelapi.MurmelAPI.getUserProvider().findByMojangId(p.getUniqueId());
                if (user != null) {
                    boolean pendingReward = de.murmelmeister.murmelapi.MurmelAPI.getDatabase().exists(
                            "SELECT 1 FROM discord_verify WHERE user_id = ? AND verified = TRUE AND reward_claimed = FALSE",
                            s -> s.setInt(1, user.id())
                    );

                    if (pendingReward) {
                        // Mark as claimed
                        de.murmelmeister.murmelapi.MurmelAPI.getDatabase().update(
                                "UPDATE discord_verify SET reward_claimed = TRUE WHERE user_id = ?",
                                s -> s.setInt(1, user.id())
                        );

                        // Add Economy Reward (10,000$) directly to the Database
                        de.murmelmeister.murmelapi.MurmelAPI.getDatabase().update(
                                "INSERT INTO economy_balances (user_id, balance) VALUES (?, 10000) ON DUPLICATE KEY UPDATE balance = balance + 10000",
                                s -> s.setInt(1, user.id())
                        );

                        Bukkit.getScheduler().runTask(this, () -> {
                            // Give Ingame Rank (LuckPerms)
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + p.getName() + " parent add verified");

                            p.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                                    "<#2AF598><bold>SUCCESS!</bold> Your account has been verified via Discord."
                            ));
                            p.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                                    "<#2AF598>You have received <#F6C35D>10,000$<#2AF598> and your ingame rank!"
                            ));
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                        });
                    }
                }
            }
        }, 100L, 100L); // Checks every 5 seconds
    }
    private void clearMobs() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String worldName = config.getString("lobby.world", "Lobby");
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                for (org.bukkit.entity.Entity e : w.getEntities()) {
                    if (e instanceof org.bukkit.entity.Monster || e instanceof org.bukkit.entity.Animals || e instanceof org.bukkit.entity.Slime || e instanceof org.bukkit.entity.Ghast) {
                        e.remove();
                    }
                }
            }
        }, 100L); // Führt den Clear 5 Sekunden nach Serverstart aus
    }

    public void startTimeControl() {
        cancelTask(timeTask);
        if (!config.getBoolean("timeControl.enabled", true)) return;
        final long interval = config.getLong("timeControl.intervalTicks", 200L);
        final long fixed = config.getLong("timeControl.fixed-time", 1000L);
        final String worldCfg = config.getString("timeControl.world", "");

        Runnable job = () -> {
            World w = (worldCfg == null || worldCfg.isBlank()) ? Bukkit.getWorld(config.getString("lobby.world", "world")) : Bukkit.getWorld(worldCfg);
            if (w != null && w.getTime() != fixed) w.setTime(fixed);
        };
        timeTask = Bukkit.getScheduler().runTaskTimer(this, job, 20L, Math.max(1L, interval));
    }

    public void startSpawnParticles() {
        cancelTask(particleTask);
        if (!config.getBoolean("spawnParticles.enabled", true)) return;

        final String worldName = config.getString("lobby.world", "world");
        final World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        final String typeName = config.getString("spawnParticles.type", "FLAME");
        Particle particleType;
        try { particleType = Particle.valueOf(typeName.toUpperCase()); } catch (IllegalArgumentException ex) { particleType = Particle.FLAME; }

        final double radius = config.getDouble("spawnParticles.radius", 2.5);
        final int points = Math.max(8, config.getInt("spawnParticles.points", 60));
        final long interval = Math.max(1L, config.getLong("spawnParticles.intervalTicks", 5));
        final double x = config.getDouble("lobby.spawn.x", 0.5);
        final double y = config.getDouble("lobby.spawn.y", 80.0);
        final double z = config.getDouble("lobby.spawn.z", 0.5);

        final Particle type = particleType;
        particleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (int i = 0; i < points; i++) {
                double angle = (2 * Math.PI) * (i / (double) points);
                double px = x + Math.cos(angle) * radius;
                double pz = z + Math.sin(angle) * radius;
                world.spawnParticle(type, px, y + 0.1, pz, 1, 0, 0, 0, 0);
            }
        }, 40L, interval);
    }

    public void startWeatherControl() {
        cancelTask(weatherTask);
        if (!config.getBoolean("weatherControl.enabled", true)) return;
        final long interval = Math.max(1L, config.getLong("weatherControl.intervalTicks", 200L));
        final String configuredWorld = config.getString("weatherControl.world", "");
        Runnable job = () -> {
            String worldName = (configuredWorld == null || configuredWorld.isBlank()) ? config.getString("lobby.world", "world") : configuredWorld;
            World world = Bukkit.getWorld(worldName);
            if (world == null) return;
            if (config.getBoolean("weatherControl.lock-sun", true)) {
                if (world.hasStorm()) world.setStorm(false);
                if (world.isThundering()) world.setThundering(false);
                world.setWeatherDuration(0);
                world.setThunderDuration(0);
            }
        };
        weatherTask = Bukkit.getScheduler().runTaskTimer(this, job, 20L, interval);
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }
}