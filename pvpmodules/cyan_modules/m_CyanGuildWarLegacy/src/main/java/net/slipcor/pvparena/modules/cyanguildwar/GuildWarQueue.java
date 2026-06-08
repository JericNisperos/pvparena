package net.slipcor.pvparena.modules.cyanguildwar;

import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The global GuildWar matchmaking queue: a guild-aware map of waiting players.
 *
 * <p>Insertion-ordered ({@link LinkedHashMap}) so pairing is roughly first-come-first-served. All
 * access is on the Bukkit main thread, so no synchronization is needed. The queue is purely
 * in-memory — a restart loses waiting players (acceptable; scores/lockouts persist in
 * {@link GuildWarResultStore}).</p>
 */
final class GuildWarQueue {

    /** One waiting player's state. */
    static final class Entry {
        final UUID playerId;
        final UUID guildId;
        final long joinedAt;
        BukkitTask timeoutTask;

        Entry(final UUID playerId, final UUID guildId) {
            this.playerId = playerId;
            this.guildId = guildId;
            this.joinedAt = System.currentTimeMillis();
        }
    }

    private static GuildWarQueue instance;

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    static GuildWarQueue get() {
        if (instance == null) {
            instance = new GuildWarQueue();
        }
        return instance;
    }

    boolean contains(final UUID playerId) {
        return this.entries.containsKey(playerId);
    }

    /** Add a player to the queue. Caller is responsible for setting the timeout task afterwards. */
    Entry add(final UUID playerId, final UUID guildId) {
        final Entry entry = new Entry(playerId, guildId);
        this.entries.put(playerId, entry);
        return entry;
    }

    /** Remove a player and cancel their pending timeout (if any). Returns the removed entry or null. */
    Entry remove(final UUID playerId) {
        final Entry entry = this.entries.remove(playerId);
        if (entry != null && entry.timeoutTask != null) {
            entry.timeoutTask.cancel();
        }
        return entry;
    }

    /**
     * Find the oldest pair of queued players belonging to two <b>distinct</b> guilds. The first
     * entry (oldest) is paired with the oldest entry from any other guild. Returns {@code null} if no
     * such pair exists (e.g. everyone queued is from the same guild).
     */
    Entry[] findDistinctGuildPair() {
        final List<Entry> ordered = new ArrayList<>(this.entries.values());
        for (int i = 0; i < ordered.size(); i++) {
            final Entry first = ordered.get(i);
            for (int j = i + 1; j < ordered.size(); j++) {
                final Entry second = ordered.get(j);
                if (!first.guildId.equals(second.guildId)) {
                    return new Entry[]{first, second};
                }
            }
        }
        return null;
    }

    int size() {
        return this.entries.size();
    }
}
