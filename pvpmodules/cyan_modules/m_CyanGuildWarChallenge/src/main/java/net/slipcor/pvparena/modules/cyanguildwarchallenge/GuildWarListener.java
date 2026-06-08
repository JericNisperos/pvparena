package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.events.PAEndEvent;
import net.slipcor.pvparena.events.PAJoinEvent;
import net.slipcor.pvparena.events.PALeaveEvent;
import net.slipcor.pvparena.events.PAStartEvent;
import net.slipcor.pvparena.events.PAWinEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
 * Drives challenge-mode result detection, the start-gate and arena privacy:
 * <ul>
 *     <li><b>{@link PAStartEvent}</b> — cancels every start for {@code guildwar*} arenas; the core
 *         ignores the cancel when a start is <i>forced</i>, so only our countdown's
 *         {@code arena.start(true)} ever begins the fight (native auto-start is suppressed).</li>
 *     <li><b>{@link PAWinEvent}</b> — records the win for the winner's guild (idempotent).</li>
 *     <li><b>{@link PAEndEvent}</b> — frees the arena / drops the challenge.</li>
 *     <li><b>{@link PALeaveEvent} / {@link PlayerQuitEvent}</b> — a pre-fight roster drop removes the
 *         player and (during a countdown) stops it and returns to staging.</li>
 *     <li><b>{@link PAJoinEvent}</b> — blocks walk-up joins to {@code guildwar*} arenas; only joins we
 *         initiated (permitted this tick) pass.</li>
 * </ul>
 * Registered once via {@link CyanGuildWarChallenge}'s static initializer; reload-safe.
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
            GuildBridge.invalidate();
            log().info("[GuildWarChallenge] listeners registered (start-gate + results + privacy).");
        } catch (final Throwable t) {
            log().warning("[GuildWarChallenge] Could not register listeners: " + t.getMessage());
        }
    }

    /** Suppress native auto-start for guildwar* arenas — forced starts (ours) bypass this cancel. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStart(final PAStartEvent event) {
        final Arena arena = event.getArena();
        if (GuildWarArenas.isGuildWarArena(arena)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWin(final PAWinEvent event) {
        GuildWarChallenge.resolveWin(event.getArena(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnd(final PAEndEvent event) {
        GuildWarChallenge.onArenaEnd(event.getArena());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(final PALeaveEvent event) {
        final Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        final Challenge challenge = ChallengeRegistry.byPlayer(player.getUniqueId());
        if (challenge != null) {
            GuildWarChallenge.onParticipantRemoved(challenge, player.getUniqueId(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        final Challenge challenge = ChallengeRegistry.byPlayer(id);
        if (challenge != null) {
            GuildWarChallenge.onParticipantRemoved(challenge, id, false);
        }
    }

    /** Walk-up join privacy: only joins the challenge module initiated may enter a guildwar* arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onJoin(final PAJoinEvent event) {
        final Arena arena = event.getArena();
        final Player player = event.getPlayer();
        if (arena == null || player == null) {
            return;
        }
        if (GuildWarArenas.isGuildWarArena(arena) && !GuildWarChallenge.isPermitted(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "This is a Guild War arena — use /guildwar to take part.");
        }
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
