package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Short standalone aliases for the in-staging Guild War roster actions, so a player who can't click
 * the chat buttons (old client, chat-click disabled) can simply type a command:
 * <pre>/gwjoin  /gwleave</pre>
 * Each routes to the same {@link GuildWarChallenge} handler as the matching {@code /guildwar} subcommand.
 *
 * <p>Accept/deny are intentionally <b>not</b> aliased — those are official subcommands
 * ({@code /guildwar accept}, {@code /guildwar deny}).</p>
 */
public class GuildWarAliasCommand extends Command {

    enum Action { ACCEPT, DENY, JOIN, LEAVE }

    private final Action action;

    private GuildWarAliasCommand(final String label, final Action action) {
        super(label, "Guild War: " + action.name().toLowerCase(java.util.Locale.ROOT),
                "/" + label, Collections.emptyList());
        this.action = action;
    }

    /** Register the roster aliases (idempotent, reload-safe). Accept/deny are official subcommands. */
    static void ensureRegistered(final CommandMap commandMap) {
        if (commandMap == null) {
            return;
        }
        register(commandMap, "gwjoin", Action.JOIN);
        register(commandMap, "gwleave", Action.LEAVE);
    }

    private static void register(final CommandMap commandMap, final String label, final Action action) {
        if (commandMap.getCommand(label) != null) {
            return;
        }
        commandMap.register("pvparena", new GuildWarAliasCommand(label, action));
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use Guild War.");
            return true;
        }
        final Player player = (Player) sender;
        if (!player.hasPermission(GuildWarChallenge.CMD_PERM) && !player.isOp()) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use Guild War.");
            return true;
        }
        switch (this.action) {
            case ACCEPT:
                GuildWarChallenge.accept(player);
                break;
            case DENY:
                GuildWarChallenge.deny(player);
                break;
            case JOIN:
                GuildWarChallenge.join(player);
                break;
            case LEAVE:
                GuildWarChallenge.leave(player);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        return Collections.emptyList();
    }
}
