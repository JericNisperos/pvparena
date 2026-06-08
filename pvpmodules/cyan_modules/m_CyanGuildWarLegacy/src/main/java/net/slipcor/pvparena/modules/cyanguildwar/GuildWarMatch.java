package net.slipcor.pvparena.modules.cyanguildwar;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A tracked GuildWar match: two players from two distinct guilds, claimed onto one {@code guildwar*}
 * arena. Also serves as the registry of currently-active matches (keyed by arena name) — which
 * doubles as the arena <b>claim</b>: an arena with an entry here is considered taken.
 *
 * <p>The result is recorded by the <b>first</b> resolving signal (win / mid-fight exit), after which
 * {@link #resolved} is set and later signals are ignored. See {@link GuildWarListener}.</p>
 */
final class GuildWarMatch {

    private static final Map<String, GuildWarMatch> ACTIVE = new HashMap<>();

    final String arenaName;
    final UUID player1;
    final UUID guild1;
    final UUID player2;
    final UUID guild2;
    boolean resolved;

    private GuildWarMatch(final String arenaName, final UUID player1, final UUID guild1,
                          final UUID player2, final UUID guild2) {
        this.arenaName = arenaName;
        this.player1 = player1;
        this.guild1 = guild1;
        this.player2 = player2;
        this.guild2 = guild2;
    }

    /** Register a freshly-formed match (claims the arena). */
    static GuildWarMatch open(final String arenaName, final UUID p1, final UUID g1,
                              final UUID p2, final UUID g2) {
        final GuildWarMatch match = new GuildWarMatch(arenaName, p1, g1, p2, g2);
        ACTIVE.put(key(arenaName), match);
        return match;
    }

    static GuildWarMatch forArena(final String arenaName) {
        return arenaName == null ? null : ACTIVE.get(key(arenaName));
    }

    /** Find the unresolved match a given player belongs to (or null). */
    static GuildWarMatch forPlayer(final UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (final GuildWarMatch match : ACTIVE.values()) {
            if (playerId.equals(match.player1) || playerId.equals(match.player2)) {
                return match;
            }
        }
        return null;
    }

    /** Clear the claim/registry entry for an arena (on match end). */
    static void close(final String arenaName) {
        if (arenaName != null) {
            ACTIVE.remove(key(arenaName));
        }
    }

    boolean involves(final UUID playerId) {
        return playerId != null && (playerId.equals(this.player1) || playerId.equals(this.player2));
    }

    /** The guild on the other side from {@code playerId}'s side (the "loser" when that player exits). */
    UUID otherGuild(final UUID playerId) {
        if (playerId == null) {
            return null;
        }
        if (playerId.equals(this.player1)) {
            return this.guild2;
        }
        if (playerId.equals(this.player2)) {
            return this.guild1;
        }
        return null;
    }

    UUID ownGuild(final UUID playerId) {
        if (playerId == null) {
            return null;
        }
        if (playerId.equals(this.player1)) {
            return this.guild1;
        }
        if (playerId.equals(this.player2)) {
            return this.guild2;
        }
        return null;
    }

    private static String key(final String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }
}
