package net.slipcor.pvparena.goals.cyanzonecapture;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PABlock;
import net.slipcor.pvparena.classes.PABlockLocation;
import net.slipcor.pvparena.classes.PAClaimBar;
import net.slipcor.pvparena.classes.PASpawn;
import net.slipcor.pvparena.commands.CommandTree;
import net.slipcor.pvparena.commands.PAA_Region;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Language;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.core.Utils;
import net.slipcor.pvparena.events.goal.PAGoalEndEvent;
import net.slipcor.pvparena.loadables.ArenaGoal;
import net.slipcor.pvparena.loadables.ArenaModuleManager;
import net.slipcor.pvparena.managers.PermissionManager;
import net.slipcor.pvparena.managers.SpawnManager;
import net.slipcor.pvparena.managers.TeamManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import net.slipcor.pvparena.runnables.EndRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>ZoneCapture — stand in the enemy zone to take it; capturing one wins the match.</pre>
 *
 * Every team owns one zone, marked by a {@code beacon} block plus a claim radius. Enemies standing
 * inside an undefended zone fill a capture timer; when it completes, the attacking team wins
 * outright. A single defender of the owning team, or a second attacking team, stalls the capture.
 *
 * <p>Deaths are not tracked at all — players respawn indefinitely and the only way to win is to
 * take a zone (or to be the last team with anyone left). That is deliberate: lives and capture are
 * two different win conditions, and stacking them makes the mode hard to read.</p>
 *
 * <p>Block naming is {@code beacon} rather than {@code zone} on purpose: it is one of the two names
 * {@code CircleParticleRunnable} recognises, so the particle ring stays available for free.</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit.</p>
 */
public class GoalZoneCapture extends ArenaGoal {

    private static final String BEACON = "beacon";
    private static final String ZONE_CMD = "zone";

    private static final String CFG_RANGE = "goal.zonecapture.claimrange";
    private static final String CFG_CAPTURE_TICKS = "goal.zonecapture.captureticks";
    private static final String CFG_TICK_INTERVAL = "goal.zonecapture.tickinterval";
    private static final String CFG_BOSSBAR = "goal.zonecapture.bossbar";

    private static final int DEFAULT_RANGE = 3;
    private static final int DEFAULT_CAPTURE_TICKS = 100; // 5s
    private static final int DEFAULT_TICK_INTERVAL = 20;  // 1s

    /** Zone owner -> ticks the current attacker has held it. */
    private final Map<ArenaTeam, Integer> progress = new HashMap<>();
    /** Zone owner -> the team currently capturing it, if any. */
    private final Map<ArenaTeam, ArenaTeam> attackers = new HashMap<>();
    private final Map<ArenaTeam, PAClaimBar> bars = new HashMap<>();

    private ArenaTeam capturedBy;
    private BukkitTask ticker;

    /** Team name held between the {@code zone set <team>} command and the block click. */
    private String blockTeamName;

    public GoalZoneCapture() {
        super("ZoneCapture");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public boolean allowsJoinInBattle() {
        return this.arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH);
    }

    // ---- config ------------------------------------------------------------------------------

    private YamlConfiguration yaml() {
        return this.arena.getConfig().getYamlConfiguration();
    }

    private int claimRange() {
        return Math.max(1, this.yaml().getInt(CFG_RANGE, DEFAULT_RANGE));
    }

    private int captureTicks() {
        return Math.max(1, this.yaml().getInt(CFG_CAPTURE_TICKS, DEFAULT_CAPTURE_TICKS));
    }

    private int tickInterval() {
        return Math.max(1, this.yaml().getInt(CFG_TICK_INTERVAL, DEFAULT_TICK_INTERVAL));
    }

    @Override
    public void setDefaults(final YamlConfiguration config) {
        if (config.get("teams") == null) {
            debug(this.arena, "no teams defined, adding custom red and blue!");
            config.addDefault("teams.red", ChatColor.RED.name());
            config.addDefault("teams.blue", ChatColor.BLUE.name());
        }
        config.addDefault(CFG_RANGE, DEFAULT_RANGE);
        config.addDefault(CFG_CAPTURE_TICKS, DEFAULT_CAPTURE_TICKS);
        config.addDefault(CFG_TICK_INTERVAL, DEFAULT_TICK_INTERVAL);
        config.addDefault(CFG_BOSSBAR, true);
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage("claim range: " + this.claimRange());
        sender.sendMessage("capture ticks: " + this.captureTicks());
        sender.sendMessage("tick interval: " + this.tickInterval());
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        return SpawnManager.getMissingTeamSpawn(this.arena, spawns);
    }

