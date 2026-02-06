package me.mklv.scoreboarddb;

// --- TOUS LES IMPORTS DOIVENT ÊTRE ICI, AU DÉBUT DU FICHIER ---
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.ScoreboardManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
// -------------------------------------------------------------

public class ScoreboardDBPlugin extends JavaPlugin implements PluginMessageListener {
    private ConfigLoader configLoader;
    private DatabaseManager databaseManager;
    private static ScoreboardDBPlugin instance;
    private BukkitRunnable syncTask;
    private int syncTaskId = -1;

    private final AtomicReference<String> velocityServerName = new AtomicReference<>(null);
    private boolean velocityEnabled = false;

    public static ScoreboardDBPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // 1. Initialisation de la Configuration et des Managers
        saveDefaultConfig();
        this.configLoader = new ConfigLoader(this);
        this.databaseManager = new DatabaseManager(this, configLoader);

        // 2. Initialisation de la Base de Données
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            getLogger().severe("MySQL JDBC Driver not found!");
        }
        databaseManager.init();

        // 3. Enregistrement des commandes
        getCommand("scoreboarddb").setExecutor(new ScoreboardDBCommand(this, databaseManager));

        // 4. Enregistrement de l'extension PlaceholderAPI
        // On ne garde que CE bloc, qui utilise le bon nom de classe (ScoreboardDBExpansion)
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ScoreboardDBExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered successfully!");
        } else {
            getLogger().warning("PlaceholderAPI not found, placeholders will not work.");
        }

        // 5. Configuration de Velocity (Plugin Messaging)
        Map<String, Object> velocity = configLoader.getVelocity();
        this.velocityEnabled = velocity != null && Boolean.TRUE.equals(velocity.getOrDefault("enabled", false));
        if (velocityEnabled) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "velocity:server");
            getServer().getMessenger().registerIncomingPluginChannel(this, "velocity:server", this);
            requestVelocityServerName();
        }

        // 6. Lancement de la tâche de synchronisation
        startSyncTask();

        // Enregistrement des events (Listeners)
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        getLogger().info("Plugin enabled and successfully initialized!");
    }

    private void requestVelocityServerName() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (player != null) {
                player.sendPluginMessage(this, "velocity:server", new byte[0]);
            }
        }, 40L);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("velocity:server")) return;
        String name = new String(message);
        velocityServerName.set(name);
        getLogger().info("Velocity server name received: " + name);
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (syncTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
        }
        getLogger().info("Plugin disabled!");
    }

    public void startSyncTask() {
        int interval = configLoader.getSyncInterval();
        if (syncTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
        }
        if (interval <= 0) {
            getLogger().info("Automatic sync disabled. Use /scoreboarddb sync-now for manual sync.");
            return;
        }

        if (isFoliaAvailable()) {
            try {
                Object globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                java.lang.reflect.Method runAtFixedRate = globalScheduler.getClass()
                        .getMethod("runAtFixedRate",
                                org.bukkit.plugin.Plugin.class,
                                java.util.function.Consumer.class,
                                long.class,
                                long.class);
                runAtFixedRate.invoke(globalScheduler, this, (java.util.function.Consumer<Object>) task -> {
                    syncDatabase();
                }, interval * 20L, interval * 20L);
                getLogger().info("Using Folia globalRegionScheduler for sync task");
            } catch (Exception e) {
                getLogger().severe("Failed to use Folia scheduler: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            syncTask = new BukkitRunnable() {
                @Override
                public void run() {
                    syncDatabase();
                }
            };
            syncTaskId = syncTask.runTaskTimerAsynchronously(this, interval * 20L, interval * 20L).getTaskId();
        }
    }

    private boolean isFoliaAvailable() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public void syncDatabase() {
        if (isFoliaAvailable()) {
            performSync();
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, this::performSync);
        }
    }

    private void performSync() {
        try {
            String mode = configLoader.getSyncMode();
            if (!mode.equals("PUSH")) {
                pullScoreboardFromDB();
            }
            if (!mode.equals("PULL")) {
                pushScoreboardToDB();
            }
        } catch (Exception e) {
            getLogger().severe("Sync failed: " + e.getMessage());
        }
    }

    private void pushScoreboardToDB() {
        String serverName = getServerName();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard scoreboard = manager.getMainScoreboard();

        try (Connection conn = databaseManager.getDataSource().getConnection()) {
            String sql = "INSERT INTO scoreboard_data (server_name, scoreboard_name, string, value, push) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "value = VALUES(value), " +
                    "push = VALUES(push)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Objective obj : scoreboard.getObjectives()) {
                    for (String entry : scoreboard.getEntries()) {

                        Player player = Bukkit.getPlayer(entry);
                        if (player == null || !player.isOnline()) {
                            continue;
                        }

                        Score score = obj.getScore(entry);
                        if (score.isScoreSet()) {
                            ps.setString(1, serverName);
                            ps.setString(2, obj.getName());
                            ps.setString(3, entry);
                            ps.setInt(4, score.getScore());
                            ps.setBoolean(5, false);
                            ps.addBatch();
                        }
                    }
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            getLogger().warning("Failed to push scoreboard entry: " + e.getMessage());
        }
    }

    private void pullScoreboardFromDB() {
        String serverName = getServerName();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard scoreboard = manager.getMainScoreboard();

        try (Connection conn = databaseManager.getDataSource().getConnection()) {
            String sql = "SELECT scoreboard_name, string, value, push FROM scoreboard_data WHERE server_name = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String scoreboardName = rs.getString("scoreboard_name");
                        String entry = rs.getString("string");
                        double value = rs.getDouble("value");

                        Objective obj = scoreboard.getObjective(scoreboardName);

                        if (obj != null) {
                            Score score = obj.getScore(entry);
                            try {
                                if (score.getScore() != (int) value) {
                                    score.setScore((int) value);
                                }
                            } catch (IllegalStateException e) {
                                // Ignore read-only scores
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to pull scoreboard data: " + e.getMessage());
        }
    }

    public String getServerName() {
        if (velocityEnabled && velocityServerName.get() != null) {
            return velocityServerName.get();
        }
        return configLoader.getServerName();
    }

    // Cette méthode permet aux autres classes d'accéder à la base de données
    public DatabaseManager getDatabaseManager() {
        return this.databaseManager;
    }
}