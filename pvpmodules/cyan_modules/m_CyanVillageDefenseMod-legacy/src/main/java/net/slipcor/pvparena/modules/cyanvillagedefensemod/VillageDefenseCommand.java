package net.slipcor.pvparena.modules.cyanvillagedefensemod;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code /vdefense} — the Village Defense command, registered at runtime via the server command map
 * (no plugin.yml), idempotent + reload-safe so {@code /vdefense reinstall} (and {@code /pa modules
 * install/uninstall}) work.
 *
 * <pre>
 *   /vdefense join             instant-join an available VillageDefense arena (alias: /villagedefense)
 *   /vdefense leave            leave the VillageDefense arena you are in
 *   /vdefense reload           (admin) reload the config from disk
 *   /vdefense reinstall        (admin) reinstall the module jar from /files (fresh classloader)
 * </pre>
 */
public class VillageDefenseCommand extends Command {

    static final String LABEL = "vdefense";
    private static final List<String> ALIASES = Collections.singletonList("villagedefense");

    private static final String JOIN_PERM = "pvparena.cmds.villagedefense";
    private static final String ADMIN_PERM = "pvparena.cmds.villagedefense.admin";
    private static final String MODULE_NAME = "VillageDefenseMod";

    private static final String SUB_JOIN = "join";
    private static final String SUB_LEAVE = "leave";
    private static final String SUB_RELOAD = "reload";
    private static final String SUB_REINSTALL = "reinstall";

    private static final List<String> SUBS = Arrays.asList(SUB_JOIN, SUB_LEAVE);
    private static final List<String> ADMIN_SUBS = Arrays.asList(SUB_RELOAD, SUB_REINSTALL);

    /** Arenas we already nagged the console about (setup hints), per module lifetime. */
    private static final Set<String> SETUP_HINTED = new HashSet<>();

    private static volatile boolean registered = false;

