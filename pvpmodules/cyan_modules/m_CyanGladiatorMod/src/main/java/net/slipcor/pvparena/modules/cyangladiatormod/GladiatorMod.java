package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.core.Config;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Companion module for the <b>Gladiator</b> goal — the hot-reloadable half (so rewards/command can be
 * iterated via {@code /pa modules install/uninstall} without a server restart).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *     <li>scaffold + read per-arena reward config ({@code modules.gladiatormod.winnerCommands} /
 *         {@code rewardScope}) and run rewards in {@link #commitEnd} (the goal's commitEnd calls this);</li>
 *     <li>register {@code /gladiatorjoin} + {@code pvparena.cmds.gladiatorjoin} (see
 *         {@link GladiatorJoinCommand});</li>
 *     <li>track participants by guild (via {@link #parseJoin}) for the PARTICIPANTS reward scope.</li>
 * </ul>
 *
 * <p><b>Requires the Gladiator goal.</b> If the goal isn't loaded ({@code getAgm().hasLoadable}),
 * the module disables its behavior (the command rejects, rewards no-op) and logs once.</p>
 */
public class GladiatorMod extends ArenaModule {

    static final String GOAL_NAME = "Gladiator";

    static {
        // Register the /gladiatorjoin command once at plugin enable (JarLoader initializes the class).
        GladiatorJoinCommand.ensureRegistered();
    }

    /** Per-arena (this instance) participants grouped by guild, for PARTICIPANTS reward scope. */
    private final Map<UUID, Set<UUID>> participantsByGuild = new HashMap<>();

    public GladiatorMod() {
        super("GladiatorMod");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    /** True if the Gladiator goal jar is present in /goals. */
    static boolean goalInstalled() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null && plugin.getAgm() != null && plugin.getAgm().hasLoadable(GOAL_NAME);
    }

    @Override
    public void initConfig() {
        // Scaffold editable reward config into the arena's config.yml when the module is enabled.
        final Config cfg = this.arena.getConfig();
        boolean changed = false;
        if (cfg.getUnsafe(GladiatorRewards.SCOPE_PATH) == null) {
            cfg.setManually(GladiatorRewards.SCOPE_PATH, "PARTICIPANTS");
            changed = true;
        }
        if (cfg.getUnsafe(GladiatorRewards.CMDS_PATH) == null) {
            final List<String> defaults = new ArrayList<>();
            defaults.add("console<=>broadcast &6Guild &e%guild%&6 won the Gladiator in %arena%!");
            cfg.setManually(GladiatorRewards.CMDS_PATH, defaults);
            changed = true;
        }
        if (changed) {
            cfg.save();
        }
        if (!goalInstalled()) {
            log().warning("[GladiatorMod] enabled but the Gladiator goal is not installed in /goals — "
                    + "rewards and /gladiatorjoin are inactive until it is added.");
        }
    }

    @Override
    public void parseJoin(final Player player, final ArenaTeam team) {
        final UUID guildId = GuildBridge.get().guildId(player);
        if (guildId != null) {
            this.participantsByGuild.computeIfAbsent(guildId, k -> new HashSet<>()).add(player.getUniqueId());
        }
    }

    @Override
    public boolean commitEnd(final ArenaTeam aTeam, final ArenaPlayer arenaPlayer) {
        if (!goalInstalled() || arenaPlayer == null || arenaPlayer.getPlayer() == null) {
            return false;
        }
        if (this.arena.getGoal() == null || !GOAL_NAME.equals(this.arena.getGoal().getName())) {
            return false; // only act for Gladiator arenas
        }
        final UUID winningGuild = GuildBridge.get().guildId(arenaPlayer.getPlayer());
        final String tag = GuildBridge.get().guildTag(winningGuild);
        final Set<UUID> participants = this.participantsByGuild.getOrDefault(winningGuild, new HashSet<>());
        GladiatorRewards.run(this.arena, winningGuild, tag, participants);
        return false; // we don't override the end, just reward
    }

    @Override
    public void reset(final boolean force) {
        this.participantsByGuild.clear();
    }

    static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
