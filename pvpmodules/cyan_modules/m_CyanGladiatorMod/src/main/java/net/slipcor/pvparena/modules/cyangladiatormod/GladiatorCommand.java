package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.core.RandomUtils;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.managers.PermissionManager;
import net.slipcor.pvparena.managers.TeamManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import net.slipcor.pvparena.regions.ArenaRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code /gladiator} — the Guild Royal Rumble command, registered at runtime via the server command
 * map (no plugin.yml), idempotent + reload-safe so {@code /gladiator reinstall} (and {@code /pa
 * modules install/uninstall}) work.
 *
 * <pre>
 *   /gladiator join                instant-join an available Gladiator arena (alias: /gjoin, /gladiatorjoin)
 *   /gladiator top [n]             guild leaderboard
 *   /gladiator spectate [arena]    watch an in-progress rumble
 *   /gladiator reload              (admin) reload config + results from disk
 *   /gladiator reinstall           (admin) reinstall the module jar from /files (fresh classloader)
 * </pre>
 */
public class GladiatorCommand extends Command {

    static final String LABEL = "gladiator";
    private static final List<String> ALIASES = Arrays.asList("glad", "gladiatorjoin", "gjoin");
    /** Labels that mean "just join" regardless of args. */
    private static final Set<String> JOIN_LABELS = new java.util.HashSet<>(Arrays.asList("gladiatorjoin", "gjoin"));

    private static final String JOIN_PERM = "pvparena.cmds.gladiatorjoin";
    private static final String PER_ARENA_PERM = "gladiatorjoin";
    private static final String ADMIN_PERM = "pvparena.cmds.gladiator.admin";
    private static final String GOAL_NAME = "Gladiator";
    private static final String MODULE_NAME = "GladiatorMod";

    private static final String SUB_JOIN = "join";
    private static final String SUB_TOP = "top";
    private static final String SUB_SPECTATE = "spectate";
    private static final String SUB_RELOAD = "reload";
    private static final String SUB_REINSTALL = "reinstall";

    private static final List<String> SUBS = Arrays.asList(SUB_JOIN, SUB_TOP, SUB_SPECTATE);
    private static final List<String> ADMIN_SUBS = Arrays.asList(SUB_RELOAD, SUB_REINSTALL);

    private static volatile boolean registered = false;

    public GladiatorCommand(final String label) {
        super(label, "Join or manage a Gladiator (Guild Royal Rumble)", "/" + label + " join", Collections.emptyList());
    }