    public VillageDefenseCommand(final String label) {
        super(label, "Join or leave a Village Defense match", "/" + label + " join", Collections.emptyList());
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
            log("[VillageDefense] could not register /" + LABEL + ": " + t.getMessage());
        }
    }

    private static void registerLabel(final CommandMap commandMap, final String label) {
        if (commandMap.getCommand(label) != null) {
            return; // taken (by us after reload-cleanup, or another plugin) — skip
        }
        commandMap.register("pvparena", new VillageDefenseCommand(label));
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!VillageDefenseMod.goalInstalled()) {
            sender.sendMessage(ChatColor.RED + "Village Defense is not available (the VillageDefense goal is not installed in /goals).");
            return true;
        }

        final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

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

        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return true;
        }
        final Player player = (Player) sender;

        if (SUB_JOIN.equals(sub)) {
            join(player);
            return true;
        }
        if (SUB_LEAVE.equals(sub)) {
            leave(player);
            return true;
        }
        sendUsage(sender);
        return true;
    }

    // ---- join (instant-join an open VillageDefense arena) ---------------------------------------

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

        final Set<Arena> available = villageDefenseArenas().stream()
                .filter(arena -> !arena.isLocked())
                .filter(arena -> !TeamManager.isArenaFull(arena))
                .filter(arena -> !(arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH)))
                .filter(arena -> ArenaManager.checkJoinRegion(player, arena))
                .filter(arena -> !ArenaRegion.tooFarAway(arena, player))
                .collect(Collectors.toSet());

        if (available.isEmpty()) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }

        // Prefer an arena already filling up (to gather a group), else random.
        final Arena target = available.stream()
                .filter(arena -> !arena.getEveryone().isEmpty())
                .max(Comparator.comparingInt(arena -> arena.getEveryone().size()))
                .orElseGet(() -> RandomUtils.getRandom(available, new Random()));

        if (target == null) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return;
        }
        hintSetupOnce(target);
        WorkflowManager.handleJoin(target, player, new String[0]);
    }

    // ---- leave -----------------------------------------------------------------------------------

    private static void leave(final Player player) {
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);
        final Arena arena = aPlayer.getArena();
        if (arena == null) {
            player.sendMessage(ChatColor.RED + "You are not in a Village Defense arena.");
            return;
        }
        if (arena.getGoal() == null || !VillageDefenseMod.GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName())) {
            player.sendMessage(ChatColor.RED + "You are in " + ChatColor.YELLOW + arena.getName()
                    + ChatColor.RED + ", not a Village Defense arena — use " + ChatColor.GRAY + "/pa leave"
                    + ChatColor.RED + ".");
            return;
        }
        arena.playerLeave(player, CFG.TP_EXIT, false, false, false);
    }

    /**
     * One-time console hint per arena: the auto-start countdown only behaves with
     * {@code ready.minPlayers: 1} (so solo matches can begin) and {@code ready.enforceCountdown: true}
     * (so later joins don't cancel the running countdown).
     */
    private static void hintSetupOnce(final Arena arena) {
        if (!SETUP_HINTED.add(arena.getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        if (arena.getConfig().getInt(CFG.READY_MINPLAYERS) > 1) {
            log("[VillageDefense] Arena '" + arena.getName() + "' has ready.minPlayers > 1 — the auto-start "
                    + "countdown will refuse to start solo matches. Recommended: /pa " + arena.getName()
                    + " set ready.minPlayers 1");
        }
        if (!arena.getConfig().getBoolean(CFG.READY_ENFORCECOUNTDOWN)) {
            log("[VillageDefense] Arena '" + arena.getName() + "' has ready.enforceCountdown disabled — each "
                    + "later join restarts the auto-start countdown. Recommended: /pa " + arena.getName()
                    + " set ready.enforceCountdown true");
        }
    }

    // ---- admin: reload / reinstall ---------------------------------------------------------------

    private static void doReload(final CommandSender sender) {
        try {
            VillageDefenseConfig.get().load();
            sender.sendMessage(ChatColor.GREEN + "[VillageDefense] Config reloaded from disk.");
        } catch (final Throwable t) {
            sender.sendMessage(ChatColor.RED + "[VillageDefense] Reload failed: " + t.getMessage());
            log("[VillageDefense] /vdefense reload failed: " + t.getMessage());
        }
    }

    /**
     * Reinstall the module like {@code /pa modules uninstall VillageDefenseMod} then {@code install}:
     * the jar is removed from {@code /mods}, then re-copied from {@code /files}. (The thin goal jar in
     * {@code /goals} is unaffected — reload it with {@code /pa reloadall} if it ever changes.)
     */
    private static void doReinstall(final CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "[VillageDefense] Reinstalling " + MODULE_NAME + " from /files...");
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pa modules uninstall " + MODULE_NAME);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pa modules install " + MODULE_NAME);

            if (PVPArena.getInstance().getAmm().hasLoadable(MODULE_NAME)) {
                sender.sendMessage(ChatColor.GREEN + "[VillageDefense] " + MODULE_NAME
                        + " reinstalled from /files (now running the version there).");
            } else {
                sender.sendMessage(ChatColor.RED + "[VillageDefense] " + MODULE_NAME
                        + " is now UNINSTALLED — its jar wasn't found in /files. Add "
                        + "pa_m_cyanvillagedefensemod.jar to /files and run /vdefense reinstall again.");
            }
        } catch (final Throwable t) {
            sender.sendMessage(ChatColor.RED + "[VillageDefense] Reinstall failed: " + t.getMessage());
            log("[VillageDefense] /vdefense reinstall failed: " + t.getMessage());
        }
    }

    // ---- tab-complete ----------------------------------------------------------------------------

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
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
        return Collections.emptyList();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Set<Arena> villageDefenseArenas() {
        return ArenaManager.getArenas().stream()
                .filter(arena -> arena.getGoal() != null
                        && VillageDefenseMod.GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName()))
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
                "&eUsage: &r/" + LABEL + " join &7| leave"));
        if (isAdmin(sender)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7Admin: &r/" + LABEL + " reload &7(config) &r| reinstall &7(reload module jar)"));
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
        final String target = VillageDefenseCommand.class.getName();
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
