package net.slipcor.pvparena.modules;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.modules.elo.ELOCalculator;
import net.slipcor.pvparena.modules.elo.ELODatabase;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * ELO Rating Module
 * Tracks and calculates ELO ratings for players based on match outcomes
 */
public class ELORating extends ArenaModule {

    private ELODatabase database;
    private File configFile;
    private FileConfiguration config;
    
    // Configuration values
    private boolean enabled;
    private double kFactor;
    private double initialRating;
    private boolean perArena;
    private boolean displayOnJoin;
    
    // Messages
    private String ratingChangeMsg;
    private String ratingGainMsg;
    private String ratingLossMsg;

    public ELORating() {
        super("ELORating");
    }

    @Override
    public String version() {
        return PVPArena.getInstance().getDescription().getVersion();
    }

    @Override
    public void initConfig() {
        // Create config directory if it doesn't exist
        File moduleDir = new File(PVPArena.getInstance().getDataFolder(), "modules/elo");
        if (!moduleDir.exists()) {
            moduleDir.mkdirs();
        }

        configFile = new File(moduleDir, "config.yml");
        
        // Load or create config
        if (!configFile.exists()) {
            createDefaultConfig();
        }
        
        config = YamlConfiguration.loadConfiguration(configFile);
        loadConfig();
        
        // Initialize database
        if (enabled) {
            database = new ELODatabase(configFile);
            if (!database.initializeDatabase()) {
                PVPArena.getInstance().getLogger().warning("ELO Rating module disabled due to database connection failure");
                enabled = false;
            }
        }
    }

    private void createDefaultConfig() {
        try {
            configFile.createNewFile();
            config = YamlConfiguration.loadConfiguration(configFile);
            
            // Database settings
            config.set("database.host", "localhost");
            config.set("database.port", 3306);
            config.set("database.database", "pvparena_elo");
            config.set("database.username", "root");
            config.set("database.password", "");
            config.set("database.connection_pool_size", 5);
            
            // ELO settings
            config.set("elo.enabled", true);
            config.set("elo.k_factor", 24);
            config.set("elo.initial_rating", 1000);
            config.set("elo.per_arena", false);
            config.set("elo.display_on_join", true);
            
            // Messages
            config.set("messages.rating_display", "&eYour ELO Rating: &a%rating%");
            config.set("messages.rating_change", "&eELO Change: %change%");
            config.set("messages.rating_gain", "&a+%amount%");
            config.set("messages.rating_loss", "&c-%amount%");
            
            config.save(configFile);
        } catch (IOException e) {
            PVPArena.getInstance().getLogger().severe("Failed to create ELO config file: " + e.getMessage());
        }
    }

    private void loadConfig() {
        enabled = config.getBoolean("elo.enabled", true);
        
        // Load and validate k_factor
        double rawKFactor = config.getDouble("elo.k_factor", 24);
        if (rawKFactor < 0 || rawKFactor > 100) {
            PVPArena.getInstance().getLogger().warning("Invalid k_factor (" + rawKFactor + "), using default 24. Must be between 0 and 100.");
            kFactor = 24;
        } else {
            kFactor = rawKFactor;
        }
        
        // Load and validate initial_rating
        double rawInitialRating = config.getDouble("elo.initial_rating", 1000);
        if (rawInitialRating < 0 || rawInitialRating > 10000) {
            PVPArena.getInstance().getLogger().warning("Invalid initial_rating (" + rawInitialRating + "), using default 1000. Must be between 0 and 10000.");
            initialRating = 1000;
        } else {
            initialRating = rawInitialRating;
        }
        
        perArena = config.getBoolean("elo.per_arena", false);
        displayOnJoin = config.getBoolean("elo.display_on_join", true);
        
        ratingChangeMsg = config.getString("messages.rating_change", "&eELO Change: %change%");
        ratingGainMsg = config.getString("messages.rating_gain", "&a+%amount%");
        ratingLossMsg = config.getString("messages.rating_loss", "&c-%amount%");
    }

