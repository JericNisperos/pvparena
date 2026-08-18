package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.events.PAEndEvent;
import net.slipcor.pvparena.events.PAJoinEvent;
import net.slipcor.pvparena.events.PALoseEvent;
import net.slipcor.pvparena.events.PAStartEvent;
import net.slipcor.pvparena.events.PAWinEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The Gladiator module's gameplay glue — everything a {@code goal} can't do on its own:
 * <ul>
 *     <li><b>Friendly fire</b> — cancels same-guild damage in Gladiator arenas (unless enabled).</li>
 *     <li><b>Start gate</b> — cancels the start until {@code min-guilds} distinct guilds are present.</li>
 *     <li><b>Participant tracking</b> — groups joiners by guild ({@link PAJoinEvent}) so results and
 *         rewards know who fought for whom.</li>
 *     <li><b>Elimination announce</b> — broadcasts "&lt;guild&gt; eliminated — N remain" as guilds fall.</li>
 *     <li><b>Result + reward</b> — on the win ({@link PAWinEvent}) records the standings and runs the
 *         configured reward commands (idempotent per match).</li>
 * </ul>
 * Registered once via {@link GladiatorMod}'s static initializer; reload-safe (drops any prior instance
 * by class name across classloader reloads).
 */
public class GladiatorListener implements Listener {

    static final String GOAL_NAME = "Gladiator";
    private static volatile boolean registered = false;

