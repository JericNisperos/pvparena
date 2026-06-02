package net.slipcor.pvparena.modules.cyandeathfix;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Cyan-owned PVP Arena module that fixes the "true death" bug: arena fighters who die from
 * entity-less / environmental / true damage (void, {@code /kill}, cactus, fire, drowning, ...) are
 * routed into the arena's normal death handling instead of suffering a real vanilla death.
 *
 * <p>See {@code plans/death-fix/00-diagnosis-and-plan.md} for the full root-cause analysis.</p>
 *
 * <h2>Why core misses these</h2>
 * PVP Arena <i>fakes</i> death by intercepting the lethal {@code EntityDamageEvent}
 * ({@code EntityListener.handleDeathIfNeeded}). That only fires when the killing blow arrives as an
 * interceptable damage event. Causes that kill via {@code setHealth(0)}/{@code kill()} or the void
 * out-of-world floor never produce such an event, so the player really dies — and core's
 * {@code PlayerListener.onPlayerDeath} only logs "That should not happen."
 *
 * <h2>How this fixes it (no core edits)</h2>
 * {@link DeathFixListener} registers two safety nets and a combat-tag recorder; both nets call the
 * public {@code WorkflowManager.handlePlayerDeath(...)} so behavior matches a normal arena death.
 *
 * <h2>Per-arena opt-in</h2>
 * The listeners only act on players whose arena has this module enabled
 * ({@link #isEnabledFor(Arena)}), so you can test it on specific arenas. Registration itself happens
 * once at plugin enable (module main-class is loaded with {@code Class.forName(..., true)}), mirroring
 * {@code m_CyanVanillaJoin}.
 */
public class CyanDeathFix extends ArenaModule {

    static final String MODULE_NAME = "CyanDeathFix";

    private static volatile boolean registered = false;

    static {
        // Runs at plugin enable (JarLoader uses Class.forName(..., initialize = true)).
        ensureRegisteredSafely();
    }

    public CyanDeathFix() {
        super(MODULE_NAME);
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    /** Defensive fallback trigger (idempotent). */
    @Override
    public void configParse(final YamlConfiguration config) {
        ensureRegisteredSafely();
    }

    /** Dead hook in current core, but harmless to wire in case it ever gets invoked upstream. */
    @Override
    public void onThisLoad() {
        ensureRegisteredSafely();
    }

    /** True if the given arena has this module enabled (per-arena opt-in). */
    static boolean isEnabledFor(final Arena arena) {
        return arena != null && arena.getMods().stream()
                .anyMatch(mod -> MODULE_NAME.equals(mod.getName()));
    }

    private static synchronized void ensureRegisteredSafely() {
        if (registered) {
            return;
        }
        try {
            final PVPArena plugin = PVPArena.getInstance();
            if (plugin == null) {
                return;
            }
            // Reload-safe: drop any DeathFixListener left over from a previous module classloader
            // (e.g. after /pa reloadall) before registering a fresh one. Matched by class NAME so it
            // works across the separate URLClassLoaders the module loader creates.
            unregisterStaleListeners();
            Bukkit.getPluginManager().registerEvents(new DeathFixListener(), plugin);
            registered = true;
            logger().info("[CyanDeathFix] death-fix listeners registered");
        } catch (final Throwable t) {
            // Never let registration problems prevent the module from loading as a normal ArenaModule.
            logger().warning("[CyanDeathFix] Could not register death-fix listeners: " + t.getMessage());
        }
    }

    /**
     * Remove any previously-registered {@link DeathFixListener} (possibly from an older module
     * classloader) from every handler list, matched by fully-qualified class name so it survives the
     * classloader swap that {@code /pa reloadall} performs.
     */
    private static void unregisterStaleListeners() {
        final String target = DeathFixListener.class.getName();
        for (final HandlerList handlerList : HandlerList.getHandlerLists()) {
            final List<Object> stale = new ArrayList<>();
            for (final RegisteredListener registered : handlerList.getRegisteredListeners()) {
                if (target.equals(registered.getListener().getClass().getName())) {
                    stale.add(registered.getListener());
                }
            }
            stale.forEach(listener -> handlerList.unregister((org.bukkit.event.Listener) listener));
        }
    }

    static Logger logger() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
