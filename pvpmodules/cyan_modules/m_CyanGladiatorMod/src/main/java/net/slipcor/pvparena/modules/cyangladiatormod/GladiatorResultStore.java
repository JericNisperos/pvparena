package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent per-guild Gladiator results: {@code wins}, {@code losses}, last-known guild {@code name}
 * and the {@code updated} timestamp; plus per-player tallies.
 *
 * <p>Backed by its own flat file {@code plugins/PVPArena/cyan_gladiator.yml}, keyed by guild UUID.
 * Singleton; main-thread access only, so no synchronization. Loading is lazy and idempotent
 * ({@link #ensureLoaded()}); {@link #save()} never re-reads the file (which would discard a
 * just-recorded result). The stored name lets {@code /gladiator top} show a label even when a guild
 * is offline or UltimateClans can't resolve it.</p>
 */
final class GladiatorResultStore {

    private static final String FILE_NAME = "cyan_gladiator.yml";
    private static final String ROOT = "guilds";
    private static final String PLAYER_ROOT = "players";

    private static GladiatorResultStore instance;

    private File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private boolean loaded;

    static GladiatorResultStore get() {
        if (instance == null) {
            instance = new GladiatorResultStore();
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

    int playerWins(final UUID playerId) {
        return playerId == null ? 0 : this.yaml.getInt(playerPath(playerId, "wins"), 0);
    }

    int playerLosses(final UUID playerId) {
        return playerId == null ? 0 : this.yaml.getInt(playerPath(playerId, "losses"), 0);
    }

    /**
     * Record a finished rumble: {@code +1 win} for the surviving guild and each of its participants;
     * {@code +1 loss} for every other participating guild and each of <i>their</i> participants. Names
     * may be {@code null} (kept as-is); participant sets may be empty.
     *
     * @param winnerGuild    the surviving guild (may be {@code null} if unattributable — then no-op)
     * @param winnerName     last-known display name for the winner
     * @param winnerPlayers  the winner's fighters
     * @param losers         every other guild that took part → (name, fighters)
     */
    void recordMatch(final UUID winnerGuild, final String winnerName, final Set<UUID> winnerPlayers,
                     final Map<UUID, LoserEntry> losers) {
        ensureLoaded();
        final long now = System.currentTimeMillis();
        if (winnerGuild != null) {
            this.yaml.set(path(winnerGuild, "wins"), wins(winnerGuild) + 1);
            storeName(winnerGuild, winnerName);
            this.yaml.set(path(winnerGuild, "updated"), now);
            bumpPlayers(winnerPlayers, "wins", now);
        }
        if (losers != null) {
            for (final Map.Entry<UUID, LoserEntry> entry : losers.entrySet()) {
                final UUID loserGuild = entry.getKey();
                if (loserGuild == null || loserGuild.equals(winnerGuild)) {
                    continue;
                }
                this.yaml.set(path(loserGuild, "losses"), losses(loserGuild) + 1);
                storeName(loserGuild, entry.getValue() != null ? entry.getValue().name : null);
                this.yaml.set(path(loserGuild, "updated"), now);
                bumpPlayers(entry.getValue() != null ? entry.getValue().players : null, "losses", now);
            }
        }
        save();
        log().info("[Gladiator] result recorded: " + label(winnerName, winnerGuild)
                + " (" + wins(winnerGuild) + "W) won the rumble over "
                + (losers == null ? 0 : losers.size()) + " other guild(s).");
    }

    private void bumpPlayers(final Set<UUID> players, final String key, final long now) {
        if (players == null) {
            return;
        }
        for (final UUID id : players) {
            if (id == null) {
                continue;
            }
            this.yaml.set(playerPath(id, key), this.yaml.getInt(playerPath(id, key), 0) + 1);
            this.yaml.set(playerPath(id, "updated"), now);
        }
    }

    /** Guilds with at least one recorded result, ranked by wins (desc), ties broken by fewer losses. */
    List<Standing> rankedByWins() {
        ensureLoaded();
        final List<Standing> rows = new ArrayList<>();
        final ConfigurationSection section = guildsSection();
        for (final String key : section.getKeys(false)) {
            final UUID id = parseUuid(key);
            if (id == null) {
                continue;
            }
            final int w = section.getInt(key + ".wins", 0);
            final int l = section.getInt(key + ".losses", 0);
            if (w == 0 && l == 0) {
                continue;
            }
            rows.add(new Standing(id, section.getString(key + ".name"), w, l));
        }
        rows.sort(Comparator.comparingInt((Standing s) -> s.wins).reversed()
                .thenComparingInt(s -> s.losses));
        return rows;
    }

    private ConfigurationSection guildsSection() {
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
            log().warning("[Gladiator] Could not save " + FILE_NAME + ": " + e.getMessage());
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

    private static String playerPath(final UUID playerId, final String key) {
        return PLAYER_ROOT + "." + playerId + "." + key;
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** One losing guild's name + fighters, passed to {@link #recordMatch}. */
    static final class LoserEntry {
        final String name;
        final Set<UUID> players;

        LoserEntry(final String name, final Set<UUID> players) {
            this.name = name;
            this.players = players;
        }
    }

    /** One guild's standing for the leaderboard / placeholders. */
    static final class Standing {
        final UUID guildId;
        final String name;
        final int wins;
        final int losses;

        Standing(final UUID guildId, final String name, final int wins, final int losses) {
            this.guildId = guildId;
            this.name = name;
            this.wins = wins;
            this.losses = losses;
        }
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
