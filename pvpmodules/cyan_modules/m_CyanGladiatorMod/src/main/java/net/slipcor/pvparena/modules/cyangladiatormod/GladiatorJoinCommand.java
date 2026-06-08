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
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code /gladiatorjoin} — instant-join an available arena whose goal is {@code Gladiator}
 * (vanillajoin-style). Rejects players with no guild. Registered at runtime via the server command
 * map (no plugin.yml), idempotent + reload-safe so {@code /pa modules install/uninstall} works.
 */
public class GladiatorJoinCommand extends Command {

    static final String LABEL = "gladiatorjoin";
    private static final String PERM = "pvparena.cmds.gladiatorjoin";
    private static final String PER_ARENA_PERM = "gladiatorjoin";
    private static final String GOAL_NAME = "Gladiator";

    private static volatile boolean registered = false;

    public GladiatorJoinCommand() {
        super(LABEL, "Join an available Gladiator arena", "/" + LABEL, Collections.singletonList("gjoin"));
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
            unregisterStale();
            registerPermission();
            commandMap.register("pvparena", new GladiatorJoinCommand());
            syncCommands();
            registered = true;
            log("[GladiatorMod] registered command /" + LABEL);
        } catch (final Throwable t) {
            log("[GladiatorMod] could not register /" + LABEL + ": " + t.getMessage());
        }
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!GladiatorMod.goalInstalled()) {
            sender.sendMessage(ChatColor.RED + "Gladiator is not available (the Gladiator goal is not installed).");
            return true;
        }
        if (!(PermissionManager.hasAdminPerm(sender) || sender.hasPermission(PERM))) {
            sender.sendMessage(PermissionManager.getMissingPermissionMessage(PERM));
            return true;
        }
        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return true;
        }

        final Player player = (Player) sender;
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);
        if (aPlayer.getArena() != null) {
            aPlayer.getArena().msg(player, MSG.ERROR_ARENA_ALREADY_PART_OF, aPlayer.getArena().getName());
            return true;
        }

        final GuildBridge guilds = GuildBridge.get();
        if (!guilds.isAvailable() || !guilds.hasGuild(player)) {
            player.sendMessage(ChatColor.RED + "You must be in a guild to join a Gladiator arena.");
            return true;
        }

        final Set<Arena> available = ArenaManager.getArenas().stream()
                .filter(arena -> arena.getGoal() != null && GOAL_NAME.equalsIgnoreCase(arena.getGoal().getName()))
                .filter(arena -> !arena.isLocked())
                .filter(arena -> PermissionManager.hasExplicitArenaPerm(player, arena, PER_ARENA_PERM))
                .filter(arena -> !TeamManager.isArenaFull(arena))
                .filter(arena -> !(arena.isFightInProgress() && !arena.getConfig().getBoolean(CFG.JOIN_ALLOW_DURING_MATCH)))
                .filter(arena -> ArenaManager.checkJoinRegion(player, arena))
                .filter(arena -> !ArenaRegion.tooFarAway(arena, player))
                .collect(Collectors.toSet());

        if (available.isEmpty()) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return true;
        }

        // Prefer an arena already filling up (to gather a match), else random.
        final Arena target = available.stream()
                .filter(arena -> !arena.getEveryone().isEmpty())
                .max(Comparator.comparingInt(arena -> arena.getEveryone().size()))
                .orElseGet(() -> RandomUtils.getRandom(available, new Random()));

        if (target == null) {
            Arena.pmsg(player, MSG.ERROR_NO_ARENAS);
            return true;
        }

        WorkflowManager.handleJoin(target, player, new String[0]);
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        return Collections.emptyList();
    }

    // ---- registration plumbing ------------------------------------------------------------------

    private static void registerPermission() {
        try {
            if (Bukkit.getPluginManager().getPermission(PERM) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(PERM, PermissionDefault.TRUE));
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

    private static void unregisterStale() {
        final String target = GladiatorJoinCommand.class.getName();
        for (final org.bukkit.command.Command cmd : new java.util.ArrayList<>(
                listKnownCommands())) {
            if (cmd != null && target.equals(cmd.getClass().getName())) {
                cmd.unregister(getCommandMapQuietly());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<Command> listKnownCommands() {
        try {
            final CommandMap map = getCommandMap();
            final Method getKnown = map.getClass().getMethod("getKnownCommands");
            getKnown.setAccessible(true);
            return ((java.util.Map<String, Command>) getKnown.invoke(map)).values();
        } catch (final Throwable t) {
            return Collections.emptyList();
        }
    }

    private static CommandMap getCommandMapQuietly() {
        try {
            return getCommandMap();
        } catch (final Throwable t) {
            return null;
        }
    }

    private static void log(final String msg) {
        final PVPArena plugin = PVPArena.getInstance();
        (plugin != null ? plugin.getLogger() : Bukkit.getLogger()).info(msg);
    }
}
