package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import org.bukkit.ChatColor;

import java.util.UUID;

/** Small text helpers: chat-injection-safe sanitizing and guild display labels. */
final class GuildWarText {

    private GuildWarText() {
    }

    /**
     * Make a player-set guild name/tag safe to put in a broadcast: strip color codes and control
     * characters (newlines, etc.) so a crafted name can't spoof extra chat lines. Returns "" when the
     * value is null/blank after cleaning.
     */
    static String sanitize(final String value) {
        if (value == null) {
            return "";
        }
        final String noColor = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value));
        return noColor.replaceAll("\\p{Cntrl}", "").trim();
    }

    /** A human-readable, sanitized label for a guild: its name/tag, else a short UUID. */
    static String guildLabel(final UUID guildId) {
        if (guildId != null) {
            final String clean = sanitize(GuildBridge.get().clanName(guildId));
            if (!clean.isEmpty()) {
                return clean;
            }
            return "guild " + guildId.toString().substring(0, 8);
        }
        return "a guild";
    }
}
