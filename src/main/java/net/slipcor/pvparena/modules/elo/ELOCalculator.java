package net.slipcor.pvparena.modules.elo;

import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ELO Calculator utility class
 * Handles all ELO rating calculations
 */
public class ELOCalculator {

    /**
     * Calculate expected score (win probability) based on rating difference
     *
     * @param playerRating   The player's current rating
     * @param opponentRating The opponent's current rating
     * @return Expected score (0.0 to 1.0)
     */
    public static double calculateExpectedScore(double playerRating, double opponentRating) {
        return 1.0 / (1.0 + Math.pow(10, (opponentRating - playerRating) / 400.0));
    }

    /**
     * Calculate ELO rating change
     *
     * @param playerRating   The player's current rating
     * @param opponentRating The opponent's current rating
     * @param actualScore    Actual result: 1.0 for win, 0.5 for draw, 0.0 for loss
     * @param kFactor        K-factor (maximum rating change per match)
     * @return Rating change (positive for win, negative for loss)
     */
    public static double calculateRatingChange(double playerRating, double opponentRating, 
                                               double actualScore, double kFactor) {
        double expectedScore = calculateExpectedScore(playerRating, opponentRating);
        return kFactor * (actualScore - expectedScore);
    }

    /**
     * Calculate average team rating
     *
     * @param team           The arena team
     * @param playerRatings  Map of player UUIDs to their ratings
     * @return Average rating of all team members
     */
    public static double calculateAverageTeamRating(ArenaTeam team, java.util.Map<String, Double> playerRatings) {
        List<Double> ratings = team.getTeamMembers().stream()
                .map(ap -> playerRatings.getOrDefault(ap.getPlayer().getUniqueId().toString(), 1000.0))
                .collect(Collectors.toList());

        if (ratings.isEmpty()) {
            return 1000.0; // Default rating if no players
        }

        return ratings.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(1000.0);
    }

    /**
     * Distribute rating change evenly among team members
     *
     * @param team         The arena team
     * @param totalChange  Total rating change for the team
     * @return Map of player UUIDs to their individual rating changes
     */
    public static java.util.Map<String, Double> distributeTeamRatingChange(ArenaTeam team, double totalChange) {
        int teamSize = team.getTeamMembers().size();
        if (teamSize == 0) {
            return new java.util.HashMap<>();
        }

        double changePerPlayer = totalChange / teamSize;
        java.util.Map<String, Double> changes = new java.util.HashMap<>();

        for (ArenaPlayer player : team.getTeamMembers()) {
            changes.put(player.getPlayer().getUniqueId().toString(), changePerPlayer);
        }

        return changes;
    }
}

