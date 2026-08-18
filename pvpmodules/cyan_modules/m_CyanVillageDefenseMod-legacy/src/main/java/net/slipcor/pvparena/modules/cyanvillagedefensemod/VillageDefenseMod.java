package net.slipcor.pvparena.modules.cyanvillagedefensemod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.logging.Logger;

/**
 * Companion module for the <b>VillageDefense</b> goal — the hot-reloadable half: the shared config
 * file ({@code cyan_villagedefense_config.yml}), the {@code /vdefense} command and the auto-start
 * timer (the match begins a configurable number of seconds after the first player joins). All of it
 * can be iterated via {@code /vdefense reinstall} (or {@code /pa modules install/uninstall}) without
 * a server restart.
 *
 * <p>Like {@code CyanGladiatorMod}, everything is wired <b>globally from the static initializer</b>
 * (PVP Arena's {@code JarLoader} loads each module main-class with {@code Class.forName(name, true, …)},
 * which runs the static block at plugin enable). The module therefore does <b>not</b> need to be
 * attached to any arena — a {@code VillageDefense}-goal arena is all it takes.</p>
 *
 * <p>If the VillageDefense goal jar isn't present in {@code /goals} the command politely refuses;
 * the rest stays dormant.</p>
 */
public class VillageDefenseMod extends ArenaModule {

    static final String GOAL_NAME = "VillageDefense";

    static {
        // Runs at plugin enable (JarLoader uses Class.forName(..., initialize = true)).
        ensureWiredSafely();
    }

    public VillageDefenseMod() {
        super("VillageDefenseMod");
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

    /** True if the VillageDefense goal jar is present in /goals. */
    static boolean goalInstalled() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null && plugin.getAgm() != null && plugin.getAgm().hasLoadable(GOAL_NAME);
    }

    private static void ensureWiredSafely() {
        try {
            VillageDefenseConfig.get().load();
            VillageDefenseAutoStart.ensureRegistered();
            VillageDefenseCommand.ensureRegistered();
            if (!goalInstalled()) {
                log().warning("[VillageDefense] VillageDefenseMod loaded but the VillageDefense goal is not "
                        + "installed in /goals — matches can't run until pa_m_cyanvillagedefense.jar is added there.");
            }
        } catch (final Throwable t) {
            log().warning("[VillageDefense] Could not wire up VillageDefense: " + t.getMessage());
        }
    }

    static Logger log() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
