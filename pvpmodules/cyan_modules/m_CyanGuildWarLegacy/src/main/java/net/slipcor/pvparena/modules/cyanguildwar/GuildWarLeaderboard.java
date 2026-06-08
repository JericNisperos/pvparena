package net.slipcor.pvparena.modules.cyanguildwar;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Renders {@code /cyangpa guildwar top [n]} — guilds ranked by GuildWar wins (ties broken by fewer
 * losses). Reads straight from {@link GuildWarResultStore}; guild tags are resolved via
 * {@link GuildBridge} (falls back to a short UUID when a tag is unavailable).
 */
final class GuildWarLeaderboard {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 25;

    private GuildWarLeaderboard() {
    }

    static void show(final Player player, final String limitArg) {
        final int limit = parseLimit(limitArg);

        final ConfigurationSection section = GuildWarResultStore.get().guildsSection();
        final List<Row> rows = new ArrayList<>();
        for (final String key : section.getKeys(false)) {
            final UUID guildId = parseUuid(key);
            if (guildId == null) {
                continue;
            }
            final int wins = section.getInt(key + ".wins", 0);
            final int losses = section.getInt(key + ".losses", 0);
            if (wins == 0 && losses == 0) {
                continue;
            }
            rows.add(new Row(guildId, wins, losses));
        }

        if (rows.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "GuildWar: no results recorded yet.");
            return;
        }

        rows.sort(Comparator.<Row>comparingInt(r -> r.wins).reversed()
                .thenComparingInt(r -> r.losses));

        player.sendMessage(ChatColor.GOLD + "===== GuildWar — Top Guilds =====");
        int rank = 1;
        for (final Row row : rows) {
            if (rank > limit) {
                break;
            }
            player.sendMessage(ChatColor.YELLOW + "" + rank + ". " + ChatColor.WHITE + label(row.guildId)
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
            if (n < 1) {
                return DEFAULT_LIMIT;
            }
            return Math.min(n, MAX_LIMIT);
        } catch (final NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private static UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static String label(final UUID guildId) {
        final String clean = GuildWar.sanitizeTag(GuildBridge.get().guildTag(guildId));
        if (!clean.isEmpty()) {
            return clean;
        }
        return "guild " + guildId.toString().substring(0, 8);
    }

    private static final class Row {
        final UUID guildId;
        final int wins;
        final int losses;

        Row(final UUID guildId, final int wins, final int losses) {
            this.guildId = guildId;
            this.wins = wins;
            this.losses = losses;
        }
    }
}
