package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a guild's command rewards for a finished rumble (EventActions-style {@code prefix<=>command}).
 *
 * <p>Reads {@link GladiatorConfig}: {@code winner-commands} for the surviving guild,
 * {@code participation-commands} for every other guild that took part, and {@code reward-scope}
 * ({@code PARTICIPANTS} = that guild's fighters, {@code ALL_MEMBERS} = every online member).</p>
 *
 * <p>Placeholders: {@code %player%} (per recipient), {@code %guild%} (this guild's tag),
 * {@code %winner%} (the winning guild's tag), {@code %arena%}. Entries containing {@code %player%}
 * run once per recipient; otherwise once.</p>
 */
final class GladiatorRewards {

    private static final String SPLIT = "<=>";

    private GladiatorRewards() {
    }

    static void run(final String arenaName, final List<String> commands, final UUID guildId,
                    final String guildTag, final String winnerTag, final Set<UUID> participantIds) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        final boolean allMembers = GladiatorConfig.get().rewardAllMembers();
        final List<Player> recipients = resolveRecipients(allMembers, guildId, participantIds);
        final String tag = guildTag != null ? guildTag : "";
        final String winner = winnerTag != null ? winnerTag : "";

        for (final String entry : commands) {
            final int idx = entry.indexOf(SPLIT);
            final String prefix = (idx >= 0 ? entry.substring(0, idx).trim() : "console").toLowerCase();
            final String rawCommand = idx >= 0 ? entry.substring(idx + SPLIT.length()) : entry;

            if (rawCommand.contains("%player%")) {
                for (final Player recipient : recipients) {
                    dispatch(prefix, recipient, substitute(rawCommand, recipient.getName(), tag, winner, arenaName));
                }
            } else {
                final Player who = recipients.isEmpty() ? null : recipients.get(0);
                dispatch(prefix, who, substitute(rawCommand, who != null ? who.getName() : "", tag, winner, arenaName));
            }
        }
    }

    private static List<Player> resolveRecipients(final boolean allMembers, final UUID guildId,
                                                  final Set<UUID> participantIds) {
        final List<Player> out = new ArrayList<>();
        final Iterable<UUID> ids = allMembers ? GuildBridge.get().guildMembers(guildId) : participantIds;
        if (ids == null) {
            return out;
        }
        for (final UUID id : ids) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String substitute(final String command, final String playerName, final String tag,
                                     final String winnerTag, final String arenaName) {
        return command
                .replace("%player%", playerName)
                .replace("%guild%", tag)
                .replace("%winner%", winnerTag)
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
                    .warning("[Gladiator] reward command failed ('" + command + "'): " + t.getMessage());
        }
    }
}
