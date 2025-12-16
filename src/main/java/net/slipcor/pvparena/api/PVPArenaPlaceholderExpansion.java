package net.slipcor.pvparena.api;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.config.Debugger;
import net.slipcor.pvparena.core.Config;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.modules.ELORating;
import net.slipcor.pvparena.modules.elo.ELODatabase;
import net.slipcor.pvparena.statistics.dao.PlayerArenaStatsDao;
import net.slipcor.pvparena.statistics.dao.PlayerArenaStatsDaoImpl;
import net.slipcor.pvparena.statistics.model.PlayerArenaStats;
import net.slipcor.pvparena.statistics.model.StatEntry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class PVPArenaPlaceholderExpansion extends PlaceholderExpansion {

    private static final long MULTILINE_LIMIT = 10L;

    private final PlaceholderMultilineCache cache = new PlaceholderMultilineCache();

    public PVPArenaPlaceholderExpansion() {}

    /**
     * Name of the Expansion author
     */
    @Override
    public @NotNull String getAuthor() {
        return "Eredrim";
    }

    /**
     * The placeholder identifier should go here.
     * <br>This is what tells PlaceholderAPI to call our onRequest
     * method to obtain a value if a placeholder starts with our
     * identifier.
     * <br>The identifier has to be lowercase and can't contain _ or %
     *
     * @return The identifier in {@code %<identifier>_<value>%} as String.
     */
    @Override
    public @NotNull String getIdentifier() {
        return "pvpa";
    }

    /**
     * Version of the expansion
     */
    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    /**
     * This is required or else PlaceholderAPI will unregister the Expansion on reload
     * @return true to persist through reloads
     */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String identifier) {
        identifier = PlaceholderAPI.setBracketPlaceholders(player, identifier);
        String[] params = identifier.split("_");
        
        // Handle ELO placeholders (player-based, not arena-based)
        if (params.length > 0 && "elo".equalsIgnoreCase(params[0])) {
            return this.getELOPlaceholder(identifier, params, player);
        }
        
        // Handle arena-based placeholders
        Arena arena;
        if("cur".equalsIgnoreCase(params[0])) {
            arena = ArenaPlayer.fromPlayer(player.getPlayer()).getArena();
        } else {
            arena = ArenaManager.getArenaByExactName(params[0]);
        }

        if (arena != null && params.length > 1) {
            PlaceholderArgs phArgs = new PlaceholderArgs(arena, identifier);
            return this.getArenaPlaceholder(phArgs, player);
        }
        return null;
    }

    private String getArenaPlaceholder(PlaceholderArgs phArgs, OfflinePlayer player) {
        switch (phArgs.getAction()) {
            case "capacity":
                return this.getCapacityPlaceholder(phArgs);
            case "topscore":
                return this.getScorePlaceholder(phArgs);
            case "stats":
                return this.getArenaStatsPlaceholder(phArgs);
            case "pcolor":
                return this.colorPlayer(phArgs);
            case "tcolor":
                return this.colorTeam(phArgs);
            case "team":
                return ArenaPlayer.fromPlayer(player.getPlayer()).getArenaTeam().getName();
            case "elo":
                // Arena-specific ELO: %pvpa_<arena>_elo_<player>%
                return this.getArenaELOPlaceholder(phArgs, player);
            default:
                return null;
        }
    }

    private String colorPlayer(PlaceholderArgs phArgs) {
        ArenaTeam team =  phArgs.getArena().getEveryone().stream()
                .filter(ap -> ap.getName().equalsIgnoreCase(phArgs.getArg(2)))
                .findAny()
                .map(ArenaPlayer::getArenaTeam)
                .orElse(null);

        if(team != null) {
            return team.getColor() + phArgs.getArg(2) + ChatColor.RESET;
        }
        return null;
    }

    private String colorTeam(PlaceholderArgs phArgs) {
        ArenaTeam team = phArgs.getArena().getTeam(phArgs.getArg(2));
        if(team != null) {
            return team.getColor() + phArgs.getArg(2) + ChatColor.RESET;
        }
        return null;
    }

    private String getCapacityPlaceholder(PlaceholderArgs phArgs) {
        Arena arena = phArgs.getArena();
        if(phArgs.getArgsLength() <= 2) {
            int nbPlayers = arena.getFighters().size();
            int maxPlayers = arena.getConfig().getInt(Config.CFG.READY_MAXPLAYERS);
            return this.getFormattedCapacity(nbPlayers, maxPlayers);

        } else if(phArgs.argEquals(2, "team")) {
            ArenaTeam team = arena.getTeam(phArgs.getArg(3));
            if(team != null) {
                int nbPlayers = team.getTeamMembers().size();
                int maxPlayers = arena.getConfig().getInt(Config.CFG.READY_MAXTEAMPLAYERS);
                return this.getFormattedCapacity(nbPlayers, maxPlayers);
            }
        }
        return null;
    }

    private String getFormattedCapacity(int nbPlayers, int maxPlayers) {
        if(maxPlayers > 0) {
            return String.format("%d / %d", nbPlayers, maxPlayers);
        }
        return String.valueOf(nbPlayers);
    }

    private String getArenaStatsPlaceholder(PlaceholderArgs phArgs) {
        PlayerArenaStatsDao statsDao = PlayerArenaStatsDaoImpl.getInstance();
        try {
            int rowIndex = Integer.parseInt(phArgs.getArg(4));
            if(rowIndex >= 0 && rowIndex < MULTILINE_LIMIT) {
                StatEntry statEntry = StatEntry.parse(phArgs.getArg(2));
                Supplier<List<PlayerArenaStats>> statsSupplier = () -> statsDao.findBestStatByArena(statEntry, phArgs.getArena(), MULTILINE_LIMIT);
                return this.cache.getPlayerStat(phArgs, statEntry, statsSupplier).get(rowIndex);
            }
        } catch (NullPointerException | NumberFormatException | IndexOutOfBoundsException e) {
            Debugger.trace("Exception caught while parsing stat placeholder '{}': {}", phArgs.getIdentifier(), e);
        }
        return null;
    }

    private String getScorePlaceholder(PlaceholderArgs phArgs) {
        try {
            int rowIndex = Integer.parseInt(phArgs.getArg(3));
            if(phArgs.getArena().getGoal().isFreeForAll()) {
                return this.cache.getFreeForAllScore(phArgs).get(rowIndex);
            } else {
                return this.cache.getTeamsScore(phArgs).get(rowIndex);
            }
        } catch (NullPointerException | NumberFormatException | IndexOutOfBoundsException e) {
            Debugger.trace("Exception caught while parsing stat placeholder '{}': {}", phArgs.getIdentifier(), e);
        }
        return null;
    }

    /**
     * Handle ELO placeholders that don't require arena
     * Formats:
     * - %pvpa_elo_<player>% - Get ELO rating for a player (global or current arena if per_arena)
     * - %pvpa_elo_<player>_rating% - Explicitly get rating
     * - %pvpa_elo_<player>_rank% - Get rank of player
     * - %pvpa_elo_top_<rank>% - Get top player at rank (0-based)
     * - %pvpa_elo_top_<rank>_rating% - Get rating of top player at rank
     */
    private String getELOPlaceholder(String identifier, String[] params, OfflinePlayer contextPlayer) {
        if (params.length < 2) {
            return null;
        }

        // Get ELO database from any enabled arena module
        ELODatabase eloDatabase = getELODatabase();
        if (eloDatabase == null) {
            return null; // ELO module not enabled
        }

        String action = params[1].toLowerCase();

        // %pvpa_elo_top_<rank>% or %pvpa_elo_top_<rank>_rating%
        if ("top".equals(action) && params.length >= 3) {
            try {
                int rank = Integer.parseInt(params[2]);
                Map<String, Double> topRatings = eloDatabase.getTopRatings(rank + 1, null); // Global ELO
                
                if (topRatings.isEmpty() || rank >= topRatings.size()) {
                    return "N/A";
                }

                List<Map.Entry<String, Double>> sorted = topRatings.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .collect(java.util.stream.Collectors.toList());

                Map.Entry<String, Double> entry = sorted.get(rank);
                
                // %pvpa_elo_top_<rank>_rating%
                if (params.length >= 4 && "rating".equalsIgnoreCase(params[3])) {
                    return String.format("%.0f", entry.getValue());
                }
                
                // %pvpa_elo_top_<rank>% - return player name
                UUID topPlayerUUID = UUID.fromString(entry.getKey());
                OfflinePlayer topPlayer = Bukkit.getOfflinePlayer(topPlayerUUID);
                return topPlayer.getName() != null ? topPlayer.getName() : "Unknown";
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return null;
            }
        }

        // %pvpa_elo_<player>% or %pvpa_elo_<player>_rating% or %pvpa_elo_<player>_rank%
        String targetPlayerName = params[1];
        if (params.length >= 3 && !"rating".equalsIgnoreCase(params[2]) && !"rank".equalsIgnoreCase(params[2])) {
            // If third param is not "rating" or "rank", it might be part of the player name
            targetPlayerName = String.join("_", java.util.Arrays.copyOfRange(params, 1, params.length - 1));
        }

        // Try to get player by name first (online), then by UUID lookup
        OfflinePlayer targetPlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (targetPlayer == null) {
            // Try offline player lookup (deprecated but needed for offline players)
            targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        }
        if (targetPlayer == null || targetPlayer.getUniqueId() == null) {
            return "N/A";
        }

        String playerUUID = targetPlayer.getUniqueId().toString();
        double rating = eloDatabase.getPlayerRating(playerUUID, null, 1000.0); // Global ELO

        // Determine if per-arena ELO should be used
        Arena currentArena = null;
        if (contextPlayer != null && contextPlayer.getPlayer() != null) {
            ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(contextPlayer.getPlayer());
            currentArena = aPlayer.getArena();
        }

        // Check if any arena has per-arena ELO enabled
        String arenaUUID = null;
        if (currentArena != null) {
            ELORating eloModule = currentArena.getMods().stream()
                    .filter(mod -> mod instanceof ELORating)
                    .map(mod -> (ELORating) mod)
                    .findFirst()
                    .orElse(null);
            if (eloModule != null && eloModule.isPerArena()) {
                arenaUUID = currentArena.getName();
                rating = eloDatabase.getPlayerRating(playerUUID, arenaUUID, 1000.0);
            }
        }

        // %pvpa_elo_<player>_rank%
        if (params.length >= 3 && "rank".equalsIgnoreCase(params[params.length - 1])) {
            Map<String, Double> topRatings = eloDatabase.getTopRatings(1000, arenaUUID); // Get large list
            List<Map.Entry<String, Double>> sorted = topRatings.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .collect(java.util.stream.Collectors.toList());
            
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).getKey().equals(playerUUID)) {
                    return String.valueOf(i + 1); // 1-based rank
                }
            }
            return "N/A";
        }

        // %pvpa_elo_<player>_rating% or %pvpa_elo_<player>%
        return String.format("%.0f", rating);
    }

    /**
     * Handle arena-specific ELO placeholders
     * Format: %pvpa_<arena>_elo_<player>%
     */
    private String getArenaELOPlaceholder(PlaceholderArgs phArgs, OfflinePlayer contextPlayer) {
        ELODatabase eloDatabase = getELODatabase();
        if (eloDatabase == null) {
            return null;
        }

        String arenaUUID = phArgs.getArena().getName();

        // Get player name from params (should be after "elo")
        if (phArgs.getArgsLength() < 3) {
            return null;
        }

        String playerName = phArgs.getArg(2);
        if (playerName == null || playerName.isEmpty()) {
            // Use context player if no player specified
            if (contextPlayer != null) {
                playerName = contextPlayer.getName();
            } else {
                return null;
            }
        }

        // Try to get player by name first (online), then by UUID lookup
        OfflinePlayer targetPlayer = Bukkit.getPlayerExact(playerName);
        if (targetPlayer == null) {
            // Try offline player lookup (deprecated but needed for offline players)
            targetPlayer = Bukkit.getOfflinePlayer(playerName);
        }
        if (targetPlayer == null || targetPlayer.getUniqueId() == null) {
            return "N/A";
        }

        String playerUUID = targetPlayer.getUniqueId().toString();
        double rating = eloDatabase.getPlayerRating(playerUUID, arenaUUID, 1000.0);
        return String.format("%.0f", rating);
    }

    /**
     * Get ELO database from any enabled arena module
     */
    private ELODatabase getELODatabase() {
        // Try to find an arena with ELO module enabled
        for (Arena arena : ArenaManager.getArenas()) {
            ELORating eloModule = arena.getMods().stream()
                    .filter(mod -> mod instanceof ELORating)
                    .map(mod -> (ELORating) mod)
                    .findFirst()
                    .orElse(null);
            if (eloModule != null && eloModule.isEnabled()) {
                return eloModule.getDatabase();
            }
        }
        return null;
    }
}