    @Override
    public void timedEnd(Set<String> winners) {
        if (!enabled || database == null) {
            return;
        }

        debug(arena, "ELO: Processing timed end with winners: " + winners);
        processMatchEnd(winners);
    }

    @Override
    public boolean commitEnd(ArenaTeam team, ArenaPlayer player) {
        if (!enabled || database == null) {
            return false;
        }

        // Determine winners
        Set<String> winners = new HashSet<>();
        if (arena.isFreeForAll()) {
            if (player != null) {
                winners.add(player.getName());
            }
        } else if (team != null) {
            winners.addAll(team.getTeamMembers().stream()
                    .map(ArenaPlayer::getName)
                    .collect(Collectors.toSet()));
        }

        debug(arena, "ELO: Processing commit end with winners: " + winners);
        processMatchEnd(winners);
        
        return false; // Don't prevent normal end processing
    }

    private void processMatchEnd(Set<String> winners) {
        if (winners.isEmpty()) {
            debug(arena, "ELO: No winners, skipping ELO calculation");
            return;
        }

        String arenaUUID = perArena ? arena.getName() : null;
        
        // Get all players who participated in the match
        Set<ArenaPlayer> allPlayers = arena.getFighters();
        if (allPlayers.isEmpty()) {
            debug(arena, "ELO: No fighters found, skipping ELO calculation");
            return;
        }

        // Load current ratings for all players (skip null players)
        Map<String, Double> playerRatings = new HashMap<>();
        for (ArenaPlayer ap : allPlayers) {
            Player player = ap.getPlayer();
            if (player == null) {
                continue; // Skip disconnected players
            }
            String playerUUID = player.getUniqueId().toString();
            double rating = database.getPlayerRating(playerUUID, arenaUUID, initialRating);
            playerRatings.put(playerUUID, rating);
        }
        
        if (playerRatings.isEmpty()) {
            debug(arena, "ELO: No valid players with ratings, skipping ELO calculation");
            return;
        }

        if (arena.isFreeForAll()) {
            processFFAMatch(allPlayers, winners, playerRatings, arenaUUID);
        } else {
            processTeamMatch(allPlayers, winners, playerRatings, arenaUUID);
        }
    }

