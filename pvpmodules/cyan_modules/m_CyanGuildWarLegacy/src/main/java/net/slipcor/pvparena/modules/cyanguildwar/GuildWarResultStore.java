package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent per-guild GuildWar results: {@code wins}, {@code losses} and {@code lastLossDate}.
 *
 * <p>Backed by a YAML file ({@code plugins/PVPArena/cyan_guildwar.yml}) keyed by guild UUID. The
 * daily lockout needs <b>no scheduler</b>: a guild is locked iff its {@code lastLossDate} equals
 * "today" in the configured zone (default GMT+8). At local 00:00 "today" rolls over, so the lock
 * lifts on its own.</p>
 *
 * <p>Singleton; all access is on the Bukkit main thread (commands + events), so no synchronization
 * is needed. Saved on every change, loaded on enable.</p>
 */
final class GuildWarResultStore {

    private static final String FILE_NAME = "cyan_guildwar.yml";
    private static final String ROOT = "guilds";

    private static GuildWarResultStore instance;

    private File file;
    private YamlConfiguration yaml = new YamlConfiguration();

    static GuildWarResultStore get() {
        if (instance == null) {
            instance = new GuildWarResultStore();
        }
        return instance;
    }

    /** (Re)load the file from disk. Creates the data folder reference lazily. */
    void load() {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        if (this.file.exists()) {
            this.yaml = YamlConfiguration.loadConfiguration(this.file);
        } else {
            this.yaml = new YamlConfiguration();
        }
    }

    /** Today's date in the configured zone — basis for both stamping a loss and testing the lockout. */
    static LocalDate today() {
        return LocalDate.now(GuildWarConfig.get().zone());
    }

    boolean isLockedOut(final UUID guildId) {
        if (guildId == null) {
            return false;
        }
        final String raw = this.yaml.getString(path(guildId, "lastLossDate"), null);
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        try {
            return LocalDate.parse(raw).equals(today());
        } catch (final DateTimeParseException e) {
            return false;
        }
    }

    int wins(final UUID guildId) {
        return guildId == null ? 0 : this.yaml.getInt(path(guildId, "wins"), 0);
    }

    int losses(final UUID guildId) {
        return guildId == null ? 0 : this.yaml.getInt(path(guildId, "losses"), 0);
    }

    /**
     * Record a finished match: {@code +1 win} for the winner, {@code +1 loss} for the loser, and
     * stamp the loser's {@code lastLossDate} = today (which locks them out until 00:00 GMT+8).
     */
    void recordResult(final UUID winnerGuild, final UUID loserGuild) {
        if (winnerGuild != null) {
            this.yaml.set(path(winnerGuild, "wins"), wins(winnerGuild) + 1);
        }
        if (loserGuild != null) {
            this.yaml.set(path(loserGuild, "losses"), losses(loserGuild) + 1);
            this.yaml.set(path(loserGuild, "lastLossDate"), today().toString());
        }
        save();
    }

    /** Iterate stored guild UUIDs (for a future leaderboard). */
    ConfigurationSection guildsSection() {
        ConfigurationSection section = this.yaml.getConfigurationSection(ROOT);
        if (section == null) {
            section = this.yaml.createSection(ROOT);
        }
        return section;
    }

    private void save() {
        if (this.file == null) {
            load();
        }
        if (this.file == null) {
            return;
        }
        try {
            final File parent = this.file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            this.yaml.save(this.file);
        } catch (final IOException e) {
            log().warning("[GuildWar] Could not save " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static String path(final UUID guildId, final String key) {
        return ROOT + "." + guildId + "." + key;
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
