package net.slipcor.pvparena.goals.cyangladiator;

import net.slipcor.pvparena.PVPArena;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
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
 * Free-for-all where players fight as their <b>Guild</b>; guild-mates can't hurt each other,
 * <b>last guild standing wins</b>. Players without a guild are rejected; the match won't start until
 * the configured number of distinct guilds is present; and each guild spawns together at its own
 * {@code fight} spawn.
 *
 * <p>This goal is intentionally <b>thin</b> — it owns only the win condition, guild-grouped spawns
 * and lives. Everything else (friendly fire, the start gate, the {@code /gladiator} command, config,
 * rewards, the leaderboard and PlaceholderAPI) lives in the hot-reloadable companion module
 * <b>CyanGladiatorMod</b>, which wires itself globally and so does <b>not</b> need to be attached to
 * the arena.</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit. Reaches the guild
 * API via {@link GuildBridge} (reflection), so no {@code plugin.yml} softdepend is needed. The one
 * value the goal shares with the module — {@code lives} — is read directly from the module's
 * {@code cyan_gladiator_config.yml} (goal and module live in separate classloaders, so they can't
 * reference each other's types).</p>
 */
public class GoalGladiator extends AbstractPlayerLivesGoal {

    /** Central config file (owned by CyanGladiatorMod); the goal reads only {@code lives} from it. */
    private static final String CONFIG_FILE = "cyan_gladiator_config.yml";
    private static final int DEFAULT_LIVES = 1;

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

    /** Lives per player (default 1 = single-life battle royale), from the module's config file. */
    @Override
    protected int getLivesConfigValue() {
        return configuredLives();
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        return SpawnManager.getMissingFFASpawn(this.arena, spawns);
    }

    @Override
    public void checkJoin(final Player player, final String[] args) throws GameplayException {
        super.checkJoin(player, args);

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
        // Set lives directly (NOT via updateLives) so general.addLivesPerPlayer can't multiply them.
        this.getPlayerLifeMap().put(arenaPlayer, configuredLives());
    }

    @Override
    public void parseStart() {
        final int lives = configuredLives();
        this.arena.getFighters().forEach(arenaPlayer -> this.getPlayerLifeMap().put(arenaPlayer, lives));
    }

    /** Read {@code lives} from the module's config file; falls back to a single life. */
    private static int configuredLives() {
        try {
            final PVPArena plugin = PVPArena.getInstance();
            if (plugin == null) {
                return DEFAULT_LIVES;
            }
            final File file = new File(plugin.getDataFolder(), CONFIG_FILE);
            if (!file.exists()) {
                return DEFAULT_LIVES;
            }
            final int lives = YamlConfiguration.loadConfiguration(file).getInt("lives", DEFAULT_LIVES);
            return lives > 0 ? lives : DEFAULT_LIVES;
        } catch (final Throwable t) {
            return DEFAULT_LIVES;
        }
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