    static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        try {
            final CommandMap commandMap = getCommandMap();
            if (commandMap == null) {
                return;
            }
            unregisterStale(commandMap);
            registerPermissions();
            registerLabel(commandMap, LABEL);
            for (final String alias : ALIASES) {
                registerLabel(commandMap, alias);
            }
            syncCommands();
            registered = true;
        } catch (final Throwable t) {
            log("[Gladiator] could not register /" + LABEL + ": " + t.getMessage());
        }
    }

    private static void registerLabel(final CommandMap commandMap, final String label) {
        if (commandMap.getCommand(label) != null) {
            return; // taken (by us after reload-cleanup, or another plugin) — skip
        }
        commandMap.register("pvparena", new GladiatorCommand(label));
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!GladiatorMod.goalInstalled()) {
            sender.sendMessage(ChatColor.RED + "Gladiator is not available (the Gladiator goal is not installed in /goals).");
            return true;
        }

        final boolean joinLabel = JOIN_LABELS.contains(label.toLowerCase(Locale.ROOT));
        final String sub = joinLabel ? SUB_JOIN
                : (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT));

        // Admin maintenance — runnable from console too.
        if (SUB_RELOAD.equals(sub) || SUB_REINSTALL.equals(sub)) {
            if (!isAdmin(sender)) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            if (SUB_RELOAD.equals(sub)) {
                doReload(sender);
            } else {
                doReinstall(sender);
            }
            return true;
        }

        if (SUB_TOP.equals(sub)) {
            GladiatorLeaderboard.show(sender, args.length > 1 ? args[1] : null);
            return true;
        }

        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return true;
        }
        final Player player = (Player) sender;

        if (SUB_SPECTATE.equals(sub)) {
            spectate(player, args.length > 1 ? args[1] : null);
            return true;
        }
        if (SUB_JOIN.equals(sub)) {
            join(player);
            return true;
        }
        sendUsage(sender);
        return true;
    }

    // ---- join (instant-join an open Gladiator arena) -------------------------------------------

    private static void join(final Player player) {
        if (!(PermissionManager.hasAdminPerm(player) || player.hasPermission(JOIN_PERM))) {
            player.sendMessage(PermissionManager.getMissingPermissionMessage(JOIN_PERM));
            return;
        }
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);
        if (aPlayer.getArena() != null) {
            aPlayer.getArena().msg(player, MSG.ERROR_ARENA_ALREADY_PART_OF, aPlayer.getArena().getName());
            return;
        }
        final GuildBridge guilds = GuildBridge.get();
        if (!guilds.isAvailable() || !guilds.hasGuild(player)) {
            player.sendMessage(ChatColor.RED + "You must be in a guild to join a Gladiator arena.");
            return;
        }

        final Set<Arena> available = gladiatorArenas().stream()
                .filter(arena -> !arena.isLocked())
                .filter(arena -> PermissionManager.hasExplicitArenaPerm(player, arena, PER_ARENA_PERM))
                .filter(arena -> !TeamManager.isArenaFull(arena))
                .filter(arena -> !(arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH)))
                .filter(arena -> ArenaManager.checkJoinRegion(player, arena))
                .filter(arena -> !ArenaRegion.tooFarAway(arena, player))
                .collect(Collectors.toSet());

        if (available.isEmpty()) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        // Prefer an arena already filling up (to gather a match), else random.
        final Arena target = available.stream()
                .filter(arena -> !arena.getEveryone().isEmpty())
                .max(Comparator.comparingInt(arena -> arena.getEveryone().size()))
                .orElseGet(() -> RandomUtils.getRandom(available, new Random()));

        if (target == null) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }
        WorkflowManager.handleJoin(target, player, new String[0]);
    }

    // ---- spectate ------------------------------------------------------------------------------

    private static void spectate(final Player player, final String arenaArg) {
        if (ArenaPlayer.fromPlayer(player).getArena() != null) {
            player.sendMessage(ChatColor.RED + "You're already in an arena — leave it first.");
            return;
        }
        final Arena arena = resolveSpectateArena(player, arenaArg);
        if (arena == null) {
            return; // already messaged why
        }
        boolean ok;
        try {
            ok = WorkflowManager.handleSpectate(arena, player);
        } catch (final RuntimeException e) {
            log("[Gladiator] handleSpectate threw for " + player.getName() + " in '" + arena.getName() + "': " + e.getMessage());
            ok = false;
        }
        if (!ok) {
            player.sendMessage(ChatColor.RED + "Couldn't spectate that Gladiator — the arena needs the "
                    + "Spectate module and a 'spectator' spawn. Ask an admin.");
        }
    }

    private static Arena resolveSpectateArena(final Player player, final String arenaArg) {
        final List<Arena> running = gladiatorArenas().stream()
                .filter(Arena::isFightInProgress)
                .collect(Collectors.toList());

        if (arenaArg != null && !arenaArg.trim().isEmpty()) {
            final Arena named = running.stream()
                    .filter(a -> a.getName().equalsIgnoreCase(arenaArg.trim()))
                    .findFirst().orElse(null);
            if (named == null) {
                player.sendMessage(ChatColor.RED + "No Gladiator is in progress in an arena named "
                        + ChatColor.YELLOW + arenaArg + ChatColor.RED + ".");
            }
            return named;
        }
        if (running.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No Gladiator is in progress to spectate.");
            return null;
        }
        if (running.size() > 1) {
            final String names = running.stream().map(Arena::getName).collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.RED + "Several Gladiators are running — pick one: "
                    + ChatColor.GRAY + "/gladiator spectate <arena> " + ChatColor.DARK_GRAY + "(" + names + ")");
            return null;
        }
        return running.get(0);
    }

    // ---- admin: reload / reinstall -------------------------------------------------------------

    private static void doReload(final CommandSender sender) {
        try {
            GladiatorConfig.get().load();
            GladiatorResultStore.get().load();
            GuildBridge.invalidate();
            sender.sendMessage(ChatColor.GREEN + "[Gladiator] Config and results reloaded from disk.");
        } catch (final Throwable t) {
            sender.sendMessage(ChatColor.RED + "[Gladiator] Reload failed: " + t.getMessage());
            log("[Gladiator] /gladiator reload failed: " + t.getMessage());
        }
    }

    /**
     * Reinstall the module like {@code /pa modules uninstall GladiatorMod} then {@code install}: the
     * jar is removed from {@code /mods}, then re-copied from {@code /files}. (The thin goal jar in
     * {@code /goals} is unaffected — reload it with {@code /pa reloadall} if it ever changes.)
     */
    private static void doReinstall(final CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "[Gladiator] Reinstalling " + MODULE_NAME + " from /files...");
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pa modules uninstall " + MODULE_NAME);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pa modules install " + MODULE_NAME);

            if (PVPArena.getInstance().getAmm().hasLoadable(MODULE_NAME)) {
                sender.sendMessage(ChatColor.GREEN + "[Gladiator] " + MODULE_NAME
                        + " reinstalled from /files (now running the version there).");
            } else {
                sender.sendMessage(ChatColor.RED + "[Gladiator] " + MODULE_NAME
                        + " is now UNINSTALLED — its jar wasn't found in /files. Add "
                        + "pa_m_cyangladiatormod.jar to /files and run /gladiator reinstall again.");
            }
        } catch (final Throwable t) {
            sender.sendMessage(ChatColor.RED + "[Gladiator] Reinstall failed: " + t.getMessage());
            log("[Gladiator] /gladiator reinstall failed: " + t.getMessage());
        }
    }

    // ---- tab-complete --------------------------------------------------------------------------

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (JOIN_LABELS.contains(alias.toLowerCase(Locale.ROOT))) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            final List<String> out = new ArrayList<>();
            for (final String kw : SUBS) {
                if (kw.startsWith(prefix)) {
                    out.add(kw);
                }
            }
            if (isAdmin(sender)) {
                for (final String kw : ADMIN_SUBS) {
                    if (kw.startsWith(prefix)) {
                        out.add(kw);
                    }
                }
            }
            return out;
        }
        if (args.length == 2 && SUB_SPECTATE.equals(args[0].toLowerCase(Locale.ROOT))) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            return gladiatorArenas().stream()
                    .filter(Arena::isFightInProgress)
                    .map(Arena::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Set<Arena> gladiatorArenas() {
        return ArenaManager.getArenas().stream()
                .filter(arena -> arena.getGoal() != null && GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName()))
                .collect(Collectors.toSet());
    }

    private static boolean isAdmin(final CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true; // console / command blocks
        }
        final Player p = (Player) sender;
        return p.isOp() || p.hasPermission(ADMIN_PERM);
    }

    private void sendUsage(final CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&eUsage: &r/" + LABEL + " join &7| top | spectate"));
        if (isAdmin(sender)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7Admin: &r/" + LABEL + " reload &7(config+results) &r| reinstall &7(reload module jar)"));
        }
    }

    private static void registerPermissions() {
        addPermission(JOIN_PERM, PermissionDefault.TRUE);
        addPermission(ADMIN_PERM, PermissionDefault.OP);
    }

    private static void addPermission(final String node, final PermissionDefault def) {
        try {
            if (Bukkit.getPluginManager().getPermission(node) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(node, def));
            }
        } catch (final Throwable ignored) {
            // already registered / not critical
        }
    }

    private static CommandMap getCommandMap() throws ReflectiveOperationException {
        final Server server = Bukkit.getServer();
        final Method getCommandMap = server.getClass().getMethod("getCommandMap");
        getCommandMap.setAccessible(true);
        return (CommandMap) getCommandMap.invoke(server);
    }

    private static void syncCommands() {
        try {
            final Server server = Bukkit.getServer();
            final Method syncCommands = server.getClass().getMethod("syncCommands");
            syncCommands.setAccessible(true);
            syncCommands.invoke(server);
        } catch (final ReflectiveOperationException ignored) {
            // older servers: command still works, tab-completion shows after relog
        }
    }

    @SuppressWarnings("unchecked")
    private static void unregisterStale(final CommandMap commandMap) {
        final String target = GladiatorCommand.class.getName();
        try {
            final Method m = commandMap.getClass().getMethod("getKnownCommands");
            m.setAccessible(true);
            final Map<String, Command> known = (Map<String, Command>) m.invoke(commandMap);
            if (known != null) {
                known.values().removeIf(c -> c != null && target.equals(c.getClass().getName()));
            }
        } catch (final Throwable ignored) {
            // unmodifiable / missing on some forks — registration falls back to skip-if-present
        }
    }

    private static void log(final String msg) {
        final PVPArena plugin = PVPArena.getInstance();
        (plugin != null ? plugin.getLogger() : Bukkit.getLogger()).info(msg);
    }
}
