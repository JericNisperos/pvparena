package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.logging.Logger;

/**
 * Companion module for the <b>Gladiator</b> goal — the hot-reloadable half (config, the
 * {@code /gladiator} command, the gameplay listener, results, the leaderboard, rewards and the
 * PlaceholderAPI expansion), so all of it can be iterated via {@code /gladiator reinstall} (or
 * {@code /pa modules install/uninstall}) without a server restart.
 *
 * <p>Like {@code CyanGuildWarChallenge}, everything is wired <b>globally from the static initializer</b>
 * (PVP Arena's {@code JarLoader} loads each module main-class with {@code Class.forName(name, true, …)},
 * which runs the static block at plugin enable). The module therefore does <b>not</b> need to be
 * attached to any arena — a {@code Gladiator}-goal arena is all it takes.</p>
 *
 * <p>If the Gladiator goal jar isn't present in {@code /goals} the command politely refuses; the rest
 * stays dormant.</p>
 */
public class GladiatorMod extends ArenaModule {

    static final String GOAL_NAME = "Gladiator";

    static {
        // Runs at plugin enable (JarLoader uses Class.forName(..., initialize = true)).
        ensureWiredSafely();
    }

    public GladiatorMod() {
        super("GladiatorMod");
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

    /** True if the Gladiator goal jar is present in /goals. */
    static boolean goalInstalled() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null && plugin.getAgm() != null && plugin.getAgm().hasLoadable(GOAL_NAME);
    }

    private static void ensureWiredSafely() {
        try {
            GladiatorConfig.get().load();
            GladiatorResultStore.get().load();
            GladiatorListener.ensureRegistered();
            GladiatorCommand.ensureRegistered();
            // Optional PlaceholderAPI hook — only touch the PAPI-extending class when PAPI is present,
            // so this module still loads on servers without PlaceholderAPI installed.
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                GladiatorPlaceholders.registerSafely();
            }
            if (!goalInstalled()) {
                log().warning("[Gladiator] GladiatorMod loaded but the Gladiator goal is not installed in /goals — "
                        + "rumbles can't run until pa_m_cyangladiator.jar is added there.");
            }
        } catch (final Throwable t) {
            log().warning("[Gladiator] Could not wire up Gladiator: " + t.getMessage());
        }
    }

    static Logger log() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
