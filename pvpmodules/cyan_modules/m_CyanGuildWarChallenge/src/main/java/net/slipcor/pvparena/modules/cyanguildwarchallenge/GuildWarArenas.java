package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.managers.ArenaManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Finds and claims a random available {@code guildwar*} arena for a forming challenge.
 *
 * <p>"Available" means: name starts with the configured prefix, not locked, no fight in progress,
 * empty, has at least two teams, and is <b>not already claimed</b> by another active {@link Challenge}.</p>
 */
final class GuildWarArenas {

    private static final Random RANDOM = new Random();

    private GuildWarArenas() {
    }

    /** A random available {@code guildwar*} arena, or {@code null} if none is free. */
    static Arena findAvailable() {
        ChallengeRegistry.sweepStale(); // self-heal any leaked claim before picking
        final List<Arena> candidates = new ArrayList<>();
        for (final Arena arena : ArenaManager.getArenas()) {
            if (isAvailable(arena)) {
                candidates.add(arena);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(RANDOM.nextInt(candidates.size()));
    }

    static boolean isGuildWarArena(final Arena arena) {
        return arena != null && arena.getName() != null
                && arena.getName().toLowerCase(Locale.ROOT).startsWith(GuildWarConfig.get().arenaPrefix());
    }

    private static boolean isAvailable(final Arena arena) {
        if (!isGuildWarArena(arena)) {
            return false;
        }
        if (arena.isLocked() || arena.isFightInProgress()) {
            return false;
        }
        if (!arena.getEveryone().isEmpty()) {
            return false;
        }
        if (ChallengeRegistry.byArena(arena.getName()) != null) {
            return false; // already claimed by a forming/active challenge
        }
        return twoTeams(arena) != null;
    }

    /**
     * The two distinct teams to assign challenger/enemy to. Challenge mode expects a 2-team arena.
     * Returns {@code null} if the arena doesn't have at least two teams.
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

    static Arena byName(final String name) {
        if (name == null) {
            return null;
        }
        for (final Arena arena : ArenaManager.getArenas()) {
            if (name.equalsIgnoreCase(arena.getName())) {
                return arena;
            }
        }
        return null;
    }
}
