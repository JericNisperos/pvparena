package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.managers.ArenaManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Finds and claims a random available {@code guildwar*} arena for a forming challenge.
 *
 * <p>"Available" means: name starts with the configured prefix, not locked, no fight in progress,
 * empty, has at least two teams, and is <b>not already claimed</b> by another active {@link Challenge}.</p>
 */
final class GuildWarArenas {

    /** Gamemode label for arenas with no name suffix (e.g. {@code guildwar1}): a plain Skirmish. */
    static final String DEFAULT_GAMEMODE = "skirmish";

    private static final Random RANDOM = new Random();

    private GuildWarArenas() {
    }

    /** A random available {@code guildwar*} arena of any gamemode, or {@code null} if none is free. */
    static Arena findAvailable() {
        return findAvailable(null);
    }

    /**
     * A random available {@code guildwar*} arena whose gamemode label (see {@link #gamemodeOf}) matches
     * {@code gamemode} ({@code null} = any gamemode), or {@code null} if none is free.
     */
    static Arena findAvailable(final String gamemode) {
        ChallengeRegistry.sweepStale(); // self-heal any leaked claim before picking
        final List<Arena> candidates = new ArrayList<>();
        for (final Arena arena : ArenaManager.getArenas()) {
            if (isAvailable(arena) && (gamemode == null || gamemodeOf(arena).equalsIgnoreCase(gamemode))) {
                candidates.add(arena);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(RANDOM.nextInt(candidates.size()));
    }

    /**
     * The gamemode label encoded in a GuildWar arena's name: the configured
     * {@link GuildWarConfig#arenaPrefix() prefix} and any trailing digits stripped off. So
     * {@code guildwardomination1 -> "domination"}, {@code guildwarbedwars1 -> "bedwars"}, and a plain
     * {@code guildwar1 -> "skirmish"} ({@link #DEFAULT_GAMEMODE}, no suffix). Always lowercase;
     * {@code ""} for non-GuildWar arenas.
     */
    static String gamemodeOf(final Arena arena) {
        if (!isGuildWarArena(arena)) {
            return "";
        }
        String name = arena.getName().toLowerCase(Locale.ROOT);
        final String prefix = GuildWarConfig.get().arenaPrefix();
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        }
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        final String suffix = name.substring(0, end);
        return suffix.isEmpty() ? DEFAULT_GAMEMODE : suffix;
    }

    /** Does at least one configured GuildWar arena carry this gamemode label? (case-insensitive) */
    static boolean gamemodeExists(final String gamemode) {
        if (gamemode == null) {
            return false;
        }
        for (final Arena arena : ArenaManager.getArenas()) {
            if (isGuildWarArena(arena) && gamemodeOf(arena).equalsIgnoreCase(gamemode)) {
                return true;
            }
        }
        return false;
    }

    /** Distinct gamemode labels across ALL configured GuildWar arenas (for listing / tab-complete). */
    static Set<String> allGamemodes() {
        final Set<String> out = new LinkedHashSet<>();
        for (final Arena arena : ArenaManager.getArenas()) {
            if (isGuildWarArena(arena)) {
                out.add(gamemodeOf(arena));
            }
        }
        return out;
    }

    /** Gamemode labels that currently have at least one free (available) arena. */
    static List<String> availableGamemodes() {
        ChallengeRegistry.sweepStale();
        final Set<String> out = new LinkedHashSet<>();
        for (final Arena arena : ArenaManager.getArenas()) {
            if (isAvailable(arena)) {
                out.add(gamemodeOf(arena));
            }
        }
        return new ArrayList<>(out);
    }

    /** A random gamemode that currently has a free arena, or {@code null} if none is free. */
    static String randomAvailableGamemode() {
        final List<String> avail = availableGamemodes();
        return avail.isEmpty() ? null : avail.get(RANDOM.nextInt(avail.size()));
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
