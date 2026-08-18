package net.slipcor.pvparena.modules.cyanvillagedefensemod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.events.PAJoinEvent;
import net.slipcor.pvparena.runnables.StartRunnable;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Auto-start for VillageDefense arenas: the moment the first player joins, a {@link StartRunnable}
 * countdown ({@code autostart-seconds}, default 60) is scheduled — no {@code /pa ready} needed.
 *
 * <p>The check runs one tick after {@link PAJoinEvent} so the join has fully committed (and so we
 * see whether another module already started a countdown). For the countdown to behave, the arena
 * should have {@code ready.minPlayers: 1} and {@code ready.enforceCountdown: true} — without the
 * latter, the StandardLounge cancels the running countdown whenever another player joins (we then
 * simply re-arm a fresh full-length countdown, which works but resets the timer and spams chat).</p>
 *
 * <p>Registered once via {@link VillageDefenseMod}'s static initializer; reload-safe (drops any
 * prior instance by class name across classloader reloads).</p>
 */
public class VillageDefenseAutoStart implements Listener {

    private static volatile boolean registered = false;

    static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        unregisterStale();
        Bukkit.getPluginManager().registerEvents(new VillageDefenseAutoStart(), plugin);
        registered = true;
        log().info("[VillageDefense] auto-start listener registered ("
                + VillageDefenseConfig.get().autostartSeconds() + "s after first join).");
    }

    static boolean isVillageDefense(final Arena arena) {
        return arena != null && arena.getGoal() != null
                && VillageDefenseMod.GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PAJoinEvent event) {
        if (event.isSpectator() || !isVillageDefense(event.getArena())) {
            return;
        }
        final Arena arena = event.getArena();
        // one tick later: the join has committed and other join modules have had their say
        Bukkit.getScheduler().runTask(PVPArena.getInstance(), () -> armCountdownIfNeeded(arena));
    }

    private static void armCountdownIfNeeded(final Arena arena) {
        if (arena.isFightInProgress() || arena.startRunner != null || arena.getFighters().isEmpty()) {
            return;
        }
        new StartRunnable(arena, VillageDefenseConfig.get().autostartSeconds());
    }

    private static void unregisterStale() {
        final String target = VillageDefenseAutoStart.class.getName();
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