    @Override
    public Set<PABlock> checkForMissingBlocks(final Set<PABlock> blocks) {
        // every team needs its own beacon to defend
        return SpawnManager.getMissingBlocksTeamCustom(this.arena, blocks, BEACON);
    }

    // ---- setup command: /pa <arena> zone set|remove <team> -------------------------------------

    @Override
    public boolean checkCommand(final String string) {
        return ZONE_CMD.equalsIgnoreCase(string);
    }

    @Override
    public List<String> getGoalCommands() {
        return Collections.singletonList(ZONE_CMD);
    }

    @Override
    public CommandTree<String> getGoalSubCommands(final Arena arena) {
        final CommandTree<String> result = new CommandTree<>(null);
        arena.getTeamNames().forEach(teamName -> {
            result.define(new String[]{"set", teamName});
            result.define(new String[]{"remove", teamName});
        });
        return result;
    }

    @Override
    public void commitCommand(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            this.arena.msg(sender, MSG.ERROR_INVALID_ARGUMENT_COUNT, String.valueOf(args.length), "3");
            return;
        }

        final ArenaTeam team = this.arena.getTeam(args[2]);
        if (team == null) {
            this.arena.msg(sender, MSG.ERROR_TEAM_NOT_FOUND, args[2]);
            return;
        }

