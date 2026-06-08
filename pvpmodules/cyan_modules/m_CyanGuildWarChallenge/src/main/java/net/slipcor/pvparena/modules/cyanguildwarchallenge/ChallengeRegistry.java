package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.arena.Arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registry of currently-active {@link Challenge}s, keyed by arena name — which doubles as the arena
 * <b>claim</b>: an arena with an entry here is taken. Also indexes lookups by guild and by player.
 *
 * <p>Single-threaded (main-thread) access only, so a plain {@link HashMap} is safe.</p>
 */
final class ChallengeRegistry {

    private static final Map<String, Challenge> ACTIVE = new HashMap<>();

    private ChallengeRegistry() {
    }

    static Challenge open(final Challenge challenge) {
        ACTIVE.put(key(challenge.arenaName), challenge);
        return challenge;
    }

    static void close(final String arenaName) {
        if (arenaName != null) {
            ACTIVE.remove(key(arenaName));
        }
    }

    /**
     * Drop challenges whose arena vanished or whose fight ended without us seeing a {@code PAEndEvent}
     * (e.g. a forced {@code /pa stop}, which calls {@code reset(true)} and skips the event). Self-heals
     * a leaked claim so the arena and both guilds aren't stuck "in a war" forever. Cheap — called from
     * the user-facing gates ({@link GuildWarChallenge#challenge} and {@link GuildWarArenas#findAvailable}).
     */
    static void sweepStale() {
        final Iterator<Map.Entry<String, Challenge>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            final Challenge c = it.next().getValue();
            final Arena arena = GuildWarArenas.byName(c.arenaName);
            final boolean gone = arena == null;
            final boolean endedSilently = !gone
                    && c.state == Challenge.State.RUNNING && !arena.isFightInProgress();
            if (gone || endedSilently) {
                c.cancelTasks();
                it.remove();
            }
        }
    }

    static Challenge byArena(final String arenaName) {
        return arenaName == null ? null : ACTIVE.get(key(arenaName));
    }

    /** The active challenge a guild is part of (challenger or enemy), or {@code null}. */
    static Challenge byGuild(final UUID guildId) {
        if (guildId == null) {
            return null;
        }
        for (final Challenge c : ACTIVE.values()) {
            if (c.involvesGuild(guildId)) {
                return c;
            }
        }
        return null;
    }

    /** All challenges currently in the {@link Challenge.State#RUNNING} state (fight underway). */
    static List<Challenge> running() {
        final List<Challenge> out = new ArrayList<>();
        for (final Challenge c : ACTIVE.values()) {
            if (c.state == Challenge.State.RUNNING) {
                out.add(c);
            }
        }
        return out;
    }

    /** The active challenge whose roster contains the player, or {@code null}. */
    static Challenge byPlayer(final UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (final Challenge c : ACTIVE.values()) {
            if (c.involvesPlayer(playerId)) {
                return c;
            }
        }
        return null;
    }

    private static String key(final String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }
}
