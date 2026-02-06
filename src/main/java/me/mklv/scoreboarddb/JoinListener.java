package me.mklv.scoreboarddb;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {

    private final ScoreboardDBPlugin plugin;

    public JoinListener(ScoreboardDBPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // On lance une synchronisation asynchrone (en arrière-plan)
        // dès que le joueur pose le pied sur le serveur.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.syncDatabase();
        });
    }
}
