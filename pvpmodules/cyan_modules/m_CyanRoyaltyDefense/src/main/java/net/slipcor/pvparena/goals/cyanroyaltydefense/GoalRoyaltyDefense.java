package net.slipcor.pvparena.goals.cyanroyaltydefense;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaClass;
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
import net.slipcor.pvparena.loadables.ArenaModuleManager;
import net.slipcor.pvparena.managers.InventoryManager;
import net.slipcor.pvparena.managers.SpawnManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import net.slipcor.pvparena.runnables.EndRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>RoyaltyDefense — one hidden royal per team, guarded by bodyguards.</pre>
 *
 * At match start each team is randomly assigned a <b>royal</b>. Bodyguards get
 * {@code bodyguardlives} lives each; the royal gets exactly one — their death ends the match for
 * that team no matter how many bodyguards are still standing. Last team with a living royal wins.
 *
 * <p>The royal is drawn at random and cannot be picked. Players still choose their own class in the
 * lobby as usual, but whoever is drawn has that loadout replaced by {@code vipclass} on start —
 * following the same clear/set/equip sequence the lobby class signs use
 * ({@code Arena#chooseClass}).</p>
 *
 * <p>The identity announcement is <b>team-private</b>: only your own team is told who your royal
 * is. The distinctive armour is the tell the enemy has to work for, which is why this goal
 * deliberately does not add a glow effect — that would give it away for free.</p>
 *
 * <p>The royal's death is final, so unlike {@code RoundTeamLives} this goal can use the core
 * {@code handleDeathAndLose()} path — there is no round to come back for.</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit.</p>
 */
public class GoalRoyaltyDefense extends ArenaGoal {

    private static final String CFG_GUARD_LIVES = "goal.royaltydefense.bodyguardlives";
    private static final String CFG_ROYAL_CLASS = "goal.royaltydefense.vipclass";

    private static final int DEFAULT_GUARD_LIVES = 3;
    private static final String DEFAULT_ROYAL_CLASS = "Royalty";

    /** Team -> its living royal. A team drops out of this map when its royal dies. */
    private final Map<ArenaTeam, ArenaPlayer> royals = new HashMap<>();

    public GoalRoyaltyDefense() {
        super("RoyaltyDefense");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    // ---- config ------------------------------------------------------------------------------

    private YamlConfiguration yaml() {
        return this.arena.getConfig().getYamlConfiguration();
    }

    private int bodyguardLives() {
        return Math.max(1, this.yaml().getInt(CFG_GUARD_LIVES, DEFAULT_GUARD_LIVES));
    }

    private String royalClassName() {
        return this.yaml().getString(CFG_ROYAL_CLASS, DEFAULT_ROYAL_CLASS);
    }

    @Override
    public void setDefaults(final YamlConfiguration config) {
        if (config.get("teams") == null) {
            debug(this.arena, "no teams defined, adding custom red and blue!");
            config.addDefault("teams.red", ChatColor.RED.name());
            config.addDefault("teams.blue", ChatColor.BLUE.name());
        }
        config.addDefault(CFG_GUARD_LIVES, DEFAULT_GUARD_LIVES);
        config.addDefault(CFG_ROYAL_CLASS, DEFAULT_ROYAL_CLASS);
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage("bodyguard lives: " + this.bodyguardLives());
        sender.sendMessage("royal lives: 1 (death ends the match for that team)");
        sender.sendMessage("royal class: " + this.royalClassName());
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        return SpawnManager.getMissingTeamSpawn(this.arena, spawns);
    }

    // ---- scores ------------------------------------------------------------------------------

    @Override
    public int getScore(final ArenaTeam arenaTeam) {
        return arenaTeam.getTeamMembers().stream()
                .mapToInt(ap -> this.getPlayerLifeMap().getOrDefault(ap, 0))
                .sum();
    }

    @Override
    public int getScore(final ArenaPlayer arenaPlayer) {
        return this.getScore(arenaPlayer.getArenaTeam());
    }

    // ---- setup -------------------------------------------------------------------------------

    @Override
    public void initiate(final ArenaPlayer arenaPlayer) {
        this.getPlayerLifeMap().put(arenaPlayer, this.bodyguardLives());
    }

    @Override
    public void parseStart() {
        this.royals.clear();
        final int guardLives = this.bodyguardLives();

        for (final ArenaTeam team : this.arena.getNotEmptyTeams()) {
            team.getTeamMembers().forEach(ap -> this.getPlayerLifeMap().put(ap, guardLives));
            this.crownRoyal(team, null);
        }
    }

    @Override
    public void reset(final boolean force) {
        this.royals.clear();
        this.getPlayerLifeMap().clear();
    }

    /**
     * Draw a random living member of the team (skipping {@code exclude}), give them the royal
     * class and tell only their own team.
     */
    private void crownRoyal(final ArenaTeam team, final ArenaPlayer exclude) {
        final List<ArenaPlayer> candidates = new ArrayList<>();
        for (final ArenaPlayer arenaPlayer : team.getTeamMembers()) {
            if (arenaPlayer != exclude
                    && arenaPlayer.getStatus() == PlayerStatus.FIGHT
                    && arenaPlayer.getPlayer() != null) {
                candidates.add(arenaPlayer);
            }
        }

        if (candidates.isEmpty()) {
            this.royals.remove(team);
            return;
        }

        final ArenaPlayer royal = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        this.royals.put(team, royal);
        this.getPlayerLifeMap().put(royal, 1); // the royal always has exactly one life
        this.applyRoyalClass(royal);

        // Team-private: the enemy has to identify the royal by their armour, not by chat.
        final String announcement = ChatColor.GOLD + "Your royal is "
                + team.colorizePlayer(royal) + ChatColor.GOLD + " — protect them!";
        for (final ArenaPlayer member : team.getTeamMembers()) {
            final Player player = member.getPlayer();
            if (player == null) {
                continue;
            }
            player.sendMessage(member.equals(royal)
                    ? ChatColor.GOLD + "You are the royal! Stay alive — your team loses if you fall."
                    : announcement);
        }
    }

    /** Replace the lobby-chosen loadout, mirroring the sequence used by {@code Arena#chooseClass}. */
    private void applyRoyalClass(final ArenaPlayer royal) {
        final String className = this.royalClassName();
        final ArenaClass royalClass = this.arena.getArenaClass(className);

        if (royalClass == null) {
            PVPArena.getInstance().getLogger().warning(String.format(
                    "Arena '%s': %s class '%s' does not exist — the royal keeps their lobby class.",
                    this.arena.getName(), this.getName(), className));
            return;
        }

        final Player player = royal.getPlayer();
        InventoryManager.clearInventory(player);
        royal.setArenaClass(royalClass);
        royal.equipPlayerFightItems();
    }

    private boolean isRoyal(final ArenaPlayer arenaPlayer) {
        return arenaPlayer != null && arenaPlayer.equals(this.royals.get(arenaPlayer.getArenaTeam()));
    }

    // ---- deaths -----------------------------------------------------------------------------

    @Override
    public Boolean shouldRespawnPlayer(final ArenaPlayer arenaPlayer, final PADeathInfo deathInfo) {
        if (this.isRoyal(arenaPlayer)) {
            return false; // the royal never comes back
        }
        return this.getPlayerLifeMap().getOrDefault(arenaPlayer, 0) > 1;
    }

    @Override
    public void commitPlayerDeath(final ArenaPlayer arenaPlayer, final boolean doesRespawn,
                                  final PADeathInfo deathInfo) {
        Bukkit.getPluginManager().callEvent(
                new PAGoalPlayerDeathEvent(this.arena, this, arenaPlayer, deathInfo, doesRespawn));

        final boolean deathMessages = this.arena.getConfig().getBoolean(CFG.USES_DEATHMESSAGES);
        arenaPlayer.setMayDropInventory(true);

        if (this.isRoyal(arenaPlayer)) {
            final ArenaTeam team = arenaPlayer.getArenaTeam();
            this.royals.remove(team);
            this.getPlayerLifeMap().put(arenaPlayer, 0);
            arenaPlayer.setMayRespawn(false);

            this.arena.broadcast(ChatColor.GOLD + "The " + team.getColoredName() + ChatColor.GOLD
                    + " royal has fallen!");

            arenaPlayer.handleDeathAndLose(deathInfo);
            WorkflowManager.handleEnd(this.arena, false);
            return;
        }

        final int lives = this.getPlayerLifeMap().getOrDefault(arenaPlayer, 0);

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
            // Bodyguard is spent — out for good, but the team survives while the royal lives.
            this.getPlayerLifeMap().put(arenaPlayer, 0);
            arenaPlayer.setMayRespawn(false);
            arenaPlayer.handleDeathAndLose(deathInfo);
        }
    }

    @Override
    public void parseLeave(final ArenaPlayer arenaPlayer) {
        if (arenaPlayer == null) {
            PVPArena.getInstance().getLogger().warning(this.getName() + ": player NULL");
            return;
        }
        this.getPlayerLifeMap().remove(arenaPlayer);

        if (!this.isRoyal(arenaPlayer)) {
            return;
        }

        final ArenaTeam team = arenaPlayer.getArenaTeam();
        this.royals.remove(team);

        // Crown a surviving bodyguard next tick, once the leaver is off the roster. Forfeiting
        // outright would let one quitter throw the match for their whole team.
        // Skipped while the server stops: PVPArena.onDisable resets every arena, and scheduling
        // against a disabled plugin throws IllegalPluginAccessException.
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null || plugin.isShuttingDown()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!this.arena.isFightInProgress() || this.arena.realEndRunner != null) {
                return;
            }
            this.arena.broadcast(ChatColor.GOLD + "The " + team.getColoredName() + ChatColor.GOLD
                    + " royal abandoned the field!");
            this.crownRoyal(team, arenaPlayer);
            WorkflowManager.handleEnd(this.arena, false);
        });
    }

    @Override
    public void unload(final ArenaPlayer arenaPlayer) {
        super.unload(arenaPlayer);
        Optional.ofNullable(arenaPlayer)
                .map(ArenaPlayer::getArenaTeam)
                .ifPresent(team -> {
                    if (arenaPlayer.equals(this.royals.get(team))) {
                        this.royals.remove(team);
                    }
                });
    }

    // ---- end --------------------------------------------------------------------------------

    @Override
    public boolean checkEnd() {
        return this.royals.size() <= 1;
    }

    @Override
    public void commitEnd(final boolean force) {
        if (this.arena.realEndRunner != null) {
            debug(this.arena, "[ROYALTYDEFENSE] already ending");
            return;
        }
        debug(this.arena, "[ROYALTYDEFENSE]");

        Bukkit.getPluginManager().callEvent(new PAGoalEndEvent(this.arena, this));

        final ArenaTeam winner = this.royals.keySet().stream().findFirst().orElse(null);

        if (winner != null && !force) {
            final String message = Language.parse(MSG.TEAM_HAS_WON, winner.getColoredName() + ChatColor.YELLOW);
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

    @Override
    public Map<String, Double> timedEnd(final Map<String, Double> scores) {
        // A surviving royal outweighs any amount of bodyguard attrition.
        for (final ArenaTeam team : this.arena.getNotEmptyTeams()) {
            final double score = (this.royals.containsKey(team) ? 1000.0 : 0.0) + this.getScore(team);
            scores.merge(team.getName(), score, Double::sum);
        }
        return scores;
    }
}
