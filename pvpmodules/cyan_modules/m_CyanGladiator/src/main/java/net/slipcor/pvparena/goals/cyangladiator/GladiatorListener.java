package net.slipcor.pvparena.goals.cyangladiator;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.events.PAStartEvent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bukkit listener that supplies the two things the goal can't (goals have no damage hook and can't
 * veto start):
 * <ul>
 *     <li><b>Friendly fire</b> — cancels same-guild damage in Gladiator arenas.</li>
 *     <li><b>Start gate</b> — cancels the start until ≥ 2 distinct guilds are present.</li>
 * </ul>
 * Registered once via {@link GoalGladiator}'s static initializer; reload-safe (drops any prior
 * instance by class name, like {@code m_CyanDeathFix}).
 */
public class GladiatorListener implements Listener {

    static final String GOAL_NAME = "Gladiator";
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
            Bukkit.getPluginManager().registerEvents(new GladiatorListener(), plugin);
            registered = true;
            GuildBridge.invalidate(); // re-bind the guild API fresh on next use
            log().info("[Gladiator] listeners registered (friendly fire + start gate).");
        } catch (final Throwable t) {
            log().warning("[Gladiator] Could not register listeners: " + t.getMessage());
        }
    }

    static boolean isGladiator(final Arena arena) {
        return arena != null && arena.getGoal() != null && GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFriendlyFire(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
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

    @EventHandler
    public void onStart(final PAStartEvent event) {
        final Arena arena = event.getArena();
        if (!isGladiator(arena)) {
            return;
        }
        final Set<UUID> guilds = new HashSet<>();
        for (final ArenaPlayer ap : arena.getFighters()) {
            final UUID guildId = GuildBridge.get().guildId(ap.getPlayer());
            if (guildId != null) {
                guilds.add(guildId);
            }
        }
        if (guilds.size() < 2) {
            event.setCancelled(true);
            arena.broadcast(ChatColor.RED + "Gladiator needs at least 2 different guilds to start.");
        }
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
}
