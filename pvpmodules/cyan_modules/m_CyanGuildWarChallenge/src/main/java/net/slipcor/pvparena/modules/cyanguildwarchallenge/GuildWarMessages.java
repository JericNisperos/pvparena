package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and sends GuildWar chat: server broadcasts and per-player <b>clickable</b> prompts
 * ({@code [Accept] [Deny]}, {@code [Join the Guild War]}).
 *
 * <p>Clickable text is delivered via a console {@code /tellraw <player> <json>} so the module compiles
 * without the {@code net.md-5:bungeecord-chat} classes (excluded from our {@code spigot-api}
 * dependency). Legacy {@code §} color codes inside the JSON {@code text} render fine on modern
 * clients. All interpolated guild/player names are sanitized upstream and JSON-escaped here, so a
 * crafted name can't break out of the JSON or inject extra components.</p>
 */
final class GuildWarMessages {

    static final String PREFIX = ChatColor.GOLD + "[GuildWar] " + ChatColor.RESET;

    private GuildWarMessages() {
    }

    static void broadcast(final String legacyMessage) {
        Bukkit.broadcastMessage(PREFIX + legacyMessage);
    }

    static void send(final Player to, final String legacyMessage) {
        if (to != null) {
            to.sendMessage(PREFIX + legacyMessage);
        }
    }

    /** DM the challenged-guild member an {@code [Accept] [Deny]} prompt for a {@code gamemodeLabel} war. */
    static void sendAcceptPrompt(final Player to, final String challengerLabel, final int count,
                                 final String gamemodeLabel) {
        final String lead = PREFIX + ChatColor.YELLOW + challengerLabel + ChatColor.GRAY
                + " challenged your guild to a " + ChatColor.WHITE + count + "v" + count
                + ChatColor.GRAY + " " + ChatColor.AQUA + gamemodeLabel + ChatColor.GRAY + " war. ";
        final List<Button> buttons = new ArrayList<>();
        buttons.add(new Button("[Accept]", "green", "/guildwar accept", "Accept the challenge"));
        buttons.add(new Button(" ", null, null, null));
        buttons.add(new Button("[Deny]", "red", "/guildwar deny", "Deny the challenge"));
        sendClickable(to, lead, buttons);
        sendHint(to, "/guildwar accept", "/guildwar deny");
        if (to != null) {
            to.sendMessage(ChatColor.DARK_GRAY + "Want a different mode? Counter with your own "
                    + ChatColor.GRAY + "/guildwar invite <guild> <count> [mode]");
        }
    }

    /** DM a guild member a {@code [Join the Guild War]} prompt during staging. */
    static void sendJoinPrompt(final Player to, final String prompt) {
        final String lead = PREFIX + ChatColor.GRAY + prompt + " ";
        final List<Button> buttons = new ArrayList<>();
        buttons.add(new Button("[Join the Guild War]", "aqua", "/gwjoin", "Join your guild's war roster"));
        sendClickable(to, lead, buttons);
        sendHint(to, "/gwjoin");
    }

    /** A plain follow-up line so players who can't click still know exactly what to type. */
    private static void sendHint(final Player to, final String... commands) {
        if (to == null) {
            return;
        }
        to.sendMessage(ChatColor.DARK_GRAY + "Can't click? Type " + ChatColor.GRAY
                + String.join(ChatColor.DARK_GRAY + " or " + ChatColor.GRAY, commands));
    }

    // ---------------------------------------------------------------------------------- internals

    private static void sendClickable(final Player to, final String leadLegacy, final List<Button> buttons) {
        if (to == null) {
            return;
        }
        final StringBuilder json = new StringBuilder();
        json.append("{\"text\":\"").append(escape(leadLegacy)).append("\",\"extra\":[");
        boolean first = true;
        for (final Button b : buttons) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(b.toJson());
        }
        json.append("]}");

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "tellraw " + to.getName() + " " + json);
        } catch (final Throwable t) {
            // Fallback: plain (non-clickable) message so the player still sees the prompt + how to act.
            final StringBuilder plain = new StringBuilder(leadLegacy);
            for (final Button b : buttons) {
                if (b.command != null) {
                    plain.append(ChatColor.WHITE).append(b.label)
                            .append(ChatColor.GRAY).append(" (").append(b.command).append(") ");
                }
            }
            to.sendMessage(plain.toString());
        }
    }

    /** Escape a string for embedding inside a JSON string literal. */
    private static String escape(final String s) {
        final StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** One clickable (or plain spacer) chat component. */
    private static final class Button {
        final String label;
        final String color;   // JSON color name, or null
        final String command; // run_command value, or null for a plain spacer
        final String hover;

        Button(final String label, final String color, final String command, final String hover) {
            this.label = label;
            this.color = color;
            this.command = command;
            this.hover = hover;
        }

        String toJson() {
            final StringBuilder b = new StringBuilder();
            b.append("{\"text\":\"").append(escape(label)).append('"');
            if (color != null) {
                b.append(",\"color\":\"").append(color).append('"');
                b.append(",\"bold\":true");
            }
            if (command != null) {
                b.append(",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"")
                        .append(escape(command)).append("\"}");
            }
            if (hover != null) {
                b.append(",\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"")
                        .append(escape(hover)).append("\"}");
            }
            b.append('}');
            return b.toString();
        }
    }
}
