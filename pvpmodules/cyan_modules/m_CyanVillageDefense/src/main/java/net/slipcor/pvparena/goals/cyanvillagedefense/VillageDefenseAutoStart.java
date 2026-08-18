package net.slipcor.pvparena.goals.cyanvillagedefense;

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

/**
 * Auto-start for VillageDefense arenas: the moment the first player joins, a {@link StartRunnable}
 * countdown ({@code goal.villagedefense.autostart-seconds}, default 60) is scheduled — no
 * {@code /pa ready} needed.
 *
 * <p>This is a <b>single static listener</b> covering every VillageDefense arena, not a per-arena
 * one: it has to be alive <i>before</i> a match starts, so it cannot share the goal's own
 * parseStart/reset listener lifecycle. Registered from {@link GoalVillageDefense}'s constructor and
 * reload-safe (drops any prior instance by class name across classloader reloads).</p>
 *
 * <p>For the countdown to behave, the arena should have {@code ready.minPlayers: 1} and
 * {@code ready.enforceCountdown: true} — without the latter, the StandardLounge cancels the running
 * countdown whenever another player joins (we then simply re-arm a fresh full-length countdown,
 * which works but resets the timer and spams chat).</p>
 */
public class VillageDefenseAutoStart implements Listener {

    private static volatile boolean registered;

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
        plugin.getLogger().info("[VillageDefense] auto-start listener registered.");
    }

    static boolean isVillageDefense(final Arena arena) {
        return arena != null && arena.getGoal() instanceof GoalVillageDefense;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(final PAJoinEvent event) {
        if (event.isSpectator() || !isVillageDefense(event.getArena())) {
            return;
        }
        final Arena arena = event.getArena();
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null || plugin.isShuttingDown()) {
            return; // stopping: scheduling against a disabled plugin throws, and no fight will start
        }
        // one tick later: the join has committed and other join modules have had their say
        Bukkit.getScheduler().runTask(plugin, () -> armCountdownIfNeeded(arena));
    }

    private static void armCountdownIfNeeded(final Arena arena) {
        if (arena.isFightInProgress() || arena.startRunner != null || arena.getFighters().isEmpty()) {
            return;
        }
        new StartRunnable(arena, ((GoalVillageDefense) arena.getGoal()).autostartSeconds());
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
}
