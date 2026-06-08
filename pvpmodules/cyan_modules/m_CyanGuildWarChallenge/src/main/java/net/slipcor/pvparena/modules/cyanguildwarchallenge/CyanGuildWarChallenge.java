package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Cyan-owned PVP Arena module: challenge-style guild wars via {@code /guildwar <guild> <count>}.
 *
 * <p>A guild member challenges another <i>online</i> guild to an NvN; an officer of the challenged
 * guild accepts; both sides fill their rosters; a 10-second countdown runs once both are full
 * (resetting if anyone drops); then the fight starts in a {@code guildwar*} arena. See
 * {@code plans/guildwar/01-challenge-plan.md}.</p>
 *
 * <h2>Independence</h2>
 * <p>Fully independent module with its <b>own</b> command {@code /guildwar} and its own
 * {@link GuildBridge} copy — no dependency on the queue module ({@code m_CyanGuildWar}) or any other
 * Cyan jar. It does <b>not</b> need to be attached to any arena.</p>
 *
 * <h2>How registration is triggered</h2>
 * <p>PVP Arena's {@code JarLoader} loads each module main-class with
 * {@code Class.forName(name, true, loader)} — i.e. it <i>initializes</i> the class at plugin enable.
 * The {@code static} initializer below therefore runs once at startup and wires the command, the
 * listener and the config without requiring any arena to enable this module.</p>
 */
public class CyanGuildWarChallenge extends ArenaModule {

    static {
        // Runs at plugin enable (JarLoader uses Class.forName(..., initialize = true)).
        ensureWiredSafely();
    }

    public CyanGuildWarChallenge() {
        super("CyanGuildWarChallenge");
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
            GuildWarCommand.ensureRegistered(getCommandMap());
            GuildWarListener.ensureRegistered();
        } catch (final Throwable t) {
            logger().warning("[CyanGuildWarChallenge] Could not wire up Guild War: " + t.getMessage());
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
