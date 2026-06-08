package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.events.PAEndEvent;
import net.slipcor.pvparena.events.PAJoinEvent;
import net.slipcor.pvparena.events.PALeaveEvent;
import net.slipcor.pvparena.events.PAWinEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bukkit listener that drives GuildWar's result detection, queue hygiene and arena privacy:
 * <ul>
 *     <li><b>{@link PAWinEvent}</b> — normal win: winner's guild wins, the other loses.</li>
 *     <li><b>{@link PALeaveEvent} / {@link PlayerQuitEvent}</b> — a tracked player exiting an
 *         unresolved, in-progress match loses (covers death-leave, rage-quit and disconnect alike);
 *         also dequeues any waiting player.</li>
 *     <li><b>{@link PAEndEvent}</b> — clears the match claim; an unresolved end is a no-contest
 *         (no score, no lockout) and re-runs matchmaking now that an arena freed up.</li>
 *     <li><b>{@link PAJoinEvent}</b> — blocks walk-up joins to {@code guildwar*} arenas (queue-only)
 *         and dequeues a queued player who joins anything else.</li>
 * </ul>
 * Registered once via {@link CyanGuildWar}'s static initializer; reload-safe (drops any prior
 * instance by class name, like {@code m_CyanGladiator}).
 */
public class GuildWarListener implements Listener {

    private static volatile boolean registered = false;

    static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        try {
            final PVPArena plugin = PVPArena.getInstance();
            if (plugin == null) {
                return;
            }
            unregisterStale();
            Bukkit.getPluginManager().registerEvents(new GuildWarListener(), plugin);
            registered = true;
            GuildBridge.invalidate(); // re-bind the guild API fresh on next use
            log().info("[GuildWar] listeners registered (results + queue hygiene + arena privacy).");
        } catch (final Throwable t) {
            log().warning("[GuildWar] Could not register listeners: " + t.getMessage());
        }
    }

    /** Normal win — record it for the winner's guild. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWin(final PAWinEvent event) {
        final Arena arena = event.getArena();
        if (arena == null) {
            return;
        }
        final GuildWarMatch match = GuildWarMatch.forArena(arena.getName());
        if (match == null || match.resolved) {
            return;
        }
        final Player winner = event.getPlayer();
        if (winner == null) {
            return;
        }
        final UUID winnerGuild = match.ownGuild(winner.getUniqueId());
        final UUID loserGuild = match.otherGuild(winner.getUniqueId());
        if (winnerGuild == null || loserGuild == null) {
            return; // winner isn't one of the tracked players — can't attribute, leave unresolved
        }
        GuildWar.resolve(match, winnerGuild, loserGuild);
    }

    /** A matched player leaving an unresolved, in-progress match = that player's guild loses. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(final PALeaveEvent event) {
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        GuildWar.dequeue(player.getUniqueId());
        if (!event.isSpectator()) {
            handleExit(player.getUniqueId());
        }
    }

    /** Disconnect mid-fight is treated identically to a leave. Also dequeues a waiting player. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        GuildWar.dequeue(playerId);
        handleExit(playerId);
    }

    /** Clear the match claim at end; unresolved = no-contest. Retry matchmaking now an arena is free. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnd(final PAEndEvent event) {
        final Arena arena = event.getArena();
        if (arena == null) {
            return;
        }
        GuildWarMatch.close(arena.getName());
        GuildWar.tryMatch();
    }

    /** Queue-only privacy + dequeue-on-join. Runs early enough to veto the join. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJoin(final PAJoinEvent event) {
        final Arena arena = event.getArena();
        final Player player = event.getPlayer();
        if (arena == null || player == null) {
            return;
        }

        // A queued player who joins anything leaves the queue (the matched pair was already dequeued).
        GuildWar.dequeue(player.getUniqueId());

        // Walk-up joins to guildwar* arenas are blocked unless the player is part of that match.
        if (GuildWarArenas.isGuildWarArena(arena)) {
            final GuildWarMatch match = GuildWarMatch.forArena(arena.getName());
            if (match == null || !match.involves(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(org.bukkit.ChatColor.RED
                        + "This is a GuildWar arena — join it with /cyangpa guildwar.");
            }
        }
    }

    private static void handleExit(final UUID playerId) {
        final GuildWarMatch match = GuildWarMatch.forPlayer(playerId);
        if (match == null || match.resolved) {
            return;
        }
        final Arena arena = arenaOf(match);
        if (arena == null || !arena.isFightInProgress()) {
            return; // not an in-progress match exit (e.g. countdown) — don't score it
        }
        // Only credit a loss if the opponent is still actively fighting. If both sides are gone
        // (e.g. a simultaneous double-death), this is ambiguous — let PAWinEvent or the end-event
        // no-contest decide instead of arbitrarily blaming whoever's leave event fired first.
        final UUID opponentId = match.involves(playerId)
                ? (playerId.equals(match.player1) ? match.player2 : match.player1)
                : null;
        if (opponentId == null || !isFighting(arena, opponentId)) {
            return;
        }
        final UUID loserGuild = match.ownGuild(playerId);
        final UUID winnerGuild = match.otherGuild(playerId);
        GuildWar.resolve(match, winnerGuild, loserGuild);
    }

    private static boolean isFighting(final Arena arena, final UUID playerId) {
        for (final net.slipcor.pvparena.arena.ArenaPlayer ap : arena.getFighters()) {
            final Player p = ap.getPlayer();
            if (p != null && playerId.equals(p.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    private static Arena arenaOf(final GuildWarMatch match) {
        for (final Arena arena : net.slipcor.pvparena.managers.ArenaManager.getArenas()) {
            if (match.arenaName.equalsIgnoreCase(arena.getName())) {
                return arena;
            }
        }
        return null;
    }

    private static void unregisterStale() {
        final String target = GuildWarListener.class.getName();
        for (final HandlerList handlerList : HandlerList.getHandlerLists()) {
            final List<Listener> stale = new ArrayList<>();
            for (final RegisteredListener rl : handlerList.getRegisteredListeners()) {
                if (target.equals(rl.getListener().getClass().getName())) {
                    stale.add(rl.getListener());
                }
            }
            stale.forEach(handlerList::unregister);
        }
    }

    private static Logger log() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
