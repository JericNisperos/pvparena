package net.slipcor.pvparena.modules.cyangladiatormod;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Renders {@code /gladiator top [n]} — guilds ranked by Gladiator wins (ties broken by fewer losses).
 * Reads the ranked standings from {@link GladiatorResultStore}; labels via {@link GladiatorText}.
 */
final class GladiatorLeaderboard {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 25;

    private GladiatorLeaderboard() {
    }

    static void show(final CommandSender sender, final String limitArg) {
        final int limit = parseLimit(limitArg);
        final List<GladiatorResultStore.Standing> rows = GladiatorResultStore.get().rankedByWins();

        if (rows.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Gladiator: no results recorded yet.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "===== Gladiator — Top Guilds =====");
        int rank = 1;
        for (final GladiatorResultStore.Standing row : rows) {
            if (rank > limit) {
                break;
            }
            sender.sendMessage(ChatColor.YELLOW + "" + rank + ". "
                    + ChatColor.WHITE + GladiatorText.guildDisplay(row.guildId, row.name)
                    + ChatColor.GRAY + " — " + ChatColor.GREEN + row.wins + "W"
                    + ChatColor.GRAY + " / " + ChatColor.RED + row.losses + "L");
            rank++;
        }
    }

    private static int parseLimit(final String arg) {
        if (arg == null) {
            return DEFAULT_LIMIT;
        }
        try {
            final int n = Integer.parseInt(arg.trim());
            return n < 1 ? DEFAULT_LIMIT : Math.min(n, MAX_LIMIT);
        } catch (final NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
