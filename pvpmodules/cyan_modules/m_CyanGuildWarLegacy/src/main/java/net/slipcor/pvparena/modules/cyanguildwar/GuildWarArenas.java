package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.managers.ArenaManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Finds and selects a random available {@code guildwar*} arena for a forming match.
 *
 * <p>"Available" means: name starts with {@link GuildWar#ARENA_PREFIX}, not locked, no fight in
 * progress, completely empty, has at least two teams to assign, and is <b>not already claimed</b> by
 * another active {@link GuildWarMatch}. Selection among the candidates is random.</p>
 */
final class GuildWarArenas {

    private static final Random RANDOM = new Random();

    private GuildWarArenas() {
    }

    /** A random available {@code guildwar*} arena, or {@code null} if none is free. */
    static Arena findAvailable() {
        final List<Arena> candidates = new ArrayList<>();
        for (final Arena arena : ArenaManager.getArenas()) {
            if (isAvailable(arena)) {
                candidates.add(arena);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(RANDOM.nextInt(candidates.size()));
    }

    static boolean isGuildWarArena(final Arena arena) {
        return arena != null && arena.getName() != null
                && arena.getName().toLowerCase(Locale.ROOT).startsWith(GuildWarConfig.get().arenaPrefix());
    }

    private static boolean isAvailable(final Arena arena) {
        if (!isGuildWarArena(arena)) {
            return false;
        }
        if (arena.isLocked()) {
            return false;
        }
        if (arena.isFightInProgress()) {
            return false;
        }
        if (!arena.getEveryone().isEmpty()) {
            return false;
        }
        if (GuildWarMatch.forArena(arena.getName()) != null) {
            return false; // already claimed by a forming/active match
        }
        return twoTeams(arena) != null;
    }

    /**
     * The two distinct teams to assign the matched players to. GuildWar expects a 2-team arena
     * (one slot per team). Returns {@code null} if the arena doesn't have at least two teams.
     */
    static ArenaTeam[] twoTeams(final Arena arena) {
        if (arena == null || arena.isFreeForAll()) {
            return null;
        }
        final List<ArenaTeam> teams = new ArrayList<>(arena.getTeams());
        if (teams.size() < 2) {
            return null;
        }
        return new ArenaTeam[]{teams.get(0), teams.get(1)};
    }
}
