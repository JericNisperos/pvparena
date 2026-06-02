package net.slipcor.pvparena.modules.cyanvanillajoin;

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
 * The {@code /cyanpa} command — Cyan's own PVP Arena join helpers, registered at runtime so we
 * never touch PVP Arena's core command system.
 *
 * <p>Subcommands implemented so far:</p>
 * <ul>
 *     <li>{@code /cyanpa vanillajoin} (alias {@code vj}) — see {@link VanillaJoin}.</li>
 * </ul>
 *
 * <p>Deliberately unique label ({@code cyanpa}, not {@code cyan}) to avoid clashing with any
 * unrelated {@code /cyan} command from another plugin.</p>
 */
public class CyanPaCommand extends Command {

    static final String LABEL = "cyanpa";
    private static final String SUB_VANILLAJOIN = "vanillajoin";
    private static final String SUB_VANILLAJOIN_SHORT = "vj";

    public CyanPaCommand() {
        super(LABEL,
                "Cyan PVP Arena join helpers",
                "/" + LABEL + " <" + SUB_VANILLAJOIN + ">",
                Collections.emptyList());
    }

    /**
     * Idempotently register {@code /cyanpa} into the server command map. Safe to call multiple
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
        commandMap.register("pvparena", new CyanPaCommand());
        syncCommands();
        CyanVanillaJoin.logger().info("[CyanVanillaJoin] Registered command /" + LABEL + " " + SUB_VANILLAJOIN);
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        final String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        if (SUB_VANILLAJOIN.equals(sub) || SUB_VANILLAJOIN_SHORT.equals(sub)) {
            VanillaJoin.handle(sender, subArgs);
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
            if (SUB_VANILLAJOIN.startsWith(prefix)) {
                out.add(SUB_VANILLAJOIN);
            }
            if (SUB_VANILLAJOIN_SHORT.startsWith(prefix)) {
                out.add(SUB_VANILLAJOIN_SHORT);
            }
            return out;
        }
        return Collections.emptyList();
    }

    private void sendUsage(final CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&eUsage: &r/" + LABEL + " " + SUB_VANILLAJOIN));
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
