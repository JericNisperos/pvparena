package net.slipcor.pvparena.commands;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.core.RandomUtils;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.managers.PermissionManager;
import net.slipcor.pvparena.managers.TeamManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>PVP Arena AUTOJOINONE Command class</pre>
 * <p/>
 * A command to automatically join an arena with specific logic:
 * - If no arenas started: randomly select from available arenas
 * - If arena has players: join that arena
 * - If arena has ongoing fight: cancel and show message
 * - Excludes the last arena the player was in to avoid repeating
 *
 * @author slipcor
 * @version v0.10.2
 */

public class PAG_AutoJoinOne extends AbstractGlobalCommand {
    private static final String CMD_AUTOJOINONE_PERM = "pvparena.cmds.autojoinone";
    private static final String AUTOJOINONE = "autojoinone";
    private static final String AUTOJOINONE_SHORT = "-ajo";
    
    // Track the last arena each player was in to avoid repeating
    private static final Map<UUID, String> lastArenaMap = new HashMap<>();

    public PAG_AutoJoinOne() {
        super(new String[]{CMD_AUTOJOINONE_PERM});
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
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);

        // Check if player is already in an arena
        if (aPlayer.getArena() != null) {
            final Arena currentArena = aPlayer.getArena();
            debug(player, "Player already in arena: {}", currentArena.getName());
            currentArena.msg(player, MSG.ERROR_ARENA_ALREADY_PART_OF, currentArena.getName());
            return;
        }

        // Get the last arena this player was in (to exclude it)
        final String lastArenaName = lastArenaMap.get(player.getUniqueId());

        // Get all enabled arenas that the player can join
        final Set<Arena> availableArenas = ArenaManager.getArenas().stream()
                .filter(arena -> {
                    // Filter out locked (disabled) arenas
                    if (arena.isLocked()) {
                        return false;
                    }

                    // Exclude the last arena the player was in
                    if (lastArenaName != null && arena.getName().equalsIgnoreCase(lastArenaName)) {
                        debug(player, "Excluding last arena: {}", lastArenaName);
                        return false;
                    }

                    // Check if player has permission to join this arena
                    if (!PermissionManager.hasExplicitArenaPerm(player, arena, "join")) {
                        return false;
                    }

                    // Check if arena is full
                    if (TeamManager.isArenaFull(arena)) {
                        return false;
                    }

                    // Check if fight is in progress and join is not allowed
                    if (arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH) &&
                            (!arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN) || !arena.hasAlreadyPlayed(player.getName()))) {
                        return false;
                    }

                    // Check if player has already played and rejoin is not allowed
                    if (!arena.getGoal().allowsJoinInBattle() &&
                            !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN) && arena.hasAlreadyPlayed(player.getName())) {
                        return false;
                    }

                    // Check join region if set
                    if (!ArenaManager.checkJoinRegion(player, arena)) {
                        return false;
                    }

                    // Check if player is too far away
                    if (net.slipcor.pvparena.regions.ArenaRegion.tooFarAway(arena, player)) {
                        return false;
                    }

                    return true;
                })
                .collect(Collectors.toSet());

        // Check if any arena (not just available) has an ongoing fight (PVPEvent) - if so, cancel autojoin
        boolean hasOngoingEvent = ArenaManager.getArenas().stream()
                .anyMatch(Arena::isFightInProgress);
        
        if (hasOngoingEvent) {
            // Cancel autojoin and show message
            Arena.pmsg(player, MSG.CMD_AUTOJOINONE_EVENT_ONGOING);
            debug(player, "Autojoin cancelled: Found arena(s) with ongoing fight");
            return;
        }

        // If no arenas available after excluding last arena, try again without exclusion
        Set<Arena> finalAvailableArenas = availableArenas;
        if (availableArenas.isEmpty() && lastArenaName != null) {
            debug(player, "No arenas available after excluding last arena, retrying without exclusion");
            // Retry without excluding the last arena
            finalAvailableArenas = ArenaManager.getArenas().stream()
                    .filter(arena -> {
                        if (arena.isLocked()) return false;
                        if (!PermissionManager.hasExplicitArenaPerm(player, arena, "join")) return false;
                        if (TeamManager.isArenaFull(arena)) return false;
                        if (arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH) &&
                                (!arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN) || !arena.hasAlreadyPlayed(player.getName()))) {
                            return false;
                        }
                        if (!arena.getGoal().allowsJoinInBattle() &&
                                !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN) && arena.hasAlreadyPlayed(player.getName())) {
                            return false;
                        }
                        if (!ArenaManager.checkJoinRegion(player, arena)) return false;
                        if (net.slipcor.pvparena.regions.ArenaRegion.tooFarAway(arena, player)) return false;
                        return true;
                    })
                    .collect(Collectors.toSet());
        }

        if (finalAvailableArenas.isEmpty()) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        Arena selectedArena = null;

        // Check for arenas with players first
        Set<Arena> arenasWithPlayers = finalAvailableArenas.stream()
                .filter(arena -> arena.getEveryone().size() > 0)
                .collect(Collectors.toSet());

        if (!arenasWithPlayers.isEmpty()) {
            // Select the first arena with players
            selectedArena = arenasWithPlayers.iterator().next();
            debug(player, "Found {} arena(s) with players, selecting first one", arenasWithPlayers.size());
        } else {
            // No arenas have players, randomly select from available arenas
            selectedArena = RandomUtils.getRandom(finalAvailableArenas, new Random());
            debug(player, "No arenas have players, randomly selecting from {} available arena(s)", finalAvailableArenas.size());
        }
        
        if (selectedArena == null) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        debug(player, "Auto-joining arena: {}", selectedArena.getName());

        // Store this arena as the last arena for this player
        lastArenaMap.put(player.getUniqueId(), selectedArena.getName());
        debug(player, "Stored last arena: {} for player {}", selectedArena.getName(), player.getName());

        // Use the existing join workflow
        WorkflowManager.handleJoin(selectedArena, player, new String[0]);
    }
    
    /**
     * Clear the last arena for a player (called when they leave an arena)
     * This allows them to potentially rejoin the same arena if needed
     * 
     * @param playerUUID the UUID of the player
     */
    public static void clearLastArena(UUID playerUUID) {
        lastArenaMap.remove(playerUUID);
    }

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    @Override
    public List<String> getMain() {
        return Collections.singletonList(AUTOJOINONE);
    }

    @Override
    public List<String> getShort() {
        return Collections.singletonList(AUTOJOINONE_SHORT);
    }

    @Override
    public CommandTree<String> getSubs(final Arena nothing) {
        return new CommandTree<>(null);
    }
}

