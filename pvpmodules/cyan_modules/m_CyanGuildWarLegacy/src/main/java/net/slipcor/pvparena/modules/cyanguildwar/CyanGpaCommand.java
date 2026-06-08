package net.slipcor.pvparena.modules.cyanguildwar;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /cyangpa} command — Cyan's own Guild PVP Arena helpers, registered at runtime so we
 * never touch PVP Arena's core command system.
 *
 * <p>Subcommands implemented so far:</p>
 * <ul>
 *     <li>{@code /cyangpa guildwar} (alias {@code gw}) — toggle the global guild-war queue,
 *         see {@link GuildWar}.</li>
 * </ul>
 *
 * <p>Deliberately a <b>distinct</b> label from {@code /cyanpa} (owned by {@code m_CyanVanillaJoin})
 * so the two modules never clash and stay independently enable/disable-able. Future guild commands
 * (leaderboards, stats, …) live under {@code /cyangpa <sub>} in this same module.</p>
 */
public class CyanGpaCommand extends Command {

    static final String LABEL = "cyangpa";
    private static final String SUB_GUILDWAR = "guildwar";
    private static final String SUB_GUILDWAR_SHORT = "gw";

    public CyanGpaCommand() {
        super(LABEL,
                "Cyan Guild PVP Arena helpers",
                "/" + LABEL + " <" + SUB_GUILDWAR + ">",
                Collections.emptyList());
    }

    /**
     * Idempotently register {@code /cyangpa} into the server command map. Safe to call multiple
     * times (startup static-init, per-arena configParse, /pa reloadall) — if the label is already
     * present we leave it be.
     */
    static void ensureRegistered(final CommandMap commandMap) {
        if (commandMap == null) {
            return;
        }
        if (commandMap.getCommand(LABEL) != null) {
            return; // already registered (including after a module reload)
        }
        commandMap.register("pvparena", new CyanGpaCommand());
        syncCommands();
        CyanGuildWar.logger().info("[CyanGuildWar] Registered command /" + LABEL + " " + SUB_GUILDWAR);
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        final String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        if (SUB_GUILDWAR.equals(sub) || SUB_GUILDWAR_SHORT.equals(sub)) {
            GuildWar.handle(sender, subArgs);
            return true;
        }

        sendUsage(sender);
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            final List<String> out = new ArrayList<>();
            if (SUB_GUILDWAR.startsWith(prefix)) {
                out.add(SUB_GUILDWAR);
            }
            if (SUB_GUILDWAR_SHORT.startsWith(prefix)) {
                out.add(SUB_GUILDWAR_SHORT);
            }
            return out;
        }
        if (args.length == 2) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            if (SUB_GUILDWAR.equals(sub) || SUB_GUILDWAR_SHORT.equals(sub)) {
                if ("top".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    return Collections.singletonList("top");
                }
            }
        }
        return Collections.emptyList();
    }

    private void sendUsage(final CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&eUsage: &r/" + LABEL + " " + SUB_GUILDWAR));
    }

    private static void syncCommands() {
        // Best-effort: push the freshly-registered command to clients for live tab-completion.
        try {
            final Server server = Bukkit.getServer();
            final Method syncCommands = server.getClass().getMethod("syncCommands");
            syncCommands.setAccessible(true);
            syncCommands.invoke(server);
        } catch (final ReflectiveOperationException ignored) {
            // Older servers without syncCommands(): the command still works, tab-completion shows after relog.
        }
    }
}
