package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the winning guild's command rewards (EventActions-style {@code prefix<=>command}).
 *
 * <p>Config (per arena, scaffolded by {@link GladiatorMod}):</p>
 * <pre>
 * modules:
 *   gladiatormod:
 *     rewardScope: PARTICIPANTS   # or ALL_MEMBERS
 *     winnerCommands:
 *       - "console&lt;=&gt;eco give %player% 1000"
 *       - "console&lt;=&gt;broadcast Guild %guild% won the Gladiator in %arena%!"
 *       - "player&lt;=&gt;spawn"
 * </pre>
 * Placeholders: {@code %player%} (per recipient), {@code %guild%} (tag), {@code %arena%}. Entries
 * containing {@code %player%} run once per recipient; otherwise once.
 */
final class GladiatorRewards {

    static final String CMDS_PATH = "modules.gladiatormod.winnerCommands";
    static final String SCOPE_PATH = "modules.gladiatormod.rewardScope";
    private static final String SPLIT = "<=>";

    private GladiatorRewards() {
    }

    static void run(final Arena arena, final UUID winningGuildId, final String guildTag,
                    final Set<UUID> participantIds) {
        final List<String> commands = arena.getConfig().getStringList(CMDS_PATH, new ArrayList<>());
        if (commands == null || commands.isEmpty()) {
            return;
        }

        final Object scopeRaw = safeUnsafe(arena, SCOPE_PATH);
        final boolean allMembers = scopeRaw != null && "ALL_MEMBERS".equalsIgnoreCase(scopeRaw.toString());

        final List<Player> recipients = resolveRecipients(allMembers, winningGuildId, participantIds);
        final String tag = guildTag != null ? guildTag : "";

        for (final String entry : commands) {
            final int idx = entry.indexOf(SPLIT);
            final String prefix = (idx >= 0 ? entry.substring(0, idx).trim() : "console").toLowerCase();
            final String rawCommand = idx >= 0 ? entry.substring(idx + SPLIT.length()) : entry;

            if (rawCommand.contains("%player%")) {
                for (final Player recipient : recipients) {
                    dispatch(prefix, recipient, substitute(rawCommand, recipient.getName(), tag, arena.getName()));
                }
            } else {
                final Player who = recipients.isEmpty() ? null : recipients.get(0);
                dispatch(prefix, who, substitute(rawCommand, who != null ? who.getName() : "", tag, arena.getName()));
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
                                     final String arenaName) {
        return command
                .replace("%player%", playerName)
                .replace("%guild%", tag)
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
                    .warning("[GladiatorMod] reward command failed ('" + command + "'): " + t.getMessage());
        }
    }

    private static Object safeUnsafe(final Arena arena, final String path) {
        try {
            return arena.getConfig().getUnsafe(path);
        } catch (final Throwable t) {
            return null;
        }
    }
}
