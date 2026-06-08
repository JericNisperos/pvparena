package net.slipcor.pvparena.goals.cyangladiator;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PASpawn;
import net.slipcor.pvparena.exceptions.GameplayException;
import net.slipcor.pvparena.goals.AbstractPlayerLivesGoal;
import net.slipcor.pvparena.loadables.ArenaModuleManager;
import net.slipcor.pvparena.managers.SpawnManager;
import net.slipcor.pvparena.managers.TeleportManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>Gladiator — a guild battle-royale goal (UltimateClans-backed).</pre>
 *
 * Free-for-all where players fight as their <b>Guild</b>; guild-mates can't hurt each other
 * (handled by {@link GladiatorListener}); <b>last guild standing wins</b>. Single life. Players
 * without a guild are rejected; the match won't start until ≥ 2 distinct guilds are present; and
 * each guild spawns together at its own {@code fight} spawn.
 *
 * <p>This goal handles only <b>gameplay</b>. Rewards and the {@code /gladiatorjoin} command live in
 * the companion module <b>GladiatorMod</b> (hot-reloadable). The goal therefore <b>requires that
 * module to be enabled on the arena</b> — see {@link #checkJoin}.</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit. Reaches the guild
 * API via {@link GuildBridge} (reflection), so no {@code plugin.yml} softdepend is needed.</p>
 */
public class GoalGladiator extends AbstractPlayerLivesGoal {

    /** Module (in /mods) that must be enabled on the arena for Gladiator to run. */
    static final String GLADIATOR_MOD_NAME = "GladiatorMod";

    static {
        // Runs at plugin enable (JarLoader loads goal classes with Class.forName(..., initialize = true)).
        GladiatorListener.ensureRegistered();
    }

    public GoalGladiator() {
        super("Gladiator");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public boolean isFreeForAll() {
        return true;
    }

    /** Single life — eliminated on first death. */
    @Override
    protected int getLivesConfigValue() {
        return 1;
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        return SpawnManager.getMissingFFASpawn(this.arena, spawns);
    }

    @Override
    public void checkJoin(final Player player, final String[] args) throws GameplayException {
        super.checkJoin(player, args);

        // Gladiator pairs with the GladiatorMod module (rewards + /gladiatorjoin). Require it.
        boolean modEnabled = this.arena.getMods().stream()
                .anyMatch(mod -> GLADIATOR_MOD_NAME.equals(mod.getName()));
        if (!modEnabled) {
            throw new GameplayException(ChatColor.RED
                    + "The Gladiator module is not enabled on this arena (enable GladiatorMod).");
        }

        final GuildBridge guilds = GuildBridge.get();
        if (!guilds.isAvailable()) {
            throw new GameplayException(ChatColor.RED + "The guild system (UltimateClans) is unavailable right now.");
        }
        if (!guilds.hasGuild(player)) {
            throw new GameplayException(ChatColor.RED + "You must be in a guild to join the Gladiator arena.");
        }
    }

    @Override
    public void initiate(final ArenaPlayer arenaPlayer) {
        // Set the single life directly (NOT via updateLives) so it stays 1 even if the arena has
        // general.addLivesPerPlayer enabled — that would otherwise multiply lives by player count.
        this.getPlayerLifeMap().put(arenaPlayer, 1);
    }

    @Override
    public void parseStart() {
        this.arena.getFighters().forEach(arenaPlayer -> this.getPlayerLifeMap().put(arenaPlayer, 1));
    }

    // ---- Guild-based spawns: each guild spawns together on its own fight spawn (wraps) -----------

    @Override
    public boolean overridesStart() {
        return true;
    }

    @Override
    public void commitStart() {
        final List<PASpawn> fightSpawns = this.arena.getSpawns().stream()
                .filter(spawn -> spawn.getName().startsWith(PASpawn.FIGHT))
                .sorted(Comparator.comparing(PASpawn::getName))
                .collect(Collectors.toList());

        if (fightSpawns.isEmpty()) {
            // No fight spawns (shouldn't happen — checkForMissingSpawns enforces them): fall back.
            for (final ArenaTeam team : this.arena.getTeams()) {
                SpawnManager.distributeTeams(this.arena, team);
            }
            return;
        }

        // First-seen guild order → fight1, fight2, …; wrap when there are more guilds than spawns.
        final List<UUID> guildOrder = new ArrayList<>();
        for (final ArenaPlayer arenaPlayer : this.arena.getFighters()) {
            final UUID guildId = GuildBridge.get().guildId(arenaPlayer.getPlayer());
            if (guildId != null && !guildOrder.contains(guildId)) {
                guildOrder.add(guildId);
            }
        }

        for (final ArenaPlayer arenaPlayer : this.arena.getFighters()) {
            final UUID guildId = GuildBridge.get().guildId(arenaPlayer.getPlayer());
            final int guildIndex = (guildId == null) ? 0 : Math.max(0, guildOrder.indexOf(guildId));
            final PASpawn spawn = fightSpawns.get(guildIndex % fightSpawns.size());
            TeleportManager.teleportPlayerToSpawn(this.arena, arenaPlayer, spawn);
        }
    }

    // ---- Win condition: last guild standing ------------------------------------------------------

    @Override
    public boolean checkEnd() {
        return livingGuilds().size() <= 1;
    }

    private Set<UUID> livingGuilds() {
        final Set<UUID> guilds = new HashSet<>();
        for (final ArenaPlayer arenaPlayer : this.getActivePlayerLifeMap().keySet()) {
            final UUID guildId = GuildBridge.get().guildId(arenaPlayer.getPlayer());
            if (guildId != null) {
                guilds.add(guildId);
            }
        }
        return guilds;
    }

    @Override
    protected void setWinnerAndBroadcastEndMessages(final ArenaTeam teamToCheck) {
        // FFA: the single "free" team holds everyone. The surviving guild is the winner.
        final ArenaPlayer survivor = teamToCheck.getTeamMembers().stream()
                .filter(ap -> ap.getStatus() == PlayerStatus.FIGHT && ap.getPlayer() != null)
                .findFirst()
                .orElse(null);
        if (survivor == null) {
            return;
        }

        final UUID winningGuild = GuildBridge.get().guildId(survivor.getPlayer());
        final String tag = GuildBridge.get().guildTag(winningGuild);
        final String label = (tag != null && !tag.isEmpty()) ? tag : survivor.getName();

        final String message = ChatColor.GOLD + "Guild " + ChatColor.YELLOW + label
                + ChatColor.GOLD + " wins the Gladiator!";
        ArenaModuleManager.announce(this.arena, message, "END");
        ArenaModuleManager.announce(this.arena, message, "WINNER");
        this.arena.broadcast(message);

        teamToCheck.getTeamMembers().stream()
                .filter(ap -> ap.getStatus() == PlayerStatus.FIGHT)
                .forEach(ap -> this.arena.addWinner(ap.getName()));

        // Rewards are handled by the GladiatorMod module via its commitEnd hook (it knows the winner).
    }

    @Override
    protected ArenaPlayer getWinningPlayerIfNeeded(final ArenaTeam teamToCheck) {
        return teamToCheck.getTeamMembers().stream()
                .filter(ap -> ap.getStatus() == PlayerStatus.FIGHT)
                .findFirst()
                .orElse(null);
    }
}
