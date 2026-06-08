package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a side's command rewards when a Guild War ends (EventActions-style {@code prefix<=>command}).
 *
 * <p>Configured globally in {@link GuildWarConfig} ({@code cyan_guildwarchallenge_config.yml}):
 * {@code reward-scope}, {@code winner-commands}, {@code loser-commands}. Each entry is
 * {@code prefix<=>command} where {@code prefix} is {@code console} (run by the server) or
 * {@code player} (run by each recipient).</p>
 *
 * <p>Placeholders: {@code %player%} (recipient), {@code %guild%} (the rewarded side's tag),
 * {@code %enemy%} (the opposing side's tag), {@code %arena%}. Entries containing {@code %player%}
 * run once per recipient; otherwise once. Tags are passed no-color to keep commands clean. This is
 * deliberately decoupled from UltimateClans' Rewards Center — to feed it, run its own command, e.g.
 * {@code console<=>clan addpoints %guild% 50}.</p>
 */
final class GuildWarRewards {

    private static final String SPLIT = "<=>";

    private GuildWarRewards() {
    }

    /**
     * Run {@code commands} for one side of a finished war.
     *
     * @param arenaName    arena the war was fought on (for {@code %arena%})
     * @param ownTag       no-color tag of the side being rewarded (for {@code %guild%})
     * @param enemyTag     no-color tag of the opposing side (for {@code %enemy%})
     * @param participants that side's fighters (used when scope is PARTICIPANTS)
     * @param guildId      that side's guild (used to resolve online members when scope is ALL_MEMBERS)
     * @param commands     the {@code prefix<=>command} entries to run (winner or loser list)
     */
    static void run(final String arenaName, final String ownTag, final String enemyTag,
                    final Set<UUID> participants, final UUID guildId, final List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        final List<Player> recipients = resolveRecipients(guildId, participants);
        final String tag = ownTag != null ? ownTag : "";
        final String foe = enemyTag != null ? enemyTag : "";
        final String arena = arenaName != null ? arenaName : "";

        for (final String entry : commands) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            final int idx = entry.indexOf(SPLIT);
            final String prefix = (idx >= 0 ? entry.substring(0, idx).trim() : "console").toLowerCase();
            final String rawCommand = idx >= 0 ? entry.substring(idx + SPLIT.length()) : entry;

            if (rawCommand.contains("%player%")) {
                for (final Player recipient : recipients) {
                    dispatch(prefix, recipient, substitute(rawCommand, recipient.getName(), tag, foe, arena));
                }
            } else {
                final Player who = recipients.isEmpty() ? null : recipients.get(0);
                dispatch(prefix, who, substitute(rawCommand, who != null ? who.getName() : "", tag, foe, arena));
            }
        }
    }

    private static List<Player> resolveRecipients(final UUID guildId, final Set<UUID> participants) {
        final List<Player> out = new ArrayList<>();
        final Iterable<UUID> ids = GuildWarConfig.get().rewardAllMembers()
                ? GuildBridge.get().guildMembers(guildId) : participants;
        if (ids == null) {
            return out;
        }
        for (final UUID id : ids) {
            if (id == null) {
                continue;
            }
            final Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String substitute(final String command, final String playerName, final String tag,
                                     final String enemyTag, final String arenaName) {
        return command
                .replace("%player%", playerName)
                .replace("%guild%", tag)
                .replace("%enemy%", enemyTag)
                .replace("%arena%", arenaName);
    }

    private static void dispatch(final String prefix, final Player player, final String command) {
        try {
            if ("player".equals(prefix)) {
                if (player != null && player.isOnline()) {
                    player.performCommand(command);
                }
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        } catch (final Throwable t) {
            PVPArena.getInstance().getLogger()
                    .warning("[GuildWarChallenge] reward command failed ('" + command + "'): " + t.getMessage());
        }
    }
}
