package net.slipcor.pvparena.modules.elo;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.*;
import java.util.*;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * MySQL Database handler for ELO ratings
 */
public class ELODatabase {
    private Connection connection;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;

    public ELODatabase(File configFile) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        
        // Load database configuration
        this.host = config.getString("database.host", "localhost");
        this.port = config.getInt("database.port", 3306);
        this.database = config.getString("database.database", "pvparena_elo");
        this.username = config.getString("database.username", "root");
        this.password = config.getString("database.password", "");
    }

    /**
     * Ensure database connection is active
     * @return true if connection is valid, false otherwise
     */
    private boolean ensureConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                // Test connection is still valid
                return connection.isValid(2); // 2 second timeout
            }
        } catch (SQLException e) {
            // Connection is invalid, will reconnect below
        }

        return initializeDatabase();
    }

    /**
     * Initialize database connection and create table if needed
     */
    public boolean initializeDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                try {
                    // Test if connection is still valid
                    if (connection.isValid(2)) {
                        return true;
                    }
                } catch (SQLException e) {
                    // Connection is invalid, will reconnect
                }
            }

            String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&autoReconnect=true",
                    host, port, database);
            
            connection = DriverManager.getConnection(url, username, password);
            createTable();
            
            debug("ELO Database connected successfully");
            return true;
        } catch (SQLException e) {
            PVPArena.getInstance().getLogger().severe("Failed to connect to ELO database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Create the ELO ratings table if it doesn't exist
     */
    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS pvparena_elo_ratings (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "arena_uuid VARCHAR(36) NULL, " +
                "rating DOUBLE NOT NULL DEFAULT 1000, " +
                "matches_played INT NOT NULL DEFAULT 0, " +
                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "UNIQUE KEY unique_player_arena (player_uuid, arena_uuid), " +
                "INDEX idx_rating (rating DESC), " +
                "INDEX idx_player (player_uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            debug("ELO ratings table created/verified");
        }
    }

    /**
     * Get player's ELO rating
     *
     * @param playerUUID Player's UUID
     * @param arenaUUID  Arena UUID (null for global)
     * @return Player's rating, or default if not found
     */
    public double getPlayerRating(String playerUUID, String arenaUUID, double defaultRating) {
        if (!ensureConnection()) {
            return defaultRating;
        }

        String sql = "SELECT rating FROM pvparena_elo_ratings WHERE player_uuid = ? AND " +
                (arenaUUID == null ? "arena_uuid IS NULL" : "arena_uuid = ?");

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUUID);
            if (arenaUUID != null) {
                stmt.setString(2, arenaUUID);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("rating");
                }
            }
        } catch (SQLException e) {
            PVPArena.getInstance().getLogger().warning("Error getting ELO rating: " + e.getMessage());
        }

        return defaultRating;
    }

    /**
     * Update player's ELO rating
     *
     * @param playerUUID Player's UUID
     * @param arenaUUID  Arena UUID (null for global)
     * @param newRating  New rating value
     */
    public void updatePlayerRating(String playerUUID, String arenaUUID, double newRating) {
        if (!ensureConnection()) {
            return;
        }

        String sql = "INSERT INTO pvparena_elo_ratings (player_uuid, arena_uuid, rating, matches_played) " +
                "VALUES (?, ?, ?, 1) " +
                "ON DUPLICATE KEY UPDATE rating = ?, matches_played = matches_played + 1";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUUID);
            if (arenaUUID == null) {
                stmt.setNull(2, Types.VARCHAR);
            } else {
                stmt.setString(2, arenaUUID);
            }
            stmt.setDouble(3, newRating);
            stmt.setDouble(4, newRating);

            stmt.executeUpdate();
        } catch (SQLException e) {
            PVPArena.getInstance().getLogger().warning("Error updating ELO rating: " + e.getMessage());
        }
    }

    /**
     * Get top ELO ratings (leaderboard)
     *
     * @param limit     Maximum number of results
     * @param arenaUUID Arena UUID (null for global)
     * @return Map of player UUIDs to their ratings, sorted by rating descending
     */
    public Map<String, Double> getTopRatings(int limit, String arenaUUID) {
        Map<String, Double> ratings = new LinkedHashMap<>();

        if (!ensureConnection()) {
            return ratings;
        }

        String sql = "SELECT player_uuid, rating FROM pvparena_elo_ratings WHERE " +
                (arenaUUID == null ? "arena_uuid IS NULL" : "arena_uuid = ?") +
                " ORDER BY rating DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            if (arenaUUID != null) {
                stmt.setString(1, arenaUUID);
                stmt.setInt(2, limit);
            } else {
                stmt.setInt(1, limit);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ratings.put(rs.getString("player_uuid"), rs.getDouble("rating"));
                }
            }
        } catch (SQLException e) {
            PVPArena.getInstance().getLogger().warning("Error getting top ratings: " + e.getMessage());
        }

        return ratings;
    }

    /**
     * Close database connection
     */
    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                debug("ELO Database connection closed");
            }
        } catch (SQLException e) {
            PVPArena.getInstance().getLogger().warning("Error closing database connection: " + e.getMessage());
        }
    }
}

