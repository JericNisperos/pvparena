package net.slipcor.pvparena.modules.cyanvillagedefensemod;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * VillageDefense settings, backed by {@code plugins/PVPArena/cyan_villagedefense_config.yml}.
 *
 * <p>Written with documented defaults on first run, reloaded on enable / {@code /vdefense reload}.
 * The goal jar reads the gameplay keys (waves, villagers, lives) from this same file (see
 * {@code GoalVillageDefense} / {@code VillageDefenseSettings}); the module itself only consumes
 * {@code autostart-seconds}.</p>
 */
final class VillageDefenseConfig {

    static final String FILE_NAME = "cyan_villagedefense_config.yml";

    private static final int DEF_AUTOSTART_SECONDS = 60;
    private static final int DEF_PLAYER_LIVES = 1;
    private static final boolean DEF_ANNOUNCE_WAVE_START = true;
    private static final boolean DEF_ANNOUNCE_WAVE_CLEARED = true;
    private static final boolean DEF_ANNOUNCE_VILLAGER_DEATH = true;
    private static final int DEF_VILLAGERS_PER_SPAWN = 1;
    private static final boolean DEF_PROTECT_VILLAGERS = true;
    private static final int DEF_FIRST_DELAY_SECONDS = 15;
    private static final int DEF_INTERVAL_SECONDS = 30;
    private static final int DEF_BASE_MOBS = 3;
    private static final int DEF_MOBS_PER_WAVE = 2;
    private static final int DEF_MOBS_PER_PLAYER = 1;
    private static final int DEF_MAX_MOBS_ALIVE = 40;
    private static final String DEF_MOB_TYPE = "ZOMBIE";
    private static final boolean DEF_FIRE_RESISTANT = true;
    private static final double DEF_EMERALD_CHANCE = 0.4d;
    private static final int DEF_EMERALD_BASE = 1;
    private static final int DEF_EMERALD_WAVES_PER_EXTRA = 3;

    private static VillageDefenseConfig instance;

    private int autostartSeconds = DEF_AUTOSTART_SECONDS;

    static VillageDefenseConfig get() {
        if (instance == null) {
            instance = new VillageDefenseConfig();
        }
        return instance;
    }

    void load() {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        final boolean existed = file.exists();
        final YamlConfiguration yaml = existed
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        // Write on first run AND whenever an upgrade introduces new keys, so existing installs pick
        // up new settings without having to delete their config.
        if (ensureDefaults(yaml) || !existed) {
            saveConfig(yaml, file);
        }

        this.autostartSeconds = Math.max(5, yaml.getInt("autostart-seconds", DEF_AUTOSTART_SECONDS));
    }

    /** Seconds between the first player joining and the match auto-starting. */
    int autostartSeconds() {
        return this.autostartSeconds;
    }

    /** Set any key that's missing to its default. Returns {@code true} if anything was added. */
    private static boolean ensureDefaults(final YamlConfiguration yaml) {
        boolean changed = false;
        changed |= setIfAbsent(yaml, "autostart-seconds", DEF_AUTOSTART_SECONDS);
        changed |= setIfAbsent(yaml, "player-lives", DEF_PLAYER_LIVES);
        changed |= setIfAbsent(yaml, "announce.wave-start", DEF_ANNOUNCE_WAVE_START);
        changed |= setIfAbsent(yaml, "announce.wave-cleared", DEF_ANNOUNCE_WAVE_CLEARED);
        changed |= setIfAbsent(yaml, "announce.villager-death", DEF_ANNOUNCE_VILLAGER_DEATH);
        changed |= setIfAbsent(yaml, "villagers.per-spawn", DEF_VILLAGERS_PER_SPAWN);
        changed |= setIfAbsent(yaml, "villagers.protect-from-players", DEF_PROTECT_VILLAGERS);
        changed |= setIfAbsent(yaml, "waves.first-delay-seconds", DEF_FIRST_DELAY_SECONDS);
        changed |= setIfAbsent(yaml, "waves.interval-seconds", DEF_INTERVAL_SECONDS);
        changed |= setIfAbsent(yaml, "waves.base-mobs", DEF_BASE_MOBS);
        changed |= setIfAbsent(yaml, "waves.mobs-per-wave", DEF_MOBS_PER_WAVE);
        changed |= setIfAbsent(yaml, "waves.mobs-per-player", DEF_MOBS_PER_PLAYER);
        changed |= setIfAbsent(yaml, "waves.max-mobs-alive", DEF_MAX_MOBS_ALIVE);
        changed |= setIfAbsent(yaml, "waves.mob-type", DEF_MOB_TYPE);
        changed |= setIfAbsent(yaml, "waves.fire-resistant-mobs", DEF_FIRE_RESISTANT);
        changed |= setIfAbsent(yaml, "drops.emerald-chance", DEF_EMERALD_CHANCE);
        changed |= setIfAbsent(yaml, "drops.emerald-base", DEF_EMERALD_BASE);
        changed |= setIfAbsent(yaml, "drops.emerald-waves-per-extra", DEF_EMERALD_WAVES_PER_EXTRA);
        return changed;
    }

    private static boolean setIfAbsent(final YamlConfiguration yaml, final String key, final Object value) {
        if (yaml.isSet(key)) {
            return false;
        }
        yaml.set(key, value);
        return true;
    }

    private void saveConfig(final YamlConfiguration yaml, final File file) {
        yaml.options().header("CyanVillageDefense (co-op PvE) settings. Read by BOTH the goal jar and the module jar.\n"
                + "autostart-seconds: seconds between the first player joining and the match auto-starting (>= 5).\n"
                + "player-lives: lives per defender. 1 = no respawn (current design; respawn support comes later).\n"
                + "announce.*: toggle the wave/villager broadcast messages.\n"
                + "villagers.per-spawn: how many villagers to spawn at each 'villager' spawn point.\n"
                + "villagers.protect-from-players: true = players cannot damage the villagers they defend.\n"
                + "waves.first-delay-seconds: delay between match start and wave 1.\n"
                + "waves.interval-seconds: delay between waves (>= 5).\n"
                + "waves: mobs per wave = base-mobs + mobs-per-wave * (wave - 1) + mobs-per-player * alive players,\n"
                + "  capped so no more than max-mobs-alive wave mobs are alive at once.\n"
                + "waves.mob-type: vanilla EntityType to spawn (ZOMBIE, HUSK, SKELETON, ...). This simple spawner\n"
                + "  is temporary — it will be replaced by MythicMobs integration (see plans/villagedefense-phases.md).\n"
                + "waves.fire-resistant-mobs: true = wave mobs get fire resistance so zombies don't burn in daylight.\n"
                + "drops.emerald-chance: 0.0-1.0 chance a dying wave mob drops emeralds (0.4 = 40%).\n"
                + "drops: amount = emerald-base + (wave - 1) / emerald-waves-per-extra, so later waves pay more\n"
                + "  (defaults: 1 emerald, +1 every 3 waves -> wave 1-3 = 1, wave 4-6 = 2, ...).");
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (final IOException e) {
            log().warning("[VillageDefense] Could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
