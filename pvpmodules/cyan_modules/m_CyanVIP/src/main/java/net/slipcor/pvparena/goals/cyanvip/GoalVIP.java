package net.slipcor.pvparena.goals.cyanvip;

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
import net.slipcor.pvparena.loadables.ArenaModuleManager;
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
 * <pre>VIP — protect your own VIP, kill the enemy's.</pre>
 *
 * One player per team is designated VIP at match start. Ordinary players respawn as usual; the VIP
 * does not. Killing the enemy VIP wins the match immediately.
 *
 * <p>The VIP's death is genuinely final, so unlike {@code RoundTeamLives} this goal can use the
 * core {@code handleDeathAndLose()} path — there is no round to come back for.</p>
 *
 * <p>If a VIP leaves or disconnects, the role is handed to a living teammate rather than forfeiting
 * the match, so a rage-quit cannot hand the win away. A team with nobody left to promote is out.</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit.</p>
 */
public class GoalVIP extends ArenaGoal {

    private static final String CFG_GLOW = "goal.vip.glow";

    /** Team -> its living VIP. A team drops out of this map when its VIP dies unreplaced. */
    private final Map<ArenaTeam, ArenaPlayer> vips = new HashMap<>();

    public GoalVIP() {
        super("VIP");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    private boolean glowEnabled() {
        return this.arena.getConfig().getYamlConfiguration().getBoolean(CFG_GLOW, true);
    }

    @Override
    public void setDefaults(final YamlConfiguration config) {
        if (config.get("teams") == null) {
            debug(this.arena, "no teams defined, adding custom red and blue!");
            config.addDefault("teams.red", ChatColor.RED.name());
            config.addDefault("teams.blue", ChatColor.BLUE.name());
        }
        config.addDefault(CFG_GLOW, true);
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        return SpawnManager.getMissingTeamSpawn(this.arena, spawns);
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage("one VIP per team; kill the enemy VIP to win");
        sender.sendMessage("VIP glows: " + this.glowEnabled());
    }

    // ---- VIP assignment ----------------------------------------------------------------------

    @Override
    public void parseStart() {
        this.vips.clear();
        for (final ArenaTeam team : this.arena.getNotEmptyTeams()) {
            this.assignVip(team, null);
        }
    }

    /** Promote a random living member of the team, skipping {@code exclude}. */
    private void assignVip(final ArenaTeam team, final ArenaPlayer exclude) {
        final List<ArenaPlayer> candidates = new ArrayList<>();
        for (final ArenaPlayer arenaPlayer : team.getTeamMembers()) {
            if (arenaPlayer != exclude
                    && arenaPlayer.getStatus() == PlayerStatus.FIGHT
                    && arenaPlayer.getPlayer() != null) {
                candidates.add(arenaPlayer);
            }
        }

        if (candidates.isEmpty()) {
            this.vips.remove(team);
            return;
        }

        final ArenaPlayer vip = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        this.vips.put(team, vip);
        this.setGlow(vip, true);

        this.arena.broadcast(team.getColoredName() + ChatColor.YELLOW + "'s VIP is "
                + team.colorizePlayer(vip) + ChatColor.YELLOW + "!");
    }

    private boolean isVip(final ArenaPlayer arenaPlayer) {
        return arenaPlayer != null && arenaPlayer.equals(this.vips.get(arenaPlayer.getArenaTeam()));
    }

    private void setGlow(final ArenaPlayer arenaPlayer, final boolean glowing) {
        if (!this.glowEnabled()) {
            return;
        }
        final Player player = arenaPlayer.getPlayer();
        if (player != null) {
            player.setGlowing(glowing);
        }
    }

    // ---- deaths -----------------------------------------------------------------------------

    @Override
    public Boolean shouldRespawnPlayer(final ArenaPlayer arenaPlayer, final PADeathInfo deathInfo) {
        return !this.isVip(arenaPlayer); // everyone but the VIP comes back
    }

    @Override
    public void commitPlayerDeath(final ArenaPlayer arenaPlayer, final boolean doesRespawn,
                                  final PADeathInfo deathInfo) {
        Bukkit.getPluginManager().callEvent(
                new PAGoalPlayerDeathEvent(this.arena, this, arenaPlayer, deathInfo, doesRespawn));

        arenaPlayer.setMayDropInventory(true);

        if (!this.isVip(arenaPlayer)) {
            arenaPlayer.setMayRespawn(true);
            if (this.arena.getConfig().getBoolean(CFG.USES_DEATHMESSAGES)) {
                this.broadcastSimpleDeathMessage(arenaPlayer, deathInfo);
            }
            return;
        }

        // The VIP is down: that team is out of the match for good.
        final ArenaTeam team = arenaPlayer.getArenaTeam();
        this.vips.remove(team);
        this.setGlow(arenaPlayer, false);
        arenaPlayer.setMayRespawn(false);

        this.arena.broadcast(team.getColoredName() + ChatColor.YELLOW + "'s VIP has fallen!");

        arenaPlayer.handleDeathAndLose(deathInfo);
        WorkflowManager.handleEnd(this.arena, false);
    }

    @Override
    public void parseLeave(final ArenaPlayer arenaPlayer) {
        if (arenaPlayer == null) {
            PVPArena.getInstance().getLogger().warning(this.getName() + ": player NULL");
            return;
        }
        if (!this.isVip(arenaPlayer)) {
            return;
        }

        final ArenaTeam team = arenaPlayer.getArenaTeam();
        this.setGlow(arenaPlayer, false);
        this.vips.remove(team);

        // Promote a teammate next tick, once the leaver is actually off the roster.
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
            this.arena.broadcast(team.getColoredName() + ChatColor.YELLOW + "'s VIP left the fight!");
            this.assignVip(team, arenaPlayer);
            WorkflowManager.handleEnd(this.arena, false);
        });
    }

    @Override
    public void reset(final boolean force) {
        this.vips.values().forEach(vip -> this.setGlow(vip, false));
        this.vips.clear();
    }

    // ---- end --------------------------------------------------------------------------------

    @Override
    public boolean checkEnd() {
        return this.vips.size() <= 1;
    }

    @Override
    public void commitEnd(final boolean force) {
        if (this.arena.realEndRunner != null) {
            debug(this.arena, "[VIP] already ending");
            return;
        }
        debug(this.arena, "[VIP]");

        Bukkit.getPluginManager().callEvent(new PAGoalEndEvent(this.arena, this));

        final ArenaTeam winner = this.vips.keySet().stream().findFirst().orElse(null);

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
        // Surviving VIP is the only thing worth scoring when the clock runs out.
        for (final ArenaTeam team : this.arena.getNotEmptyTeams()) {
            scores.merge(team.getName(), this.vips.containsKey(team) ? 1.0 : 0.0, Double::sum);
        }
        return scores;
    }

    @Override
    public void unload(final ArenaPlayer arenaPlayer) {
        super.unload(arenaPlayer);
        Optional.ofNullable(arenaPlayer)
                .map(ArenaPlayer::getArenaTeam)
                .ifPresent(team -> {
                    if (arenaPlayer.equals(this.vips.get(team))) {
                        this.vips.remove(team);
                    }
                });
    }
}
