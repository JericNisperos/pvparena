package net.slipcor.pvparena.goals.cyanroundteamlives;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PADeathInfo;
import net.slipcor.pvparena.classes.PASpawn;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Language;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.events.goal.PAGoalEndEvent;
import net.slipcor.pvparena.events.goal.PAGoalPlayerDeathEvent;
import net.slipcor.pvparena.loadables.ArenaGoal;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.loadables.ArenaModuleManager;
import net.slipcor.pvparena.loadables.ModuleType;
import net.slipcor.pvparena.managers.InventoryManager;
import net.slipcor.pvparena.managers.SpawnManager;
import net.slipcor.pvparena.managers.TeleportManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import net.slipcor.pvparena.runnables.EndRunnable;
import net.slipcor.pvparena.runnables.InventoryRefillRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>RoundTeamLives — best-of-N team elimination goal.</pre>
 *
 * Each player gets {@code plives} lives. When every player of a team is out, the surviving team
 * takes the <b>round</b>; everyone is then revived, re-equipped and sent back to their spawns for
 * the next round. First team to {@code rounds} round wins takes the match.
 *
 * <p>The important difference from {@code TeamPlayerLives}: this goal must <b>not</b> extend
 * {@code AbstractPlayerLivesGoal}, because that base class calls {@code handleDeathAndLose()} at
 * zero lives, which teleports the player out of the arena and hands them to the spectate module —
 * irreversible, so a round could never restart. Instead this mirrors {@code GoalLiberation}: an
 * eliminated player is parked at {@link PlayerStatus#DEAD} and handed to the spectate module
 * (FlySpectate), which keeps them on their team and revivable for the next round.</p>
 *
 * <p>Whether a team is out is decided by <b>lives, never status</b> — see
 * {@link #teamsStillInRound()} for why status is not trustworthy here.</p>
 *
 * <p>Round resets never touch {@code Arena.reset()} — that is a full teardown that ejects
 * everyone. This goal does its own soft reset.</p>
 *
 * <p>Requires {@code join.allowRejoin: false} (the core default): leaving the fight or dropping
 * connection is final, and the core then removes the player outright instead of parking them
 * OFFLINE for a comeback.</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit.</p>
 */
public class GoalRoundTeamLives extends ArenaGoal {

    private static final String CFG_LIVES = "goal.roundteamlives.plives";
    private static final String CFG_ROUNDS = "goal.roundteamlives.rounds";
    private static final int DEFAULT_LIVES = 1;
    private static final int DEFAULT_ROUNDS = 3;

    /** Ticks between a team being wiped and the next round starting, so deaths/teleports settle. */
    private static final long ROUND_BREAK_TICKS = 60L;

    /** Guards against a second wipe being processed while a round reset is already scheduled. */
    private boolean roundBreak;

    public GoalRoundTeamLives() {
        super("RoundTeamLives");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    // ---- config: per-arena, read straight off the arena's YAML (no core CFG enum entry needed) ----

    private YamlConfiguration yaml() {
        return this.arena.getConfig().getYamlConfiguration();
    }

    private int livesPerPlayer() {
        return Math.max(1, this.yaml().getInt(CFG_LIVES, DEFAULT_LIVES));
    }

    private int roundsToWin() {
        return Math.max(1, this.yaml().getInt(CFG_ROUNDS, DEFAULT_ROUNDS));
    }

    @Override
    public void setDefaults(final YamlConfiguration config) {
        if (config.get("teams") == null) {
            debug(this.arena, "no teams defined, adding custom red and blue!");
            config.addDefault("teams.red", ChatColor.RED.name());
            config.addDefault("teams.blue", ChatColor.BLUE.name());
        }
        config.addDefault(CFG_LIVES, DEFAULT_LIVES);
        config.addDefault(CFG_ROUNDS, DEFAULT_ROUNDS);
        // Leaving the fight or dropping connection is final in this goal — no coming back mid-series.
        config.addDefault(CFG.JOIN_ALLOW_REJOIN.getNode(), false);
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        return SpawnManager.getMissingTeamSpawn(this.arena, spawns);
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage("lives per player: " + this.livesPerPlayer());
        sender.sendMessage("rounds to win: " + this.roundsToWin());
    }

    // ---- scores -----------------------------------------------------------------------------
    // teamLifeMap holds round wins, so the scoreboard renders the series score for free.

    @Override
    public int getScore(final ArenaTeam arenaTeam) {
        return this.getTeamLifeMap().getOrDefault(arenaTeam, 0);
    }

    @Override
    public int getScore(final ArenaPlayer arenaPlayer) {
        return this.getScore(arenaPlayer.getArenaTeam());
    }

    @Override
    public Map<String, Double> timedEnd(final Map<String, Double> scores) {
        for (final ArenaTeam team : this.arena.getNotEmptyTeams()) {
            scores.merge(team.getName(), (double) this.getScore(team), Double::sum);
        }
        return scores;
    }

    // ---- lifecycle --------------------------------------------------------------------------

    @Override
    public void initiate(final ArenaPlayer arenaPlayer) {
        this.getPlayerLifeMap().put(arenaPlayer, this.livesPerPlayer());
        this.getTeamLifeMap().putIfAbsent(arenaPlayer.getArenaTeam(), 0);
    }

    @Override
    public void parseStart() {
        this.roundBreak = false;
        this.getTeamLifeMap().clear();
        final int lives = this.livesPerPlayer();

        if (this.arena.getConfig().getBoolean(CFG.JOIN_ALLOW_REJOIN)) {
            // The core reconnect path (PlayerListener#onPlayerJoin) restores a returning player
            // straight to FIGHT without consulting the goal, so an eliminated player could rejoin
            // the series. Round bookkeeping below is lives-based and survives it, but the player
            // would still be walking around, so this arena is misconfigured for this goal.
            PVPArena.getInstance().getLogger().warning(String.format(
                    "Arena '%s': %s expects '%s: false' — leaving or disconnecting is meant to be final.",
                    this.arena.getName(), this.getName(), CFG.JOIN_ALLOW_REJOIN.getNode()));
        }

        for (final ArenaTeam team : this.arena.getTeams()) {
            if (team.getTeamMembers().isEmpty()) {
                continue;
            }
            this.getTeamLifeMap().put(team, 0);
            team.getTeamMembers().forEach(ap -> this.getPlayerLifeMap().put(ap, lives));
        }
    }

    @Override
    public void reset(final boolean force) {
        this.roundBreak = false;
        this.getPlayerLifeMap().clear();
        this.getTeamLifeMap().clear();
    }

    @Override
    public void parseLeave(final ArenaPlayer arenaPlayer) {
        if (arenaPlayer == null) {
            PVPArena.getInstance().getLogger().warning(this.getName() + ": player NULL");
            return;
        }
        this.getPlayerLifeMap().remove(arenaPlayer);
        // A leaver can wipe the last standing member of a team. Re-check next tick, once the
        // player is actually off the team.
        if (canSchedule()) {
            Bukkit.getScheduler().runTask(PVPArena.getInstance(), this::checkRoundOver);
        }
    }

    // ---- deaths -----------------------------------------------------------------------------

    @Override
    public Boolean shouldRespawnPlayer(final ArenaPlayer arenaPlayer, final PADeathInfo deathInfo) {
        return this.getPlayerLifeMap().getOrDefault(arenaPlayer, 0) > 1;
    }

    @Override
    public void commitPlayerDeath(final ArenaPlayer arenaPlayer, final boolean doesRespawn,
                                  final PADeathInfo deathInfo) {
        if (!this.getPlayerLifeMap().containsKey(arenaPlayer)) {
            return;
        }

        Bukkit.getPluginManager().callEvent(
                new PAGoalPlayerDeathEvent(this.arena, this, arenaPlayer, deathInfo, doesRespawn));

        final int lives = this.getPlayerLifeMap().get(arenaPlayer);
        final boolean deathMessages = this.arena.getConfig().getBoolean(CFG.USES_DEATHMESSAGES);
        debug(arenaPlayer, "lives before death: " + lives);

        arenaPlayer.setMayDropInventory(true);

        if (lives > 1) {
            final int remaining = lives - 1;
            this.getPlayerLifeMap().put(arenaPlayer, remaining);
            arenaPlayer.setMayRespawn(true);

            if (deathMessages) {
                if (this.arena.getConfig().getBoolean(CFG.GENERAL_SHOWREMAININGLIVES)) {
                    this.broadcastDeathMessage(MSG.FIGHT_KILLED_BY_REMAINING, arenaPlayer, deathInfo, remaining);
                } else {
                    this.broadcastSimpleDeathMessage(arenaPlayer, deathInfo);
                }
            }
        } else {
            // Out for this round. Stay DEAD (still on the team, still revivable) and let
            // parsePlayerDeath park the body — do NOT let the core respawn them.
            this.getPlayerLifeMap().put(arenaPlayer, 0);
            arenaPlayer.setMayRespawn(false);
            arenaPlayer.setStatus(PlayerStatus.DEAD);

            if (deathMessages) {
                this.broadcastSimpleDeathMessage(arenaPlayer, deathInfo);
            }
        }
    }

    @Override
    public void parsePlayerDeath(final ArenaPlayer arenaPlayer, final PADeathInfo deathInfo) {
        if (arenaPlayer.getStatus() != PlayerStatus.DEAD
                || this.getPlayerLifeMap().getOrDefault(arenaPlayer, 1) > 0) {
            return; // still had lives; the core already respawned them
        }

        debug(arenaPlayer, "eliminated for this round - parking until round end");
        InventoryManager.clearInventory(arenaPlayer.getPlayer());
        arenaPlayer.revive(deathInfo); // restores health/hunger/effects; does not touch status

        this.parkAsSpectator(arenaPlayer);
        this.checkRoundOver();
    }

    /**
     * Hand an eliminated player to the spectate module (FlySpectate) for the rest of the round:
     * hidden, flying, non-collidable, unable to hit or interact. Status stays DEAD — only
     * {@code commitSpectate} sets WATCH, and {@code switchToSpectate} does not — so the player
     * stays on their team and {@link #startNextRound()} can pull them back in.
     */
    private void parkAsSpectator(final ArenaPlayer arenaPlayer) {
        final Optional<ArenaModule> spectateMod = this.spectateModule();

        if (spectateMod.isPresent()) {
            spectateMod.get().switchToSpectate(arenaPlayer.getPlayer()); // teleports to spectator spawn
            return;
        }

        // ponytail: no spectate module attached — fall back to a bare teleport so the player at
        // least leaves the battlefield. They stay solid and can walk back in; attach FlySpectate.
        final Set<PASpawn> spectatorSpawns =
                SpawnManager.getPASpawnsStartingWith(this.arena, PASpawn.SPECTATOR);
        if (spectatorSpawns.isEmpty()) {
            PVPArena.getInstance().getLogger().warning(String.format(
                    "Arena '%s': %s has no spectate module and no '%s' spawn — eliminated players "
                            + "are stranded on the battlefield.",
                    this.arena.getName(), this.getName(), PASpawn.SPECTATOR));
            return;
        }
        TeleportManager.teleportPlayerToRandomSpawn(this.arena, arenaPlayer, spectatorSpawns);
    }

    /** Undo {@link #parkAsSpectator}: unhide, ground, make solid again and restore the gamemode. */
    private void returnFromSpectator(final ArenaPlayer arenaPlayer) {
        if (!arenaPlayer.isSpectating()) {
            return;
        }
        final Player player = arenaPlayer.getPlayer();

        // ArenaModule#unload reverses the spectate state: showPlayer, drop the spectator flag that
        // cancels damage/interact, disable flight, restore collision. It does not restore gamemode.
        this.spectateModule().ifPresent(mod -> mod.unload(player));
        arenaPlayer.setSpectating(false);

        try {
            player.setGameMode(this.arena.getConfig().getGameMode(CFG.GENERAL_GAMEMODE));
        } catch (final RuntimeException e) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    /** Lowest-priority attached SPECTATE module, referenced only through the core base type. */
    private Optional<ArenaModule> spectateModule() {
        return this.arena.getMods().stream()
                .filter(mod -> mod.getType() == ModuleType.SPECTATE)
                .min(Comparator.comparingInt(ArenaModule::getPriority));
    }

    // ---- rounds -----------------------------------------------------------------------------

    /**
     * A team is still in the round while any member has lives left.
     *
     * <p>Deliberately lives-based rather than status-based ({@code TeamManager.getActiveTeams()}
     * counts players in FIGHT). Status lies: a player mid-respawn is briefly DEAD with lives to
     * spare, and the core reconnect path restores a returning player to FIGHT without asking the
     * goal — which would otherwise let an eliminated player's team read as alive and hang the
     * round forever. Lives are this goal's own bookkeeping, so they cannot be desynced.</p>
     */
    private Set<ArenaTeam> teamsStillInRound() {
        return this.arena.getNotEmptyTeams().stream()
                .filter(team -> team.getTeamMembers().stream()
                        .anyMatch(ap -> this.getPlayerLifeMap().getOrDefault(ap, 0) > 0))
                .collect(Collectors.toSet());
    }

    /** If exactly one team (or none) still has lives, close out the round. */
    private void checkRoundOver() {
        if (this.roundBreak || !this.arena.isFightInProgress() || this.arena.realEndRunner != null) {
            return;
        }

        final Set<ArenaTeam> aliveTeams = this.teamsStillInRound();
        if (aliveTeams.size() > 1) {
            return; // round still going
        }

        this.roundBreak = true;
        final ArenaTeam winner = aliveTeams.stream().findFirst().orElse(null);

        if (winner == null) {
            this.arena.broadcast(ChatColor.YELLOW + "Round drawn!" + this.seriesScore());
        } else {
            final int wins = this.getTeamLifeMap().merge(winner, 1, Integer::sum);
            this.arena.broadcast(winner.getColoredName() + ChatColor.YELLOW
                    + " wins the round!" + this.seriesScore());

            if (wins >= this.roundsToWin()) {
                this.roundBreak = false;
                WorkflowManager.handleEnd(this.arena, false);
                return;
            }
        }

        this.arena.getScoreboard().refresh();
        if (canSchedule()) {
            Bukkit.getScheduler().runTaskLater(PVPArena.getInstance(), this::startNextRound, ROUND_BREAK_TICKS);
        }
    }

    /**
     * False while the server is stopping. {@code PVPArena.onDisable} resets every arena, which reaches
     * {@code parseLeave}/{@code checkRoundOver} — and the scheduler throws
     * {@code IllegalPluginAccessException} once the plugin is disabled. Nothing deferred is worth
     * running at that point anyway.
     */
    private static boolean canSchedule() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null && !plugin.isShuttingDown();
    }

    /** Soft reset: revive everyone, restore lives, send teams back to their spawns. */
    private void startNextRound() {
        this.roundBreak = false;
        if (!this.arena.isFightInProgress() || this.arena.realEndRunner != null) {
            return;
        }

        final int lives = this.livesPerPlayer();

        for (final ArenaTeam team : this.arena.getTeams()) {
            boolean anyRestored = false;

            for (final ArenaPlayer arenaPlayer : team.getTeamMembers()) {
                final PlayerStatus status = arenaPlayer.getStatus();
                if (status != PlayerStatus.FIGHT && status != PlayerStatus.DEAD) {
                    continue; // left, offline or spectating — not part of the next round
                }
                this.returnFromSpectator(arenaPlayer); // before the teleport below
                this.getPlayerLifeMap().put(arenaPlayer, lives);
                arenaPlayer.setStatus(PlayerStatus.FIGHT); // must precede the refill runnable
                new InventoryRefillRunnable(this.arena, arenaPlayer.getPlayer(), emptyList());
                anyRestored = true;
            }

            if (anyRestored) {
                SpawnManager.distributeTeams(this.arena, team);
            }
        }

        this.arena.broadcast(ChatColor.YELLOW + "Next round — fight!" + this.seriesScore());
        this.arena.getScoreboard().refresh();
    }

    private String seriesScore() {
        final StringBuilder sb = new StringBuilder(ChatColor.GRAY + " (");
        String sep = "";
        for (final ArenaTeam team : this.arena.getNotEmptyTeams()) {
            sb.append(sep).append(team.getColoredName()).append(ChatColor.GRAY)
                    .append(' ').append(this.getScore(team));
            sep = ChatColor.GRAY + " - ";
        }
        return sb.append(ChatColor.GRAY).append('/').append(this.roundsToWin()).append(')').toString();
    }

    // ---- match end --------------------------------------------------------------------------

    @Override
    public boolean checkEnd() {
        // Deliberately does NOT end on "one team left standing" — that is a round, not the match.
        // Round wins are counted in checkRoundOver(); this only reports the match-level verdict,
        // so any other core caller of handleEnd() mid-round is a safe no-op.
        if (matchOver(this.getTeamLifeMap(), this.roundsToWin())) {
            return true;
        }
        return this.arena.getNotEmptyTeams().size() <= 1; // everyone actually left
    }

    @Override
    public void commitEnd(final boolean force) {
        if (this.arena.realEndRunner != null) {
            debug(this.arena, "[ROUNDTEAMLIVES] already ending");
            return;
        }
        debug(this.arena, "[ROUNDTEAMLIVES]");

        Bukkit.getPluginManager().callEvent(new PAGoalEndEvent(this.arena, this));

        final ArenaTeam winner = leader(this.getTeamLifeMap());

        if (winner != null && !force) {
            final String message = Language.parse(MSG.TEAM_HAS_WON,
                    winner.getColoredName() + ChatColor.YELLOW);
            ArenaModuleManager.announce(this.arena, message, "END");
            ArenaModuleManager.announce(this.arena, message, "WINNER");
            this.arena.broadcast(message);
            this.arena.addWinner(winner.getName());
        }

        if (ArenaModuleManager.commitEnd(this.arena, winner, null)) {
            return;
        }
        new EndRunnable(this.arena, this.arena.getConfig().getInt(CFG.TIME_ENDCOUNTDOWN));
    }

    // ---- pure helpers (unit-tested in RoundTeamLivesTest) -------------------------------------

    /** True once any team has banked enough round wins to take the match. */
    static boolean matchOver(final Map<?, Integer> roundWins, final int roundsToWin) {
        return roundWins.values().stream().anyMatch(wins -> wins >= roundsToWin);
    }

    /** The team with the most round wins, or null on an empty map. Ties resolve arbitrarily. */
    static <T> T leader(final Map<T, Integer> roundWins) {
        return roundWins.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
