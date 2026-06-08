package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent per-guild challenge results: {@code wins}, {@code losses}, last-known guild {@code name}
 * and the {@code updated} timestamp of the last recorded result.
 *
 * <p>Backed by its own flat file {@code plugins/PVPArena/cyan_guildwarchallenge.yml}, keyed by guild
 * UUID — independent of the queue module's {@code cyan_guildwar.yml}. <b>No daily lockout</b>;
 * challenge mode only tallies scores. Singleton; main-thread access only, so no synchronization.</p>
 *
 * <p>Loading is <b>lazy and idempotent</b> ({@link #ensureLoaded()}): every {@link #get()} returns a
 * ready-to-use instance, and {@link #save()} never re-reads the file (which previously discarded a
 * just-recorded result). Storing the guild name means {@code /guildwar top} still shows a label even
 * when a guild is offline or UltimateClans can't resolve it.</p>
 */
final class GuildWarResultStore {

    private static final String FILE_NAME = "cyan_guildwarchallenge.yml";
    private static final String ROOT = "guilds";

    private static GuildWarResultStore instance;

    private File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private boolean loaded;

    static GuildWarResultStore get() {
        if (instance == null) {
            instance = new GuildWarResultStore();
        }
        instance.ensureLoaded();
        return instance;
    }

    /** Force a fresh read from disk on the next access (e.g. on module enable / reload). */
    void load() {
        this.loaded = false;
        ensureLoaded();
    }

    /** Read the file once; cheap no-op afterwards. Won't bind until the plugin instance exists. */
    private void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return; // too early — try again on the next access
        }
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        this.yaml = this.file.exists()
                ? YamlConfiguration.loadConfiguration(this.file)
                : new YamlConfiguration();
        this.loaded = true;
    }

    int wins(final UUID guildId) {
        return guildId == null ? 0 : this.yaml.getInt(path(guildId, "wins"), 0);
    }

    int losses(final UUID guildId) {
        return guildId == null ? 0 : this.yaml.getInt(path(guildId, "losses"), 0);
    }

    /** Last-known display name for a guild, or {@code null} if none was ever stored. */
    String name(final UUID guildId) {
        return guildId == null ? null : this.yaml.getString(path(guildId, "name"));
    }

    /**
     * Record a finished challenge: {@code +1 win} for the winner, {@code +1 loss} for the loser, plus
     * each side's last-known name and the update timestamp. Names may be {@code null} (kept as-is).
     */
    void recordResult(final UUID winnerGuild, final String winnerName,
                      final UUID loserGuild, final String loserName) {
        ensureLoaded();
        final long now = System.currentTimeMillis();
        if (winnerGuild != null) {
            this.yaml.set(path(winnerGuild, "wins"), wins(winnerGuild) + 1);
            storeName(winnerGuild, winnerName);
            this.yaml.set(path(winnerGuild, "updated"), now);
        }
        if (loserGuild != null) {
            this.yaml.set(path(loserGuild, "losses"), losses(loserGuild) + 1);
            storeName(loserGuild, loserName);
            this.yaml.set(path(loserGuild, "updated"), now);
        }
        save();
        log().info("[GuildWarChallenge] result recorded: "
                + label(winnerName, winnerGuild) + " (" + wins(winnerGuild) + "W) defeated "
                + label(loserName, loserGuild) + " (" + losses(loserGuild) + "L).");
    }

    ConfigurationSection guildsSection() {
        ensureLoaded();
        ConfigurationSection section = this.yaml.getConfigurationSection(ROOT);
        if (section == null) {
            section = this.yaml.createSection(ROOT);
        }
        return section;
    }

    private void storeName(final UUID guildId, final String name) {
        if (name != null && !name.trim().isEmpty()) {
            this.yaml.set(path(guildId, "name"), name.trim());
        }
    }

    private void save() {
        ensureLoaded();
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
            log().warning("[GuildWarChallenge] Could not save " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static String label(final String name, final UUID guildId) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return guildId == null ? "?" : ("guild " + guildId.toString().substring(0, 8));
    }

    private static String path(final UUID guildId, final String key) {
        return ROOT + "." + guildId + "." + key;
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
