package net.slipcor.pvparena.modules.cyanvanillajoin;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Cyan-owned PVP Arena module that exposes a custom global join command:
 * <pre>/cyanpa vanillajoin</pre>
 *
 * <p>This is a faithful re-implementation of the original fork's {@code PAG_VanillaJoin}
 * global command, but lifted out of core so the plugin can be upgraded without conflicts.
 * Instead of patching PVP Arena's {@code /pa} command list, we register our <b>own</b> Bukkit
 * command ({@code /cyanpa}) at runtime, which means <b>zero edits to PVP Arena core</b>.</p>
 *
 * <h2>How registration is triggered</h2>
 * PVP Arena's {@code JarLoader} loads each module main-class with
 * {@code Class.forName(name, true, loader)} — i.e. it <i>initializes</i> the class at plugin
 * enable. The {@code static} initializer below therefore runs once at startup and registers the
 * command, without requiring any arena to enable this module. {@link #configParse} is wired as a
 * defensive fallback in case that loader behavior ever changes; both paths funnel through the
 * idempotent {@link CyanPaCommand#ensureRegistered()} guard.
 */
public class CyanVanillaJoin extends ArenaModule {

    static {
        // Runs at plugin enable (JarLoader uses Class.forName(..., initialize = true)).
        ensureCommandRegisteredSafely();
    }

    public CyanVanillaJoin() {
        super("CyanVanillaJoin");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    /** Defensive fallback trigger: fires per enabled arena, idempotent via the registration guard. */
    @Override
    public void configParse(final YamlConfiguration config) {
        ensureCommandRegisteredSafely();
    }

    /** Dead hook in current core, but harmless to wire in case it ever gets invoked upstream. */
    @Override
    public void onThisLoad() {
        ensureCommandRegisteredSafely();
    }

    private static void ensureCommandRegisteredSafely() {
        try {
            CyanPaCommand.ensureRegistered(getCommandMap());
        } catch (final Throwable t) {
            // Never let registration problems prevent the module from loading as a normal ArenaModule.
            logger().warning("[CyanVanillaJoin] Could not register /cyanpa command: " + t.getMessage());
        }
    }

    private static CommandMap getCommandMap() throws ReflectiveOperationException {
        final Server server = Bukkit.getServer();
        final Method getCommandMap = server.getClass().getMethod("getCommandMap");
        getCommandMap.setAccessible(true);
        return (CommandMap) getCommandMap.invoke(server);
    }

    static Logger logger() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
