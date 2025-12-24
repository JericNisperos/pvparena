package net.slipcor.pvparena.commands;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.managers.PermissionManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>PVP Arena DUELJOIN Command class</pre>
 * <p/>
 * A command to join a global duel queue that matches players for 1v1 duels
 * - Joins/leaves queue on toggle
 * - Matches 2 players automatically
 * - Assigns to available duel arenas (duel1, duel2, etc.)
 * - 6-minute timeout for queue
 *
 * @author slipcor
 * @version v0.10.2
 */

public class PAG_DuelJoin extends AbstractGlobalCommand {
    private static final String CMD_DUELJOIN_PERM = "pvparena.cmds.dueljoin";
    private static final String DUELJOIN = "dueljoin";
    private static final String DUELJOIN_SHORT = "-dj";
    private static final String DUEL_PREFIX = "duel";
    private static final long QUEUE_TIMEOUT_SECONDS = 360; // 6 minutes
    
    // Queue: Map<UUID, QueueEntry> where QueueEntry contains timestamp and task
    private static final Map<UUID, QueueEntry> duelQueue = new HashMap<>();
    
    private static class QueueEntry {
        final UUID playerUUID;
        final String playerName;
        final BukkitTask timeoutTask;
        
        QueueEntry(UUID playerUUID, String playerName, BukkitTask timeoutTask) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.timeoutTask = timeoutTask;
        }
    }

    public PAG_DuelJoin() {
        super(new String[]{CMD_DUELJOIN_PERM});
    }

    @Override
    public void commit(final CommandSender sender, final String[] args) {
        if (!this.hasPerms(sender)) {
            return;
        }

        if (!argCountValid(sender, args, new Integer[]{0})) {
            return;
        }

        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return;
        }

        final Player player = (Player) sender;
        final UUID playerUUID = player.getUniqueId();
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);

        // Check if player is already in an arena
        if (aPlayer.getArena() != null) {
            Arena.pmsg(player, MSG.ERROR_ARENA_ALREADY_PART_OF, aPlayer.getArena().getName());
            return;
        }

        // Toggle: If already in queue, remove from queue
        if (duelQueue.containsKey(playerUUID)) {
            removeFromQueue(playerUUID, false);
            Arena.pmsg(player, MSG.CMD_DUELJOIN_LEFT);
            return;
        }

        // Add to queue
        addToQueue(player);
    }

    /**
     * Add player to duel queue
     */
    private void addToQueue(Player player) {
        final UUID playerUUID = player.getUniqueId();
        
        // Create timeout task
        BukkitTask timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (duelQueue.containsKey(playerUUID)) {
                    removeFromQueue(playerUUID, true);
                    Player p = Bukkit.getPlayer(playerUUID);
                    if (p != null && p.isOnline()) {
                        Arena.pmsg(p, MSG.CMD_DUELJOIN_TIMEOUT);
                    }
                }
            }
        }.runTaskLater(net.slipcor.pvparena.PVPArena.getInstance(), QUEUE_TIMEOUT_SECONDS * 20L);
        
        QueueEntry entry = new QueueEntry(playerUUID, player.getName(), timeoutTask);
        duelQueue.put(playerUUID, entry);
        
        Arena.pmsg(player, MSG.CMD_DUELJOIN_JOINED);
        debug(player, "Player {} joined duel queue", player.getName());
        
        // Notify other queued players
        notifyOtherQueuedPlayers(player);
        
        // Check if we can match now (2+ players in queue)
        if (duelQueue.size() >= 2) {
            matchPlayers();
        }
    }

    /**
     * Remove player from duel queue
     */
    private static void removeFromQueue(UUID playerUUID, boolean timeout) {
        QueueEntry entry = duelQueue.remove(playerUUID);
        if (entry != null) {
            if (!timeout && entry.timeoutTask != null) {
                entry.timeoutTask.cancel();
            }
            debug("Removed player {} from duel queue (timeout: {})", entry.playerName, timeout);
        }
    }

    /**
     * Notify other queued players that someone joined
     */
    private void notifyOtherQueuedPlayers(Player newPlayer) {
        int queueSize = duelQueue.size();
        for (QueueEntry entry : duelQueue.values()) {
            if (!entry.playerUUID.equals(newPlayer.getUniqueId())) {
                Player p = Bukkit.getPlayer(entry.playerUUID);
                if (p != null && p.isOnline()) {
                    Arena.pmsg(p, MSG.CMD_DUELJOIN_PLAYER_JOINED, newPlayer.getName(), String.valueOf(queueSize));
                }
            }
        }
    }

    /**
     * Match players and assign to duel arena
     */
    private void matchPlayers() {
        if (duelQueue.size() < 2) {
            return;
        }

        // Get first 2 players from queue
        List<QueueEntry> playersToMatch = duelQueue.values().stream()
                .limit(2)
                .collect(Collectors.toList());

        if (playersToMatch.size() < 2) {
            return;
        }

        QueueEntry player1Entry = playersToMatch.get(0);
        QueueEntry player2Entry = playersToMatch.get(1);

        Player player1 = Bukkit.getPlayer(player1Entry.playerUUID);
        Player player2 = Bukkit.getPlayer(player2Entry.playerUUID);

        // Validate both players are still online
        if (player1 == null || !player1.isOnline() || player2 == null || !player2.isOnline()) {
            // Remove offline players
            if (player1 == null || !player1.isOnline()) {
                removeFromQueue(player1Entry.playerUUID, false);
            }
            if (player2 == null || !player2.isOnline()) {
                removeFromQueue(player2Entry.playerUUID, false);
            }
            return;
        }

        // Find available duel arena
        Arena selectedArena = findAvailableDuelArena(player1, player2);
        
        if (selectedArena == null) {
            Arena.pmsg(player1, MSG.CMD_DUELJOIN_NO_ARENAS);
            Arena.pmsg(player2, MSG.CMD_DUELJOIN_NO_ARENAS);
            return;
        }

        // Remove both players from queue
        removeFromQueue(player1Entry.playerUUID, false);
        removeFromQueue(player2Entry.playerUUID, false);

        // Assign players to different teams and join arena
        assignPlayersToArena(selectedArena, player1, player2);
    }

    /**
     * Find an available duel arena
     */
    private Arena findAvailableDuelArena(Player player1, Player player2) {
        // Get all duel arenas (starting with "duel")
        List<Arena> duelArenas = ArenaManager.getArenas().stream()
                .filter(arena -> arena.getName().toLowerCase().startsWith(DUEL_PREFIX))
                .filter(arena -> {
                    // Filter out locked (disabled) arenas
                    if (arena.isLocked()) {
                        return false;
                    }
                    // Check permissions for both players
                    if (!PermissionManager.hasExplicitArenaPerm(player1, arena, "join") ||
                        !PermissionManager.hasExplicitArenaPerm(player2, arena, "join")) {
                        return false;
                    }
                    // Check if arena is full (max 2 players for duels)
                    if (arena.getEveryone().size() >= 2) {
                        return false;
                    }
                    // Check if fight is in progress and join is not allowed
                    if (arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH)) {
                        return false;
                    }
                    return true;
                })
                .sorted(Comparator.comparingInt(arena -> arena.getEveryone().size())) // Prefer empty arenas
                .collect(Collectors.toList());

        if (duelArenas.isEmpty()) {
            return null;
        }

        // Prefer empty arenas, then arenas with 1 player
        return duelArenas.get(0);
    }

    /**
     * Assign players to arena with different teams
     */
    private void assignPlayersToArena(Arena arena, Player player1, Player player2) {
        // Get available teams
        List<ArenaTeam> teams = arena.getTeams().stream()
                .filter(team -> team.getTeamMembers().size() < 1) // Teams should have max 1 player for 1v1
                .collect(Collectors.toList());

        Arena.pmsg(player1, MSG.CMD_DUELJOIN_MATCH_FOUND, arena.getName());
        Arena.pmsg(player2, MSG.CMD_DUELJOIN_MATCH_FOUND, arena.getName());

        if (teams.size() >= 2) {
            // Assign to different teams
            ArenaTeam team1 = teams.get(0);
            ArenaTeam team2 = teams.get(1);
            
            // Join with specific teams
            WorkflowManager.handleJoin(arena, player1, new String[]{team1.getName()});
            WorkflowManager.handleJoin(arena, player2, new String[]{team2.getName()});
        } else {
            // Not enough teams, use random team assignment (WorkflowManager will handle it)
            WorkflowManager.handleJoin(arena, player1, new String[0]);
            WorkflowManager.handleJoin(arena, player2, new String[0]);
        }
    }

    /**
     * Clear queue when player joins another arena (called from event listener)
     */
    public static void clearQueueOnArenaJoin(UUID playerUUID) {
        if (duelQueue.containsKey(playerUUID)) {
            removeFromQueue(playerUUID, false);
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                Arena.pmsg(player, MSG.CMD_DUELJOIN_LEFT_ARENA_JOIN);
            }
        }
    }

    /**
     * Clear queue when player disconnects (called from event listener)
     */
    public static void clearQueueOnDisconnect(UUID playerUUID) {
        removeFromQueue(playerUUID, false);
    }

    /**
     * Get queue size (for debugging/admin)
     */
    public static int getQueueSize() {
        return duelQueue.size();
    }

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    @Override
    public List<String> getMain() {
        return Collections.singletonList(DUELJOIN);
    }

    @Override
    public List<String> getShort() {
        return Collections.singletonList(DUELJOIN_SHORT);
    }

    @Override
    public CommandTree<String> getSubs(final Arena nothing) {
        return new CommandTree<>(null);
    }
}

