package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent per-guild post-loss cooldowns: the epoch-millis timestamp of each guild's <b>last Guild
 * War loss</b>. Backed by its own flat file {@code plugins/PVPArena/cyan_guildwarchallenge_cooldowns.yml},
 * keyed by guild UUID, so the cooldown survives restarts and {@code /guildwar reinstall}.
 *
 * <p>A guild is "on cooldown" while {@code now < lastLoss + cooldown-hours}. Both the loser issuing a
 * new invite and anyone trying to invite the loser are blocked until it elapses. Lazy/idempotent load
 * mirrors {@link GuildWarResultStore}; main-thread access only.</p>
 */
final class GuildWarCooldownStore {

    private static final String FILE_NAME = "cyan_guildwarchallenge_cooldowns.yml";
    private static final String ROOT = "guilds";

    private static GuildWarCooldownStore instance;

    private File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private boolean loaded;

    static GuildWarCooldownStore get() {
        if (instance == null) {
            instance = new GuildWarCooldownStore();
        }
        instance.ensureLoaded();
        return instance;
    }

    /** Force a fresh read from disk on the next access (e.g. on module enable / reload). */
    void load() {
        this.loaded = false;
        ensureLoaded();
    }

    private void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        this.yaml = this.file.exists()
                ? YamlConfiguration.loadConfiguration(this.file)
                : new YamlConfiguration();
        this.loaded = true;
    }

    /** Epoch millis of the guild's last loss, or {@code 0} if none recorded. */
    long lastLoss(final UUID guildId) {
        return guildId == null ? 0L : this.yaml.getLong(path(guildId), 0L);
    }

    /** Stamp the guild's last loss to {@code whenMillis} (starts/refreshes its cooldown). */
    void recordLoss(final UUID guildId, final long whenMillis) {
        if (guildId == null) {
            return;
        }
        ensureLoaded();
        this.yaml.set(path(guildId), whenMillis);
        save();
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

    private static String path(final UUID guildId) {
        return ROOT + "." + guildId + ".lastLoss";
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