        if ("set".equalsIgnoreCase(args[1])) {
            this.blockTeamName = team.getName();
            PAA_Region.activeSelections.put(sender.getName(), this.arena);
            sender.sendMessage(ChatColor.YELLOW + "Click a block to set the "
                    + team.getColoredName() + ChatColor.YELLOW + " zone.");

        } else if ("remove".equalsIgnoreCase(args[1])) {
            final Optional<PABlock> paBlock = this.arena.getBlocks().stream()
                    .filter(b -> team.getName().equalsIgnoreCase(b.getTeamName())
                            && b.getName().equalsIgnoreCase(BEACON))
                    .findAny();

            if (paBlock.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No zone set for team " + team.getName() + ".");
                return;
            }
            SpawnManager.removeBlock(this.arena, paBlock.get());
            sender.sendMessage(ChatColor.YELLOW + "Zone removed for team " + team.getName() + ".");

        } else {
            this.blockTeamName = null;
            this.arena.msg(sender, MSG.ERROR_COMMAND_INVALID, String.join(" ", ZONE_CMD, args[1]));
        }
    }

    @Override
    public boolean checkSetBlock(final Player player, final Block block) {
        return block != null
                && this.blockTeamName != null
                && PAA_Region.activeSelections.containsKey(player.getName());
    }

    @Override
    public boolean commitSetBlock(final Player player, final Block block) {
        if (!PermissionManager.hasAdminPerm(player) && !PermissionManager.hasBuilderPerm(player, this.arena)) {
            return false;
        }

        SpawnManager.setBlock(this.arena, new PABlockLocation(block.getLocation()), BEACON, this.blockTeamName);
        player.sendMessage(ChatColor.YELLOW + "Zone set for team " + this.blockTeamName + ".");

        PAA_Region.activeSelections.remove(player.getName());
        this.blockTeamName = null;
        return true;
    }

    // ---- match ------------------------------------------------------------------------------

    @Override
    public void parseStart() {
        // Defensive: an aborted match can leave the previous ticker running and its boss bars on
        // screen. Two tickers would double every capture tick and broadcast the capture twice.
        this.stopTicking();
        this.capturedBy = null;

        final int interval = this.tickInterval();
        final ZoneRunnable runnable = new ZoneRunnable(this);
        this.ticker = runnable.runTaskTimer(PVPArena.getInstance(), interval, interval);
    }

    @Override
    public void reset(final boolean force) {
        this.capturedBy = null;
        this.stopTicking();
    }

    /** Tear down the timer, bars and in-flight progress, but leave {@link #capturedBy} alone. */
    private void stopTicking() {
        this.progress.clear();
        this.attackers.clear();
        this.bars.values().forEach(PAClaimBar::stop);
        this.bars.clear();
        if (this.ticker != null) {
            this.ticker.cancel();
            this.ticker = null;
        }
    }

    /** One capture tick: advance, stall or reset every zone. */
    void checkZones() {
        if (this.capturedBy != null || !this.arena.isFightInProgress() || this.arena.realEndRunner != null) {
            return;
        }

        final int range = this.claimRange();

        for (final ArenaTeam owner : this.arena.getNotEmptyTeams()) {
            final PABlockLocation beacon = SpawnManager.getBlockByExactName(this.arena, BEACON, owner.getName());
            if (beacon == null) {
                continue;
            }

            final Location center = Utils.getCenteredLocation(beacon.toLocation());
            final Set<ArenaTeam> present = this.teamsNear(center, range);
            final boolean defended = present.contains(owner);
            final Set<ArenaTeam> attacking = new HashSet<>(present);
            attacking.remove(owner);

            if (!advancesCapture(defended, attacking.size())) {
                this.clearCapture(owner);
                continue;
            }

            final ArenaTeam attacker = attacking.iterator().next();
            if (this.attackers.get(owner) != attacker) {
                // a different team took over the zone — start their timer from scratch
                this.clearCapture(owner);
                this.attackers.put(owner, attacker);
                this.arena.broadcast(attacker.getColoredName() + ChatColor.YELLOW
                        + " is capturing the " + owner.getColoredName() + ChatColor.YELLOW + " zone!");
                this.startBar(owner, center, attacker, range);
            }

            final int held = this.progress.merge(owner, this.tickInterval(), Integer::sum);
            if (held >= this.captureTicks()) {
                this.capturedBy = attacker;
                this.arena.broadcast(attacker.getColoredName() + ChatColor.YELLOW
                        + " captured the " + owner.getColoredName() + ChatColor.YELLOW + " zone!");
                this.stopTicking(); // must not clear capturedBy — commitEnd reads it
                WorkflowManager.handleEnd(this.arena, false);
                return;
            }
        }
    }

    /**
     * A capture only advances for exactly one attacking team in an undefended zone. One defender
     * holds the zone indefinitely, and two attacking teams stall each other.
     */
    static boolean advancesCapture(final boolean defenderPresent, final int attackingTeams) {
        return !defenderPresent && attackingTeams == 1;
    }

    private void clearCapture(final ArenaTeam owner) {
        this.progress.remove(owner);
        this.attackers.remove(owner);
        Optional.ofNullable(this.bars.remove(owner)).ifPresent(PAClaimBar::stop);
    }

    private void startBar(final ArenaTeam owner, final Location center, final ArenaTeam attacker, final int range) {
        if (!this.yaml().getBoolean(CFG_BOSSBAR, true)) {
            return;
        }
        final String title = "Capturing " + owner.getName() + " zone";
        this.bars.put(owner, new PAClaimBar(this.arena, title, attacker.getColor(), center, range, this.captureTicks()));
    }

    private Set<ArenaTeam> teamsNear(final Location center, final int range) {
        final Set<ArenaTeam> result = new HashSet<>();
        for (final ArenaPlayer arenaPlayer : this.arena.getFighters()) {
            if (arenaPlayer.getStatus() != PlayerStatus.FIGHT || arenaPlayer.getPlayer() == null) {
                continue;
            }
            final Location loc = arenaPlayer.getPlayer().getLocation();
            if (loc.getWorld().equals(center.getWorld()) && loc.distance(center) <= range) {
                result.add(arenaPlayer.getArenaTeam());
            }
        }
        return result;
    }

    // ---- end --------------------------------------------------------------------------------

    @Override
    public boolean checkEnd() {
        return this.capturedBy != null || TeamManager.countActiveTeams(this.arena) <= 1;
    }

    @Override
    public void commitEnd(final boolean force) {
        if (this.arena.realEndRunner != null) {
            debug(this.arena, "[ZONECAPTURE] already ending");
            return;
        }
        debug(this.arena, "[ZONECAPTURE]");

        Bukkit.getPluginManager().callEvent(new PAGoalEndEvent(this.arena, this));

        ArenaTeam winner = this.capturedBy;
        if (winner == null) {
            // nobody captured — fall back to whoever is still standing
            winner = TeamManager.getActiveTeams(this.arena).stream().findFirst().orElse(null);
        }

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

    private static class ZoneRunnable extends BukkitRunnable {
        private final GoalZoneCapture goal;

        ZoneRunnable(final GoalZoneCapture goal) {
            this.goal = goal;
        }

        @Override
        public void run() {
            if (!this.goal.arena.isFightInProgress() || this.goal.arena.realEndRunner != null) {
                this.cancel();
                return;
            }
            this.goal.checkZones();
        }
    }
}
