package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * GuildWar's externalized settings, backed by {@code plugins/PVPArena/cyan_guildwar_config.yml}.
 *
 * <p>Created with documented defaults on first run. Reloaded on enable / {@code /pa reloadall}.
 * Kept separate from {@link GuildWarResultStore} (which holds match <i>data</i>) so wiping config
 * never touches scores.</p>
 *
 * <table>
 *   <tr><th>key</th><th>default</th><th>meaning</th></tr>
 *   <tr><td>{@code queue-timeout-seconds}</td><td>360</td><td>how long a player waits before auto-removal</td></tr>
 *   <tr><td>{@code arena-prefix}</td><td>guildwar</td><td>arenas whose name starts with this are GuildWar (queue-only)</td></tr>
 *   <tr><td>{@code timezone}</td><td>Asia/Singapore</td><td>zone whose local midnight is the daily lockout reset</td></tr>
 *   <tr><td>{@code announce-globally}</td><td>false</td><td>broadcast match start/result to the whole server (vs arena only)</td></tr>
 * </table>
 */
final class GuildWarConfig {

    private static final String FILE_NAME = "cyan_guildwar_config.yml";

    private static final int DEF_TIMEOUT = 360;
    private static final String DEF_PREFIX = "guildwar";
    private static final String DEF_ZONE = "Asia/Singapore";
    private static final boolean DEF_ANNOUNCE = false;

    private static GuildWarConfig instance;

    private int queueTimeoutSeconds = DEF_TIMEOUT;
    private String arenaPrefix = DEF_PREFIX;
    private ZoneId zone = ZoneId.of(DEF_ZONE);
    private boolean announceGlobally = DEF_ANNOUNCE;

    static GuildWarConfig get() {
        if (instance == null) {
            instance = new GuildWarConfig();
        }
        return instance;
    }

    /** (Re)load the config from disk, writing a defaults file if none exists. */
    void load() {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            writeDefaults(file);
        }

        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        int timeout = yaml.getInt("queue-timeout-seconds", DEF_TIMEOUT);
        this.queueTimeoutSeconds = timeout > 0 ? timeout : DEF_TIMEOUT;

        final String prefix = yaml.getString("arena-prefix", DEF_PREFIX);
        this.arenaPrefix = (prefix == null || prefix.trim().isEmpty())
                ? DEF_PREFIX : prefix.trim().toLowerCase(Locale.ROOT);

        final String zoneId = yaml.getString("timezone", DEF_ZONE);
        try {
            this.zone = ZoneId.of(zoneId);
        } catch (final DateTimeException e) {
            log().warning("[GuildWar] Invalid timezone '" + zoneId + "' in " + FILE_NAME
                    + " — falling back to " + DEF_ZONE + ".");
            this.zone = ZoneId.of(DEF_ZONE);
        }

        this.announceGlobally = yaml.getBoolean("announce-globally", DEF_ANNOUNCE);
    }

    int queueTimeoutSeconds() {
        return this.queueTimeoutSeconds;
    }

    String arenaPrefix() {
        return this.arenaPrefix;
    }

    ZoneId zone() {
        return this.zone;
    }

    boolean announceGlobally() {
        return this.announceGlobally;
    }

    private void writeDefaults(final File file) {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header("CyanGuildWar settings. See plans/guildwar/00-plan.md.\n"
                + "queue-timeout-seconds: how long a player waits in the queue before auto-removal.\n"
                + "arena-prefix: arenas whose name starts with this are GuildWar (queue-only) arenas.\n"
                + "timezone: zone whose local midnight (00:00) is the daily lockout reset.\n"
                + "announce-globally: broadcast match start/result to the whole server (else arena-only).");
        yaml.set("queue-timeout-seconds", DEF_TIMEOUT);
        yaml.set("arena-prefix", DEF_PREFIX);
        yaml.set("timezone", DEF_ZONE);
        yaml.set("announce-globally", DEF_ANNOUNCE);
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (final IOException e) {
            log().warning("[GuildWar] Could not write default " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
