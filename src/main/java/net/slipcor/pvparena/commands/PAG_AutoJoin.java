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
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>PVP Arena AUTOJOIN Command class</pre>
 * <p/>
 * A command to automatically join a random enabled arena
 *
 * @author slipcor
 * @version v0.10.2
 */

public class PAG_AutoJoin extends AbstractGlobalCommand {
    private static final String CMD_AUTOJOIN_PERM = "pvparena.cmds.autojoin";
    private static final String AUTOJOIN = "autojoin";
    private static final String AUTOJOIN_SHORT = "-aj";

    public PAG_AutoJoin() {
        super(new String[]{CMD_AUTOJOIN_PERM});
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

        // Get all enabled arenas that the player can join
        final Set<Arena> availableArenas = ArenaManager.getArenas().stream()
                .filter(arena -> {
                    // Filter out locked (disabled) arenas
                    if (arena.isLocked()) {
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

        if (availableArenas.isEmpty()) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        // Priority 1: Arenas with players (highest player count first)
        // Priority 2: If no arenas have players, randomly select
        Arena selectedArena = null;

        // First, try to find arenas that already have players
        Set<Arena> arenasWithPlayers = availableArenas.stream()
                .filter(arena -> arena.getEveryone().size() > 0)
                .collect(Collectors.toSet());

        if (!arenasWithPlayers.isEmpty()) {
            // Select the arena with the highest player count
            selectedArena = arenasWithPlayers.stream()
                    .max(Comparator.comparingInt(arena -> arena.getEveryone().size()))
                    .orElse(null);
            debug(player, "Found {} arena(s) with players, selecting arena with highest player count", arenasWithPlayers.size());
        } else {
            // No arenas have players, randomly select from available arenas
            selectedArena = RandomUtils.getRandom(availableArenas, new Random());
            debug(player, "No arenas have players, randomly selecting from {} available arena(s)", availableArenas.size());
        }
        
        if (selectedArena == null) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        debug(player, "Auto-joining arena: {}", selectedArena.getName());

        // Use the existing join workflow
        WorkflowManager.handleJoin(selectedArena, player, new String[0]);
    }

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    @Override
    public List<String> getMain() {
        return Collections.singletonList(AUTOJOIN);
    }

    @Override
    public List<String> getShort() {
        return Collections.singletonList(AUTOJOIN_SHORT);
    }

    @Override
    public CommandTree<String> getSubs(final Arena nothing) {
        return new CommandTree<>(null);
    }
}

