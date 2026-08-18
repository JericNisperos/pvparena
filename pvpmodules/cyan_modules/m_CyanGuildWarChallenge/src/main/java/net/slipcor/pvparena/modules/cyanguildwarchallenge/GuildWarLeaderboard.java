package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Renders {@code /guildwar top [n]} — guilds ranked by challenge wins (ties broken by fewer losses).
 * Reads the ranked standings from {@link GuildWarResultStore}; labels resolved via
 * {@link GuildWarText#guildDisplay}.
 */
final class GuildWarLeaderboard {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 25;

    private GuildWarLeaderboard() {
    }

    static void show(final CommandSender sender, final String limitArg) {
        final int limit = parseLimit(limitArg);
        final List<GuildWarResultStore.Standing> rows = GuildWarResultStore.get().rankedByWins();

        if (rows.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "GuildWar: no results recorded yet.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "===== GuildWar — Top Guilds =====");
        int rank = 1;
        for (final GuildWarResultStore.Standing row : rows) {
            if (rank > limit) {
                break;
            }
            sender.sendMessage(ChatColor.YELLOW + "" + rank + ". "
                    + ChatColor.WHITE + GuildWarText.guildDisplay(row.guildId, row.name)
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
