package net.slipcor.pvparena.modules.cyanvanillajoin;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.core.RandomUtils;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.managers.PermissionManager;
import net.slipcor.pvparena.managers.TeamManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import net.slipcor.pvparena.regions.ArenaRegion;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * Faithful re-implementation of the fork's {@code PAG_VanillaJoin} global command logic.
 *
 * <p>Behavior is intentionally identical to the original (see
 * {@code plans/forked-features/03-vanillajoin.md}); the only deltas are documented in
 * {@code plans/cyan-modules/02-vanillajoin-module.md}:</p>
 * <ul>
 *     <li>invoked via {@code /cyanpa vanillajoin} (alias {@code vj}) instead of {@code /pa vanillajoin -vj};</li>
 *     <li>the "event ongoing" gate uses a local string (the original {@code MSG} key was never added
 *         to this fresh upstream fork), with the same default text.</li>
 * </ul>
 */
final class VanillaJoin {

    private static final String CMD_VANILLAJOIN_PERM = "pvparena.cmds.vanillajoin";
    private static final String VANILLA_PREFIX = "vanilla";
    /** Local copy of the fork's {@code CMD_AUTOJOINONE_EVENT_ONGOING} default text. */
    private static final String MSG_EVENT_ONGOING = "A PVP Event is already ongoing. Try typing /pvpjoin later.";

    private VanillaJoin() {
    }

    static void handle(final CommandSender sender, final String[] args) {
        if (!hasPerms(sender)) {
            return;
        }

        // Original command accepted exactly 0 arguments after "vanillajoin".
        if (args.length != 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + ChatColor.RESET + "/cyanpa vanillajoin");
            return;
        }

        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return;
        }

        final Player player = (Player) sender;
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);

        if (aPlayer.getArena() != null) {
            final Arena currentArena = aPlayer.getArena();
            debug(player, "Player already in arena: {}", currentArena.getName());
            currentArena.msg(player, MSG.ERROR_ARENA_ALREADY_PART_OF, currentArena.getName());
            return;
        }

        final boolean hasOngoingVanillaArena = ArenaManager.getArenas().stream()
                .anyMatch(arena -> {
                    if (!arena.getName().toLowerCase().startsWith(VANILLA_PREFIX)) {
                        return false;
                    }
                    return arena.isFightInProgress();
                });

        if (hasOngoingVanillaArena) {
            Arena.pmsg(player, ChatColor.translateAlternateColorCodes('&', MSG_EVENT_ONGOING));
            debug(player, "Vanillajoin cancelled: Found vanilla arena(s) with ongoing fight");
            return;
        }

        final Set<Arena> availableArenas = ArenaManager.getArenas().stream()
                .filter(arena -> {
                    if (arena.isLocked()) {
                        return false;
                    }
                    if (!arena.getName().toLowerCase().startsWith(VANILLA_PREFIX)) {
                        return false;
                    }
                    if (!PermissionManager.hasExplicitArenaPerm(player, arena, "vanillajoin")) {
                        return false;
                    }
                    if (TeamManager.isArenaFull(arena)) {
                        return false;
                    }
                    if (arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH) &&
                            (!arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN)
                                    || !arena.hasAlreadyPlayed(player.getName()))) {
                        return false;
                    }
                    if (!arena.getGoal().allowsJoinInBattle() &&
                            !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN)
                            && arena.hasAlreadyPlayed(player.getName())) {
                        return false;
                    }
                    if (!ArenaManager.checkJoinRegion(player, arena)) {
                        return false;
                    }
                    if (ArenaRegion.tooFarAway(arena, player)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toSet());

        if (availableArenas.isEmpty()) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        Arena selectedArena;

        final Function<Arena, Long> countQueuePlayers = arena -> arena.getEveryone().stream()
                .filter(ap -> ap.getStatus() == PlayerStatus.LOUNGE || ap.getStatus() == PlayerStatus.READY)
                .count();

        final List<Arena> arenasInQueue = availableArenas.stream()
                .filter(arena -> {
                    long queueCount = countQueuePlayers.apply(arena);
                    return !arena.isFightInProgress() && queueCount > 0;
                })
                .sorted((a1, a2) -> {
                    long count1 = countQueuePlayers.apply(a1);
                    long count2 = countQueuePlayers.apply(a2);
                    int countCompare = Long.compare(count2, count1);
                    if (countCompare != 0) {
                        return countCompare;
                    }
                    return a1.getName().compareToIgnoreCase(a2.getName());
                })
                .collect(Collectors.toList());

        if (!arenasInQueue.isEmpty()) {
            selectedArena = arenasInQueue.get(0);
            long queueCount = countQueuePlayers.apply(selectedArena);
            debug(player, "Found {} vanilla arena(s) with players in queue, selecting '{}' with {} player(s) waiting",
                    arenasInQueue.size(), selectedArena.getName(), queueCount);
        } else {
            final List<Arena> emptyArenas = availableArenas.stream()
                    .filter(arena -> arena.getEveryone().size() == 0)
                    .collect(Collectors.toList());

            if (!emptyArenas.isEmpty()) {
                selectedArena = RandomUtils.getRandom(emptyArenas, new Random());
                debug(player, "No vanilla arenas have players in queue, randomly selecting from {} empty arena(s)",
                        emptyArenas.size());
            } else {
                selectedArena = RandomUtils.getRandom(availableArenas, new Random());
                debug(player, "No empty vanilla arenas found, randomly selecting from {} available arena(s)",
                        availableArenas.size());
            }
        }

        if (selectedArena == null) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        debug(player, "Vanilla-joining arena: {}", selectedArena.getName());

        WorkflowManager.handleJoin(selectedArena, player, new String[0]);
    }

    /** Mirrors {@code AbstractGlobalCommand.hasPerms}: admin or the command permission node. */
    private static boolean hasPerms(final CommandSender sender) {
        if (PermissionManager.hasAdminPerm(sender) || sender.hasPermission(CMD_VANILLAJOIN_PERM)) {
            return true;
        }
        Arena.pmsg(sender, PermissionManager.getMissingPermissionMessage(CMD_VANILLAJOIN_PERM));
        return false;
    }
}
