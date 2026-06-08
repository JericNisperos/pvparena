package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Cyan-owned PVP Arena module that runs a global, guild-vs-guild 1v1 matchmaking queue:
 * <pre>/cyangpa guildwar</pre>
 *
 * <p>A player queues with {@code /cyangpa guildwar}; when two queued players from <b>different</b>
 * guilds are available they are auto-joined into a random free {@code guildwar*} arena (one per
 * team). The winning guild gets {@code +1 win}, the losing guild {@code +1 loss} and is
 * <b>locked out until the next daily reset (00:00 GMT+8)</b>. See {@code plans/guildwar/00-plan.md}.</p>
 *
 * <h2>Independence</h2>
 * <p>This is a <b>fully independent</b> module with its <b>own</b> command {@code /cyangpa} — it does
 * not share the {@code /cyanpa} label owned by {@code m_CyanVanillaJoin}, and carries its own
 * {@link GuildBridge} copy. Enable/disable either module with zero impact on the other.</p>
 *
 * <h2>How registration is triggered</h2>
 * <p>PVP Arena's {@code JarLoader} loads each module main-class with
 * {@code Class.forName(name, true, loader)} — i.e. it <i>initializes</i> the class at plugin enable.
 * The {@code static} initializer below therefore runs once at startup and registers both the command
 * and the event listener, without requiring any arena to enable this module. {@link #configParse} is
 * wired as a defensive fallback; both paths funnel through idempotent guards.</p>
 */
public class CyanGuildWar extends ArenaModule {

    static {
        // Runs at plugin enable (JarLoader uses Class.forName(..., initialize = true)).
        ensureWiredSafely();
    }

    public CyanGuildWar() {
        super("CyanGuildWar");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    /** Defensive fallback trigger: fires per enabled arena, idempotent via the registration guards. */
    @Override
    public void configParse(final YamlConfiguration config) {
        ensureWiredSafely();
    }

    /** Dead hook in current core, but harmless to wire in case it ever gets invoked upstream. */
    @Override
    public void onThisLoad() {
        ensureWiredSafely();
    }

    private static void ensureWiredSafely() {
        try {
            GuildWarConfig.get().load();
            GuildWarResultStore.get().load();
            CyanGpaCommand.ensureRegistered(getCommandMap());
            GuildWarListener.ensureRegistered();
        } catch (final Throwable t) {
            // Never let wiring problems prevent the module from loading as a normal ArenaModule.
            logger().warning("[CyanGuildWar] Could not wire up GuildWar: " + t.getMessage());
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
