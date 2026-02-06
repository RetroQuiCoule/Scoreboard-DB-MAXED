package me.mklv.scoreboarddb;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScoreboardDBCommand implements TabExecutor {
    private final DatabaseManager dbManager;

    public ScoreboardDBCommand(ScoreboardDBPlugin plugin, DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /scoreboarddb <save|get|sync-now> ...");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "save":
                // Usage: /scoreboarddb save <scoreboard> <target> [value]
                // [value] est maintenant optionnel.
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /scoreboarddb save <scoreboard> <target> [value]");
                    return true;
                }

                String scoreboardName = args[1];
                String targetArg = args[2];

                // Vérification : A-t-on fourni une valeur manuelle ?
                boolean hasManualValue = (args.length >= 4);
                double manualValue = 0;

                if (hasManualValue) {
                    try {
                        manualValue = Double.parseDouble(args[3]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cValue must be a number.");
                        return true;
                    }
                }

                // Récupération du scoreboard Bukkit (nécessaire si on doit lire les scores actuels)
                ScoreboardManager manager = Bukkit.getScoreboardManager();
                Scoreboard mainScoreboard = (manager != null) ? manager.getMainScoreboard() : null;
                Objective objective = (mainScoreboard != null) ? mainScoreboard.getObjective(scoreboardName) : null;

                // Si on n'a pas de valeur manuelle, l'objectif DOIT exister pour qu'on puisse lire dedans
                if (!hasManualValue && objective == null) {
                    sender.sendMessage("§cObjective '" + scoreboardName + "' does not exist on this server. Cannot fetch current values.");
                    return true;
                }

                // 1. Résolution des cibles (Joueurs ou Sélecteurs)
                Set<String> targets = new HashSet<>();
                try {
                    List<Entity> entities = Bukkit.selectEntities(sender, targetArg);
                    for (Entity e : entities) {
                        if (e instanceof Player) {
                            targets.add(e.getName());
                        }
                    }
                } catch (IllegalArgumentException | NoSuchMethodError e) {
                    // Ignorer les erreurs de sélecteur
                }

                if (targets.isEmpty()) {
                    targets.add(targetArg);
                }

                String serverName = ScoreboardDBPlugin.getInstance().getServerName();

                // 2. Envoi en base de données
                try (Connection conn = dbManager.getDataSource().getConnection()) {
                    String sql = "INSERT INTO scoreboard_data (server_name, scoreboard_name, string, value, push) " +
                            "VALUES (?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "value = VALUES(value), " +
                            "push = VALUES(push)";

                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int count = 0;
                        for (String target : targets) {
                            double valueToSave;

                            if (hasManualValue) {
                                // Cas A : L'utilisateur a donné un chiffre (ex: 10) -> on l'utilise
                                valueToSave = manualValue;
                            } else {
                                // Cas B : L'utilisateur n'a rien mis -> on cherche le score actuel du joueur
                                Score score = objective.getScore(target);
                                if (score.isScoreSet()) {
                                    valueToSave = score.getScore();
                                } else {
                                    // Le joueur n'a pas de score sur cet objectif, on ignore ou on met 0 ?
                                    // Ici, on choisit d'ignorer pour ne pas corrompre la DB avec des faux 0
                                    continue;
                                }
                            }

                            ps.setString(1, serverName);
                            ps.setString(2, scoreboardName);
                            ps.setString(3, target);
                            ps.setDouble(4, valueToSave);
                            ps.setBoolean(5, false);
                            ps.addBatch();
                            count++;
                        }

                        if (count > 0) {
                            ps.executeBatch();
                            sender.sendMessage("§aSaved " + scoreboardName + " for " + count + " player(s).");
                        } else {
                            sender.sendMessage("§cNo scores found to save (targets might not have a score set).");
                        }
                    }
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("doesn't exist")) {
                        dbManager.ensureTableExists();
                        sender.sendMessage("§eTable was missing and has been created. Please try again.");
                    } else {
                        sender.sendMessage("§cFailed to save value: " + e.getMessage());
                    }
                }
                break;

            case "get":
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /scoreboarddb get <scoreboard> <string>");
                    return true;
                }
                scoreboardName = args[1];
                String key = args[2];
                serverName = ScoreboardDBPlugin.getInstance().getServerName();
                try (Connection conn = dbManager.getDataSource().getConnection()) {
                    String sql = "SELECT value FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? AND string = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, serverName);
                        ps.setString(2, scoreboardName);
                        ps.setString(3, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                double val = rs.getDouble("value");
                                sender.sendMessage("§aValue for " + key + ": " + val);
                            } else {
                                sender.sendMessage("§cNo value found for " + key + ".");
                            }
                        }
                    }
                } catch (Exception e) {
                    sender.sendMessage("§cFailed to get value: " + e.getMessage());
                }
                break;

            case "sync-now":
                ScoreboardDBPlugin.getInstance().syncDatabase();
                sender.sendMessage("§aSync triggered.");
                break;

            default:
                sender.sendMessage("§cUnknown subcommand");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            Collections.addAll(subs, "save", "get", "sync-now");
            return filterSuggestions(subs, args[0]);
        }
        // Tab complete for save command
        if (args[0].equalsIgnoreCase("save")) {
            if (args.length == 2) {
                return filterSuggestions(getBukkitScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("@a");
                suggestions.add("@p");
                suggestions.addAll(getBukkitEntryNames(args[1]));
                return filterSuggestions(suggestions, args[2]);
            }
            // Pas de suggestion pour l'argument 4 car c'est un nombre optionnel
        }
        // Tab complete for get command
        if (args[0].equalsIgnoreCase("get")) {
            if (args.length == 2) {
                return filterSuggestions(getDatabaseScoreboardNames(), args[1]);
            } else if (args.length == 3) {
                return filterSuggestions(getDatabaseEntryNames(args[1]), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterSuggestions(List<String> suggestions, String input) {
        List<String> filtered = new ArrayList<>();
        String lowerInput = input.toLowerCase();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(lowerInput)) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }

    private List<String> getBukkitScoreboardNames() {
        List<String> scoreboards = new ArrayList<>();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return scoreboards;
        Scoreboard scoreboard = manager.getMainScoreboard();
        for (Objective obj : scoreboard.getObjectives()) {
            scoreboards.add(obj.getName());
        }
        return scoreboards;
    }

    private List<String> getBukkitEntryNames(String scoreboardName) {
        List<String> entries = new ArrayList<>();
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return entries;
        Scoreboard scoreboard = manager.getMainScoreboard();
        Objective obj = scoreboard.getObjective(scoreboardName);
        if (obj != null) {
            for (String entry : scoreboard.getEntries()) {
                try {
                    if (obj.getScore(entry).isScoreSet()) {
                        entries.add(entry);
                    }
                } catch (IllegalStateException ignore) {}
            }
        }
        return entries;
    }

    private List<String> getScoreboardNames() {
        List<String> scoreboards = new ArrayList<>();
        String serverName = ScoreboardDBPlugin.getInstance().getServerName();
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = "SELECT DISTINCT scoreboard_name FROM scoreboard_data WHERE server_name = ? ORDER BY scoreboard_name";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        scoreboards.add(rs.getString("scoreboard_name"));
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail
        }
        return scoreboards;
    }

    private List<String> getDatabaseScoreboardNames() {
        return getScoreboardNames();
    }

    private List<String> getDatabaseEntryNames(String scoreboardName) {
        List<String> entries = new ArrayList<>();
        String serverName = ScoreboardDBPlugin.getInstance().getServerName();
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            String sql = "SELECT DISTINCT string FROM scoreboard_data WHERE server_name = ? AND scoreboard_name = ? ORDER BY string";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, serverName);
                ps.setString(2, scoreboardName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(rs.getString("string"));
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail
        }
        return entries;
    }
}