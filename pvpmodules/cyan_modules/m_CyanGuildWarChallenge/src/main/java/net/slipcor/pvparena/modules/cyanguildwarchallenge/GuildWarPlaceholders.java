package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI expansion for Guild War, identifier {@code cyanguildwar}. Soft-hooked: this class
 * references PlaceholderAPI types, so it's only ever touched from {@link #registerSafely()} <b>after</b>
 * a PlaceholderAPI presence check — the module still loads fine without PlaceholderAPI installed.
 *
 * <h2>Placeholders</h2>
 * <ul>
 *   <li>{@code %cyanguildwar_player_wins%} / {@code %cyanguildwar_player_losses%} — the queried
 *       player's own per-Guild-War tally.</li>
 *   <li>{@code %cyanguildwar_guild_wins_<rank>_name%} / {@code _value%} — name / win-count of the
 *       guild at that 1-based rank on the wins leaderboard.</li>
 *   <li>{@code %cyanguildwar_guild_losses_<rank>_name%} / {@code _value%} — same, ranked by losses.</li>
 * </ul>
 * An out-of-range rank returns {@code ""} so scoreboards render a blank slot rather than the raw text.
 */
public class GuildWarPlaceholders extends PlaceholderExpansion {

    static final String ID = "cyanguildwar";

    /**
     * (Re)register the expansion if PlaceholderAPI is installed. Reload-safe: any previously-registered
     * {@code cyanguildwar} expansion (e.g. from a prior classloader after {@code /guildwar reinstall})
     * is unregistered first so the fresh instance serves current data.
     */
    static void registerSafely() {
        try {
            final PlaceholderExpansion existing = PlaceholderAPIPlugin.getInstance()
                    .getLocalExpansionManager().getExpansion(ID);
            if (existing != null) {
                existing.unregister();
            }
        } catch (final Throwable ignored) {
            // PAPI internals differ across versions — non-fatal, register() below still replaces/declines.
        }
        final boolean ok = new GuildWarPlaceholders().register();
        if (ok) {
            CyanGuildWarChallenge.logger().info("[GuildWarChallenge] PlaceholderAPI expansion '%"
                    + ID + "_...%' registered.");
        }
    }

    @Override
    public String getIdentifier() {
        return ID;
    }

    @Override
    public String getAuthor() {
        return "Cyan";
    }

    @Override
    public String getVersion() {
        final String v = getClass().getPackage().getImplementationVersion();
        return v == null ? "1.0.0" : v;
    }

    /** Persist through PlaceholderAPI reloads. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(final OfflinePlayer player, final String params) {
        final String[] p = params.toLowerCase(Locale.ROOT).split("_");
        if (p.length == 0) {
            return null;
        }

        // %cyanguildwar_player_wins% / %cyanguildwar_player_losses%
        if ("player".equals(p[0])) {
            if (player == null || p.length < 2) {
                return "";
            }
            final UUID id = player.getUniqueId();
            if ("wins".equals(p[1])) {
                return String.valueOf(GuildWarResultStore.get().playerWins(id));
            }
            if ("losses".equals(p[1])) {
                return String.valueOf(GuildWarResultStore.get().playerLosses(id));
            }
            return null;
        }

        // %cyanguildwar_guild_<wins|losses>_<rank>_<name|value>%
        if ("guild".equals(p[0]) && p.length >= 4) {
            final String metric = p[1];
            final int rank;
            try {
                rank = Integer.parseInt(p[2]);
            } catch (final NumberFormatException e) {
                return null;
            }
            final String field = p[3];
            if (rank < 1) {
                return "";
            }

            final List<GuildWarResultStore.Standing> rows = GuildWarResultStore.get().rankedByWins();
            final boolean byLosses = "losses".equals(metric);
            if (byLosses) {
                rows.sort((a, b) -> {
                    final int cmp = Integer.compare(b.losses, a.losses);
                    return cmp != 0 ? cmp : Integer.compare(b.wins, a.wins);
                });
            } else if (!"wins".equals(metric)) {
                return null;
            }

            if (rank > rows.size()) {
                return ""; // empty leaderboard slot
            }
            final GuildWarResultStore.Standing s = rows.get(rank - 1);
            if ("name".equals(field)) {
                return GuildWarText.guildDisplay(s.guildId, s.name);
            }
            if ("value".equals(field)) {
                return String.valueOf(byLosses ? s.losses : s.wins);
            }
            return null;
        }
        return null;
    }
}