    /** Per-arena live match state, keyed by arena name (main-thread only — no synchronization). */
    private static final Map<String, Match> MATCHES = new HashMap<>();

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
            Bukkit.getPluginManager().registerEvents(new GladiatorListener(), plugin);
            registered = true;
            GuildBridge.invalidate(); // re-bind the guild API fresh on next use
            log().info("[Gladiator] listeners registered (friendly fire + start gate + results).");
        } catch (final Throwable t) {
            log().warning("[Gladiator] Could not register listeners: " + t.getMessage());
        }
    }

    static boolean isGladiator(final Arena arena) {
        return arena != null && arena.getGoal() != null && GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName());
    }

    // ---- friendly fire -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFriendlyFire(final EntityDamageByEntityEvent event) {
        if (GladiatorConfig.get().friendlyFire() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        final Arena arena = ArenaPlayer.fromPlayer(victim).getArena();
        if (!isGladiator(arena)) {
            return;
        }
        final Player attacker = ArenaPlayer.getLastDamagingPlayer(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        // Allies are enemies: only same-guild members are protected.
        if (GuildBridge.get().sameGuild(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    // ---- start gate ----------------------------------------------------------------------------

    @EventHandler
    public void onStart(final PAStartEvent event) {
        final Arena arena = event.getArena();
        if (!isGladiator(arena)) {
            return;
        }
        final int needed = GladiatorConfig.get().minGuilds();
        if (distinctGuilds(arena).size() < needed) {
            event.setCancelled(true);
            arena.broadcast(ChatColor.RED + "Gladiator needs at least " + needed + " different guilds to start.");
        }
    }

    // ---- participant tracking ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PAJoinEvent event) {
        final Arena arena = event.getArena();
        final Player player = event.getPlayer();
        if (!isGladiator(arena) || player == null) {
            return;
        }
        final UUID guildId = GuildBridge.get().guildId(player);
        if (guildId != null) {
            MATCHES.computeIfAbsent(key(arena), k -> new Match())
                    .participants.computeIfAbsent(guildId, g -> new HashSet<>())
                    .add(player.getUniqueId());
        }
    }

    // ---- elimination announce ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLose(final PALoseEvent event) {
        final Arena arena = event.getArena();
        if (!isGladiator(arena) || !GladiatorConfig.get().announceEliminations()) {
            return;
        }
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null || plugin.isShuttingDown()) {
            return; // stopping: scheduling would throw, and nobody is left to hear the announcement
        }
        final String key = key(arena);
        // Recompute next tick — the eliminated player's status isn't settled yet when LoseEvent fires.
        Bukkit.getScheduler().runTask(plugin, () -> announceEliminations(arena, key));
    }

    private void announceEliminations(final Arena arena, final String key) {
        final Match match = MATCHES.get(key);
        if (match == null || match.resolved || !arena.isFightInProgress()) {
            return;
        }
        final Set<UUID> living = distinctGuilds(arena);
        for (final UUID guildId : new HashSet<>(match.participants.keySet())) {
            if (!living.contains(guildId) && match.announcedEliminated.add(guildId) && living.size() >= 1) {
                arena.broadcast(ChatColor.GRAY + "Guild " + ChatColor.YELLOW + GladiatorText.guildLabel(guildId)
                        + ChatColor.GRAY + " was eliminated — " + ChatColor.WHITE + living.size()
                        + ChatColor.GRAY + " guild" + (living.size() == 1 ? "" : "s") + " remain.");
            }
        }
    }

    // ---- result + reward -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWin(final PAWinEvent event) {
        final Arena arena = event.getArena();
        final Player winner = event.getPlayer();
        if (!isGladiator(arena) || winner == null) {
            return;
        }
        final Match match = MATCHES.get(key(arena));
        if (match == null || match.resolved) {
            return; // already recorded for this match (PAWinEvent fires per surviving player)
        }
        final GuildBridge guilds = GuildBridge.get();
        final UUID winnerGuild = guilds.guildId(winner);
        if (winnerGuild == null || !match.participants.containsKey(winnerGuild)) {
            return; // can't attribute — leave unresolved
        }
        match.resolved = true;

        final Set<UUID> winnerPlayers = new HashSet<>(match.participants.getOrDefault(winnerGuild, new HashSet<>()));
        final Map<UUID, GladiatorResultStore.LoserEntry> losers = new HashMap<>();
        for (final Map.Entry<UUID, Set<UUID>> entry : match.participants.entrySet()) {
            final UUID guildId = entry.getKey();
            if (guildId.equals(winnerGuild)) {
                continue;
            }
            losers.put(guildId, new GladiatorResultStore.LoserEntry(
                    GladiatorText.sanitize(guilds.clanName(guildId)), new HashSet<>(entry.getValue())));
        }

        GladiatorResultStore.get().recordMatch(winnerGuild,
                GladiatorText.sanitize(guilds.clanName(winnerGuild)), winnerPlayers, losers);

        // Rewards. The goal already broadcast the winner, so we stay quiet here.
        final String winnerTag = guilds.clanName(winnerGuild);
        GladiatorRewards.run(arena.getName(), GladiatorConfig.get().winnerCommands(),
                winnerGuild, winnerTag, winnerTag, winnerPlayers);
        for (final Map.Entry<UUID, GladiatorResultStore.LoserEntry> entry : losers.entrySet()) {
            GladiatorRewards.run(arena.getName(), GladiatorConfig.get().participationCommands(),
                    entry.getKey(), guilds.clanName(entry.getKey()), winnerTag, entry.getValue().players);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnd(final PAEndEvent event) {
        final Arena arena = event.getArena();
        if (arena == null) {
            return;
        }
        // Core fires PAEndEvent within the same reset() as the per-winner PAWinEvent(s); dropping the
        // match now could make onWin find nothing. Close on the next tick, after wins are tallied.
        final PVPArena plugin = PVPArena.getInstance();
        final String key = key(arena);
        // Shutting down: the scheduler rejects tasks once the plugin is disabled, and there is no
        // next tick to wait for anyway — drop it now.
        if (plugin != null && !plugin.isShuttingDown()) {
            Bukkit.getScheduler().runTask(plugin, () -> MATCHES.remove(key));
        } else {
            MATCHES.remove(key);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Distinct guilds among the arena's still-fighting players. */
    private static Set<UUID> distinctGuilds(final Arena arena) {
        final Set<UUID> guilds = new HashSet<>();
        for (final ArenaPlayer ap : arena.getFighters()) {
            if (ap.getStatus() != PlayerStatus.FIGHT || ap.getPlayer() == null) {
                continue;
            }
            final UUID guildId = GuildBridge.get().guildId(ap.getPlayer());
            if (guildId != null) {
                guilds.add(guildId);
            }
        }
        return guilds;
    }

    private static String key(final Arena arena) {
        return arena.getName().toLowerCase(java.util.Locale.ROOT);
    }

    private static void unregisterStale() {
        final String target = GladiatorListener.class.getName();
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

    /** Per-arena live match state. */
    private static final class Match {
        final Map<UUID, Set<UUID>> participants = new HashMap<>();
        final Set<UUID> announcedEliminated = new HashSet<>();
        boolean resolved;
    }
}