    private void processTeamMatch(Set<ArenaPlayer> allPlayers, Set<String> winners, 
                                 Map<String, Double> playerRatings, String arenaUUID) {
        // Group players by team
        Map<ArenaTeam, List<ArenaPlayer>> playersByTeam = allPlayers.stream()
                .filter(ap -> ap.getArenaTeam() != null && ap.getPlayer() != null)
                .collect(Collectors.groupingBy(ArenaPlayer::getArenaTeam));

        if (playersByTeam.isEmpty()) {
            debug(arena, "ELO: No valid teams found, skipping ELO calculation");
            return;
        }

        // Check if it's a draw (all teams are winners)
        boolean isDraw = winners.size() > 0 && playersByTeam.keySet().stream()
                .allMatch(team -> playersByTeam.get(team).stream()
                        .anyMatch(ap -> winners.contains(ap.getName())));

        // Calculate team ratings
        Map<ArenaTeam, Double> teamRatings = new HashMap<>();
        for (ArenaTeam team : playersByTeam.keySet()) {
            double avgRating = ELOCalculator.calculateAverageTeamRating(team, playerRatings);
            teamRatings.put(team, avgRating);
        }

        // Calculate ELO changes for each team against each opponent
        Map<String, Double> ratingChanges = new HashMap<>();
        
        for (ArenaTeam team : playersByTeam.keySet()) {
            boolean isWinner = playersByTeam.get(team).stream()
                    .anyMatch(ap -> winners.contains(ap.getName()));
            
            double teamRating = teamRatings.get(team);
            double totalChange = 0.0;

            // Calculate against each opponent team
            for (ArenaTeam opponent : playersByTeam.keySet()) {
                if (team.equals(opponent)) {
                    continue;
                }

                double opponentRating = teamRatings.get(opponent);
                // Use 0.5 for draws, 1.0 for win, 0.0 for loss
                double actualScore = isDraw ? 0.5 : (isWinner ? 1.0 : 0.0);
                
                double change = ELOCalculator.calculateRatingChange(
                        teamRating, opponentRating, actualScore, kFactor);
                totalChange += change;
            }

            // Distribute change to team members
            Map<String, Double> teamChanges = ELOCalculator.distributeTeamRatingChange(team, totalChange);
            for (Map.Entry<String, Double> entry : teamChanges.entrySet()) {
                ratingChanges.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        // Update ratings and notify players
        updateRatingsAndNotify(allPlayers, playerRatings, ratingChanges, arenaUUID);
    }

    private void processFFAMatch(Set<ArenaPlayer> allPlayers, Set<String> winners,
                                 Map<String, Double> playerRatings, String arenaUUID) {
        // For FFA, calculate ELO against all opponents
        Map<String, Double> ratingChanges = new HashMap<>();

        for (ArenaPlayer ap : allPlayers) {
            Player player = ap.getPlayer();
            if (player == null) {
                continue; // Skip disconnected players
            }
            
            String playerUUID = player.getUniqueId().toString();
            double playerRating = playerRatings.getOrDefault(playerUUID, initialRating);
            boolean isWinner = winners.contains(ap.getName());
            double totalChange = 0.0;

            for (ArenaPlayer opponentAp : allPlayers) {
                if (ap.equals(opponentAp)) {
                    continue;
                }
                
                Player opponentPlayer = opponentAp.getPlayer();
                if (opponentPlayer == null) {
                    continue; // Skip disconnected opponents
                }

                String opponentUUID = opponentPlayer.getUniqueId().toString();
                double opponentRating = playerRatings.getOrDefault(opponentUUID, initialRating);
                double actualScore = isWinner ? 1.0 : 0.0;

                double change = ELOCalculator.calculateRatingChange(
                        playerRating, opponentRating, actualScore, kFactor);
                totalChange += change;
            }

            ratingChanges.put(playerUUID, totalChange);
        }

        updateRatingsAndNotify(allPlayers, playerRatings, ratingChanges, arenaUUID);
    }

    private void updateRatingsAndNotify(Set<ArenaPlayer> allPlayers, Map<String, Double> playerRatings,
                                       Map<String, Double> ratingChanges, String arenaUUID) {
        for (ArenaPlayer ap : allPlayers) {
            Player player = ap.getPlayer();
            
            if (player == null) {
                continue;
            }

            String playerUUID = player.getUniqueId().toString();
            double oldRating = playerRatings.getOrDefault(playerUUID, initialRating);
            double change = ratingChanges.getOrDefault(playerUUID, 0.0);
            double newRating = oldRating + change;
            
            // Apply rating bounds (0 to 10000) to prevent extreme values
            newRating = Math.max(0.0, Math.min(10000.0, newRating));

            // Update in database
            database.updatePlayerRating(playerUUID, arenaUUID, newRating);

            // Notify player
            if (Math.abs(change) > 0.01) { // Only notify if change is significant
                String changeStr = change > 0 
                        ? ratingGainMsg.replace("%amount%", String.format("%.1f", change))
                        : ratingLossMsg.replace("%amount%", String.format("%.1f", Math.abs(change)));
                
                String message = ratingChangeMsg
                        .replace("%change%", changeStr)
                        .replace("%rating%", String.format("%.0f", newRating));
                
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    @Override
    public void announce(String message, String type) {
        if (!enabled || !displayOnJoin || !"JOIN".equals(type) || database == null) {
            return;
        }

        // Try to extract player from message or use alternative method
        // For now, we'll use a different approach - hook into parseJoin if needed
    }

    @Override
    public void reset(boolean force) {
        // Clean up database connection if needed
        if (database != null && force) {
            database.closeConnection();
        }
    }

    /**
     * Get the ELO database instance (for PlaceholderAPI access)
     */
    public ELODatabase getDatabase() {
        return database;
    }

    /**
     * Check if ELO module is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if per-arena ELO is enabled
     */
    public boolean isPerArena() {
        return perArena;
    }
}

