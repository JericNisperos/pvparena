package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code /guildwar} command — challenge-mode guild wars, registered at runtime so we never touch
 * PVP Arena's core command system. Same idempotent, reload-safe registration pattern as the queue
 * module's {@code /cyangpa}.
 *
 * <pre>
 *   /guildwar invite &lt;guild&gt; &lt;count&gt;   issue a challenge
 *   /guildwar accept | deny             respond to a challenge targeting your guild (officers only)
 *   /guildwar join | leave              join / leave your guild's roster during staging
 *   /guildwar cancel                    challenger cancels their own pending/staging war
 *   /guildwar spectate [arena]          watch an in-progress war from its spectator area
 *   /guildwar top [n]                   guild leaderboard
 *   /guildwar reload                    (admin) reload config + results from disk
 *   /guildwar reinstall                 (admin) reload all module jars from /mods (fresh classloader)
 * </pre>
 */
public class GuildWarCommand extends Command {

    static final String LABEL = "guildwar";
    /** Always-ours secondary label, so the command works even if {@code /guildwar} is taken/stale. */
    static final String LABEL_ALT = "gwc";

    /** Admins (op or this perm) / console may run {@code reload} and {@code reinstall}. */
    static final String ADMIN_PERM = "pvparena.cmds.guildwar.admin";

    /** Registered module name (matches {@code module.yml}); used by {@code reinstall}'s pa-modules calls. */
    private static final String MODULE_NAME = "CyanGuildWarChallenge";

    private static final String SUB_INVITE = "invite";
    private static final String SUB_ACCEPT = "accept";
    private static final String SUB_DENY = "deny";
    private static final String SUB_JOIN = "join";
    private static final String SUB_LEAVE = "leave";
    private static final String SUB_CANCEL = "cancel";
    private static final String SUB_SPECTATE = "spectate";
    private static final String SUB_TOP = "top";
    private static final String SUB_DEBUG = "debug";
    private static final String SUB_RELOAD = "reload";
    private static final String SUB_REINSTALL = "reinstall";

    /** Player-facing subcommands (for tab-completion). */
    private static final List<String> SUBS = Arrays.asList(
            SUB_INVITE, SUB_ACCEPT, SUB_DENY, SUB_JOIN, SUB_LEAVE, SUB_CANCEL, SUB_SPECTATE, SUB_TOP, SUB_DEBUG);
    /** Admin-only subcommands (only suggested to admins). */
    private static final List<String> ADMIN_SUBS = Arrays.asList(SUB_RELOAD, SUB_REINSTALL);

    public GuildWarCommand(final String label) {
        super(label,
                "Challenge another guild to a Guild War",
                "/" + label + " <guild> <count>",
                Collections.emptyList());
    }

    /**
     * Register {@code /guildwar} and {@code /gwc} (+ short aliases). Reload-safe: any prior instance of
     * our own command classes is removed first, so {@code /pa reloadall} picks up new command code
     * (the old "skip if label exists" approach left a stale command serving the label until restart).
     * If {@code /guildwar} is owned by another plugin we can't take it — {@code /gwc} always works.
     */
    static void ensureRegistered(final CommandMap commandMap) {
        if (commandMap == null) {
            return;
        }
        unregisterOurStaleCommands(commandMap);

        GuildWarAliasCommand.ensureRegistered(commandMap);
        registerLabel(commandMap, LABEL);
        registerLabel(commandMap, LABEL_ALT);
        syncCommands();
    }

    private static void registerLabel(final CommandMap commandMap, final String label) {
        if (commandMap.getCommand(label) != null) {
            CyanGuildWarChallenge.logger().warning("[GuildWarChallenge] /" + label
                    + " is already registered by another command — use /" + LABEL_ALT + " instead.");
            return;
        }
        commandMap.register("pvparena", new GuildWarCommand(label));
        CyanGuildWarChallenge.logger().info("[GuildWarChallenge] Registered /" + label);
    }

