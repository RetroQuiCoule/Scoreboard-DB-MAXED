package me.mklv.scoreboarddb;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ScoreboardDBExpansion extends PlaceholderExpansion {

    private final ScoreboardDBPlugin plugin;

    public ScoreboardDBExpansion(ScoreboardDBPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "scoreboarddb";
    }

    @Override
    public @NotNull String getAuthor() {
        return "TonNom"; // ou plugin.getDescription().getAuthors().toString()
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Important pour que le placeholder reste actif après un reload
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        // Syntaxe attendue : %scoreboarddb_value_<scoreboardName>%
        if (params.startsWith("value_")) {
            String scoreboardName = params.substring(6); // Enlève "value_" pour garder le nom
            return getScoreFromDB(player.getName(), scoreboardName);
        }

        return null; // Placeholder inconnu
    }

    private String getScoreFromDB(String playerName, String scoreboardName) {
        String serverName = plugin.getServerName();
        // On utilise la méthode "SELECT" standard qui marche partout (MySQL, MariaDB, SQLite)
        String sql = "SELECT value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? AND string = ?";

        try (Connection conn = plugin.getDatabaseManager().getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, serverName);
            ps.setString(2, scoreboardName);
            ps.setString(3, playerName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // On formate en int si c'est un nombre rond (ex: 10.0 devient 10)
                    double val = rs.getDouble("value");
                    if (val == (int) val) {
                        return String.valueOf((int) val);
                    } else {
                        return String.valueOf(val);
                    }
                }
            }
        } catch (Exception e) {
            // En cas d'erreur, on retourne "0" ou "N/A" pour ne pas spammer la console
            // plugin.getLogger().warning("Error fetching placeholder: " + e.getMessage());
        }

        // Si aucune valeur n'est trouvée dans la DB, on retourne 0 par défaut
        return "0";
    }
}
