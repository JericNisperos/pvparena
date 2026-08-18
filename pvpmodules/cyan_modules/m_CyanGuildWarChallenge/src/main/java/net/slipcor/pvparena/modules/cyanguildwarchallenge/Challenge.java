package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One guild-vs-guild challenge: challenger guild (side A) vs enemy guild (side B), each filling a
 * roster of {@code count} players on one {@code guildwar*} arena.
 *
 * <p>A live entry in {@link ChallengeRegistry} (keyed by arena name) also serves as the arena
 * <b>claim</b>. All access is on the Bukkit main thread, so the mutable fields need no
 * synchronization. The state machine: {@link State#PENDING} → {@link State#STAGING} →
 * {@link State#COUNTDOWN} → {@link State#RUNNING} → {@link State#ENDED} (or {@link State#CANCELLED}).</p>
 */
final class Challenge {

    /**
     * PENDING (awaiting accept) → STAGING (filling rosters, players free in the world) →
     * COUNTDOWN (both full: short "teleporting in Ns" warning, still free) →
     * LOUNGE (teleported into the arena lounge: in-lounge countdown before the fight) →
     * RUNNING → ENDED (or CANCELLED at any pre-fight point).
     */
    enum State { PENDING, STAGING, COUNTDOWN, LOUNGE, RUNNING, ENDED, CANCELLED }

    final String arenaName;
    final UUID guildA;       // challenger
    final UUID guildB;       // enemy
    final String teamAName;
    final String teamBName;
    final int count;
    final UUID challengerId;
    /** Gamemode label this war is fought under (the arena-name suffix; see {@link GuildWarArenas#gamemodeOf}). */
    final String gamemode;

    final Set<UUID> rosterA = new LinkedHashSet<>();
    final Set<UUID> rosterB = new LinkedHashSet<>();

    State state = State.PENDING;
    boolean resolved;

    BukkitTask acceptTask;
    BukkitTask stagingTask;
    GuildWarCountdown countdown;

    Challenge(final String arenaName, final UUID guildA, final UUID guildB,
              final String teamAName, final String teamBName, final int count, final UUID challengerId,
              final String gamemode) {
        this.arenaName = arenaName;
        this.guildA = guildA;
        this.guildB = guildB;
        this.teamAName = teamAName;
        this.teamBName = teamBName;
        this.count = count;
        this.challengerId = challengerId;
        this.gamemode = gamemode;
    }

    /** {@code 'A'} if the guild is the challenger, {@code 'B'} if the enemy, else {@code 0}. */
    char sideOfGuild(final UUID guildId) {
        if (guildId == null) {
            return 0;
        }
        if (guildId.equals(this.guildA)) {
            return 'A';
        }
        if (guildId.equals(this.guildB)) {
            return 'B';
        }
        return 0;
    }

    char sideOfPlayer(final UUID playerId) {
        if (this.rosterA.contains(playerId)) {
            return 'A';
        }
        if (this.rosterB.contains(playerId)) {
            return 'B';
        }
        return 0;
    }

    Set<UUID> roster(final char side) {
        return side == 'A' ? this.rosterA : this.rosterB;
    }

    UUID guild(final char side) {
        return side == 'A' ? this.guildA : this.guildB;
    }

    String teamName(final char side) {
        return side == 'A' ? this.teamAName : this.teamBName;
    }

    boolean involvesGuild(final UUID guildId) {
        return sideOfGuild(guildId) != 0;
    }

    boolean involvesPlayer(final UUID playerId) {
        return playerId != null && (this.rosterA.contains(playerId) || this.rosterB.contains(playerId));
    }

    boolean bothFull() {
        return this.rosterA.size() >= this.count && this.rosterB.size() >= this.count;
    }

    void cancelTasks() {
        if (this.acceptTask != null) {
            this.acceptTask.cancel();
            this.acceptTask = null;
        }
        if (this.stagingTask != null) {
            this.stagingTask.cancel();
            this.stagingTask = null;
        }
        if (this.countdown != null) {
            this.countdown.stop();
            this.countdown = null;
        }
    }
}