    /** Drop any previously-registered instances of our command classes (across classloader reloads). */
    private static void unregisterOurStaleCommands(final CommandMap commandMap) {
        final Set<String> ours = new HashSet<>(Arrays.asList(
                GuildWarCommand.class.getName(), GuildWarAliasCommand.class.getName()));
        final Map<String, Command> known = knownCommands(commandMap);
        if (known == null) {
            return;
        }
        try {
            known.values().removeIf(c -> c != null && ours.contains(c.getClass().getName()));
        } catch (final Throwable ignored) {
            // unmodifiable view on some forks — non-fatal, registration just falls back to skip-if-present
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(final CommandMap commandMap) {
        try {
            final Method m = commandMap.getClass().getMethod("getKnownCommands");
            m.setAccessible(true);
            return (Map<String, Command>) m.invoke(commandMap);
        } catch (final Throwable ignored) {
            // fall through to field access
        }
        try {
            final java.lang.reflect.Field f = commandMap.getClass().getDeclaredField("knownCommands");
            f.setAccessible(true);
            return (Map<String, Command>) f.get(commandMap);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        final String first = args[0].toLowerCase(Locale.ROOT);

        if (SUB_TOP.equals(first)) {
            GuildWarLeaderboard.show(sender, args.length > 1 ? args[1] : null);
            return true;
        }

        // Admin maintenance — runnable from console too (handled before the player-only gate).
        if (SUB_RELOAD.equals(first) || SUB_REINSTALL.equals(first)) {
            if (!isAdmin(sender)) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            if (SUB_RELOAD.equals(first)) {
                doReload(sender);
            } else {
                doReinstall(sender);
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use Guild War.");
            return true;
        }
        final Player player = (Player) sender;

        // Spectating is open to any player (not gated behind the challenge permission).
        if (SUB_SPECTATE.equals(first)) {
            GuildWarChallenge.spectate(player, args.length > 1 ? args[1] : null);
            return true;
        }

        if (!player.hasPermission(GuildWarChallenge.CMD_PERM) && !player.isOp()) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use Guild War.");
            return true;
        }

        switch (first) {
            case SUB_ACCEPT:
                GuildWarChallenge.accept(player);
                return true;
            case SUB_DENY:
                GuildWarChallenge.deny(player);
                return true;
            case SUB_JOIN:
                GuildWarChallenge.join(player);
                return true;
            case SUB_LEAVE:
                GuildWarChallenge.leave(player);
                return true;
            case SUB_CANCEL:
                GuildWarChallenge.cancel(player);
                return true;
            case SUB_DEBUG:
                GuildWarChallenge.debug(player);
                return true;
            case SUB_INVITE:
                // Challenge: /guildwar invite <guild> <count>
                if (args.length < 3) {
                    sendUsage(sender);
                    return true;
                }
                GuildWarChallenge.challenge(player, args[1], args[2]);
                return true;
            default:
                sendUsage(sender);
                return true;
        }
    }

    private static boolean isAdmin(final CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true; // console / command blocks
        }
        final Player p = (Player) sender;
        return p.isOp() || p.hasPermission(ADMIN_PERM);
    }

    /** Reload config + results from disk (no classloader reload). */
    private static void doReload(final CommandSender sender) {
        try {
            GuildWarConfig.get().load();
            GuildWarResultStore.get().load();
            GuildBridge.invalidate();
            sender.sendMessage(ChatColor.GREEN + "[GuildWar] Config and results reloaded from disk.");
        } catch (final Throwable t) {
            sender.sendMessage(ChatColor.RED + "[GuildWar] Reload failed: " + t.getMessage());
            CyanGuildWarChallenge.logger().warning("[GuildWarChallenge] /guildwar reload failed: " + t.getMessage());
        }
    }

    /**
     * Reinstall the module exactly like {@code /pa modules uninstall <m>} then {@code /pa modules
     * install <m>}: the jar is removed from {@code /mods}, then re-copied from {@code /files} (so a
     * newer jar in {@code /files} replaces the old one). If the jar is <b>missing</b> from
     * {@code /files} the install can't restore it and the module stays uninstalled — surfaced clearly
     * here. Dispatched as <i>console</i> so it works regardless of the caller's {@code pa modules} perm.
     */
    private static void doReinstall(final CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "[GuildWar] Reinstalling " + MODULE_NAME + " from /files...");
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pa modules uninstall " + MODULE_NAME);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pa modules install " + MODULE_NAME);

            if (PVPArena.getInstance().getAmm().hasLoadable(MODULE_NAME)) {
                sender.sendMessage(ChatColor.GREEN + "[GuildWar] " + MODULE_NAME
                        + " reinstalled from /files (now running the version there).");
            } else {
                sender.sendMessage(ChatColor.RED + "[GuildWar] " + MODULE_NAME
                        + " is now UNINSTALLED — its jar wasn't found in /files. Add pa_"
                        + "m_cyanguildwarchallenge.jar to /files and run /guildwar reinstall again.");
            }
        } catch (final Throwable t) {
            sender.sendMessage(ChatColor.RED + "[GuildWar] Reinstall failed: " + t.getMessage());
            CyanGuildWarChallenge.logger().warning("[GuildWarChallenge] /guildwar reinstall failed: " + t.getMessage());
        }
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }
        final Player player = (Player) sender;
        final GuildBridge guilds = GuildBridge.get();

        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            final List<String> out = new ArrayList<>();
            for (final String kw : SUBS) {
                if (kw.startsWith(prefix)) {
                    out.add(kw);
                }
            }
            if (isAdmin(player)) {
                for (final String kw : ADMIN_SUBS) {
                    if (kw.startsWith(prefix)) {
                        out.add(kw);
                    }
                }
            }
            return out;
        }

        // /guildwar invite <guild> — suggest online enemy guild tags.
        if (args.length == 2 && SUB_INVITE.equals(args[0].toLowerCase(Locale.ROOT))) {
            return onlineGuildNames(guilds, player, args[1].toLowerCase(Locale.ROOT));
        }

        // /guildwar spectate <arena> — suggest arenas with a war in progress.
        if (args.length == 2 && SUB_SPECTATE.equals(args[0].toLowerCase(Locale.ROOT))) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            final List<String> out = new ArrayList<>();
            for (final Challenge c : ChallengeRegistry.running()) {
                if (c.arenaName != null && c.arenaName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(c.arenaName);
                }
            }
            return out;
        }

        // /guildwar invite <guild> <count> — suggest min-count .. min(online own, online enemy, max).
        if (args.length == 3 && SUB_INVITE.equals(args[0].toLowerCase(Locale.ROOT))) {
            final UUID enemy = guilds.isAvailable() ? guilds.clanByQuery(args[1]) : null;
            if (enemy == null) {
                return Collections.emptyList();
            }
            final int max = GuildWarChallenge.maxSelectableCount(player, enemy);
            final int min = GuildWarConfig.get().minCount();
            final List<String> out = new ArrayList<>();
            for (int i = min; i <= max; i++) {
                final String s = Integer.toString(i);
                if (s.startsWith(args[2])) {
                    out.add(s);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }

    private static List<String> onlineGuildNames(final GuildBridge guilds, final Player player, final String prefix) {
        if (!guilds.isAvailable()) {
            return Collections.emptyList();
        }
        final UUID own = guilds.guildId(player);
        final List<String> out = new ArrayList<>();
        for (final UUID g : guilds.onlineGuilds()) {
            if (g.equals(own)) {
                continue;
            }
            final String name = GuildWarText.sanitize(guilds.clanName(g));
            if (!name.isEmpty() && !name.contains(" ") && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    private void sendUsage(final CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&eUsage: &r/" + LABEL + " invite <guild> <count> &7| accept | deny | join | leave | cancel | spectate | top"));
        if (isAdmin(sender)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7Admin: &r/" + LABEL + " reload &7(config+results) &r| reinstall &7(reload module jars)"));
        }
    }

    private static void syncCommands() {
        try {
            final Server server = Bukkit.getServer();
            final Method syncCommands = server.getClass().getMethod("syncCommands");
            syncCommands.setAccessible(true);
            syncCommands.invoke(server);
        } catch (final ReflectiveOperationException ignored) {
            // older servers: command still works, tab-completion appears after relog
        }
    }
}
