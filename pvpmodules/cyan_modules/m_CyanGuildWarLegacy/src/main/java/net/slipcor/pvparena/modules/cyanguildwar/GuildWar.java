package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.managers.ArenaManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The GuildWar controller: command handling, the global queue, distinct-guild matchmaking, atomic
 * arena claiming + auto-join, and result resolution (win / loss / lockout).
 *
 * <p>All methods run on the Bukkit main thread (command + events), so the shared {@link GuildWarQueue}
 * and {@link GuildWarMatch} registry need no synchronization. See {@code plans/guildwar/00-plan.md}.</p>
 */
final class GuildWar {

    private static final String CMD_PERM = "pvparena.cmds.guildwar";
    private static final String SUB_TOP = "top";

    private GuildWar() {
    }

    // ----------------------------------------------------------------------------------------- command

    static void handle(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use GuildWar.");
            return;
        }
        final Player player = (Player) sender;

        if (!player.hasPermission(CMD_PERM) && !player.isOp()) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use GuildWar.");
            return;
        }

        if (args.length > 0 && SUB_TOP.equalsIgnoreCase(args[0])) {
            GuildWarLeaderboard.show(player, args.length > 1 ? args[1] : null);
            return;
        }

        // Toggle: already queued -> leave the queue.
        if (GuildWarQueue.get().contains(player.getUniqueId())) {
            dequeue(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "You left the GuildWar queue.");
            return;
        }

        // Must not already be in an arena.
        if (ArenaPlayer.fromPlayer(player).getArena() != null) {
            player.sendMessage(ChatColor.RED + "You're already in an arena — leave it before queueing for GuildWar.");
            return;
        }

        final GuildBridge guilds = GuildBridge.get();
        if (!guilds.isAvailable()) {
            player.sendMessage(ChatColor.RED + "The guild system (UltimateClans) is unavailable right now.");
            return;
        }
        if (!guilds.hasGuild(player)) {
            player.sendMessage(ChatColor.RED + "You must be in a guild to queue for GuildWar.");
            return;
        }

        final UUID guildId = guilds.guildId(player);
        if (guildId == null) {
            player.sendMessage(ChatColor.RED + "Could not determine your guild. Try again in a moment.");
            return;
        }

        if (GuildWarResultStore.get().isLockedOut(guildId)) {
            player.sendMessage(ChatColor.RED + "Your guild has already lost a GuildWar today and is locked out until "
                    + ChatColor.YELLOW + "00:00 (GMT+8)" + ChatColor.RED + ".");
            return;
        }

        enqueue(player, guildId);
    }

    // ------------------------------------------------------------------------------------------- queue

    private static void enqueue(final Player player, final UUID guildId) {
        final GuildWarQueue.Entry entry = GuildWarQueue.get().add(player.getUniqueId(), guildId);

        final PVPArena plugin = PVPArena.getInstance();
        if (plugin != null) {
            entry.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (GuildWarQueue.get().remove(player.getUniqueId()) != null) {
                    final Player p = Bukkit.getPlayer(player.getUniqueId());
                    if (p != null) {
                        p.sendMessage(ChatColor.YELLOW + "GuildWar: no opponent found in time — removed from the queue.");
                    }
                }
            }, GuildWarConfig.get().queueTimeoutSeconds() * 20L);
        }

        player.sendMessage(ChatColor.GREEN + "You joined the GuildWar queue. Waiting for an opponent from another guild…");
        tryMatch();
    }

    /** Remove a player from the queue (hygiene: quit / leave / joined another arena / toggle). */
    static void dequeue(final UUID playerId) {
        GuildWarQueue.get().remove(playerId);
    }

    // -------------------------------------------------------------------------------------- matchmaking

    /** Attempt to pair two distinct-guild players and start a match. Safe to call repeatedly. */
    static void tryMatch() {
        final GuildWarQueue.Entry[] pair = GuildWarQueue.get().findDistinctGuildPair();
        if (pair == null) {
            return; // not enough distinct-guild players queued
        }

        final Player p1 = Bukkit.getPlayer(pair[0].playerId);
        final Player p2 = Bukkit.getPlayer(pair[1].playerId);
        // Drop any offline straggler from the queue and retry.
        if (p1 == null) {
            dequeue(pair[0].playerId);
            tryMatch();
            return;
        }
        if (p2 == null) {
            dequeue(pair[1].playerId);
            tryMatch();
            return;
        }

        // Re-check the daily lockout at match time: a guild may have been queued from before it lost
        // a concurrent match. Drop any now-locked player (notify) and retry rather than start a match.
        final GuildWarResultStore store = GuildWarResultStore.get();
        if (dropIfLockedOut(pair[0], p1, store) | dropIfLockedOut(pair[1], p2, store)) {
            tryMatch();
            return;
        }

        final Arena arena = GuildWarArenas.findAvailable();
        if (arena == null) {
            p1.sendMessage(ChatColor.YELLOW + "GuildWar: opponent found, waiting for a free arena…");
            p2.sendMessage(ChatColor.YELLOW + "GuildWar: opponent found, waiting for a free arena…");
            return; // keep both queued; PAEndEvent retries when an arena frees up
        }

        startMatch(arena, pair[0], pair[1], p1, p2);
    }

    /** If the entry's guild is now locked out, dequeue + notify the player and return {@code true}. */
    private static boolean dropIfLockedOut(final GuildWarQueue.Entry entry, final Player player,
                                           final GuildWarResultStore store) {
        if (!store.isLockedOut(entry.guildId)) {
            return false;
        }
        dequeue(entry.playerId);
        player.sendMessage(ChatColor.RED + "GuildWar: your guild was locked out (a loss elsewhere) — removed from the queue.");
        return true;
    }

    private static void startMatch(final Arena arena, final GuildWarQueue.Entry e1, final GuildWarQueue.Entry e2,
                                   final Player p1, final Player p2) {
        final ArenaTeam[] teams = GuildWarArenas.twoTeams(arena);
        if (teams == null) {
            return; // shouldn't happen (findAvailable already checked), guard anyway
        }

        // Remove from the queue (cancels timeouts) and claim the arena BEFORE joining, so the
        // manual-join block in GuildWarListener lets exactly these two players through.
        GuildWarQueue.get().remove(p1.getUniqueId());
        GuildWarQueue.get().remove(p2.getUniqueId());
        GuildWarMatch.open(arena.getName(), p1.getUniqueId(), e1.guildId, p2.getUniqueId(), e2.guildId);

        boolean joined1 = false;
        boolean joined2 = false;
        try {
            joined1 = WorkflowManager.handleJoin(arena, p1, new String[]{teams[0].getName()});
            joined2 = joined1 && WorkflowManager.handleJoin(arena, p2, new String[]{teams[1].getName()});
        } catch (final RuntimeException e) {
            // An unexpected join failure must not leak the arena claim — fall through to rollback.
            CyanGuildWar.logger().warning("[GuildWar] handleJoin threw starting a match in '"
                    + arena.getName() + "': " + e.getMessage());
        }

        if (!joined1 || !joined2) {
            // Roll back a half-formed match: drop the claim and eject anyone who got in.
            GuildWarMatch.close(arena.getName());
            forceLeave(p1);
            if (joined1) {
                forceLeave(p2);
            }
            final String msg = ChatColor.RED + "GuildWar: failed to start the match. Please queue again.";
            p1.sendMessage(msg);
            p2.sendMessage(msg);
            return;
        }

        final String label1 = guildLabel(e1.guildId, p1);
        final String label2 = guildLabel(e2.guildId, p2);
        announce(arena, ChatColor.GOLD + "GuildWar: " + ChatColor.YELLOW + label1
                + ChatColor.GOLD + " vs " + ChatColor.YELLOW + label2 + ChatColor.GOLD + "!");
    }

    // ------------------------------------------------------------------------------------------ results

    /**
     * Resolve a match once (idempotent via {@link GuildWarMatch#resolved}): record the win/loss, lock
     * the loser out until 00:00 GMT+8, and announce.
     */
    static void resolve(final GuildWarMatch match, final UUID winnerGuild, final UUID loserGuild) {
        if (match == null || match.resolved) {
            return;
        }
        match.resolved = true;

        GuildWarResultStore.get().recordResult(winnerGuild, loserGuild);

        final String winnerLabel = guildLabel(winnerGuild, null);
        final String loserLabel = guildLabel(loserGuild, null);
        final String message = ChatColor.GOLD + "GuildWar: " + ChatColor.YELLOW + winnerLabel
                + ChatColor.GOLD + " defeated " + ChatColor.YELLOW + loserLabel + ChatColor.GOLD + "! "
                + ChatColor.GRAY + "(" + loserLabel + " is locked out until 00:00 GMT+8)";

        announce(arenaByName(match.arenaName), message);
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** Broadcast to the whole server if {@code announce-globally}, else to the arena (server if null). */
    private static void announce(final Arena arena, final String message) {
        if (GuildWarConfig.get().announceGlobally() || arena == null) {
            Bukkit.broadcastMessage(message);
        } else {
            arena.broadcast(message);
        }
    }

    private static void forceLeave(final Player player) {
        if (player == null) {
            return;
        }
        final ArenaPlayer ap = ArenaPlayer.fromPlayer(player);
        final Arena arena = ap.getArena();
        if (arena != null) {
            arena.playerLeave(player, CFG.TP_EXIT, true, true, false);
        }
    }

    private static Arena arenaByName(final String name) {
        if (name == null) {
            return null;
        }
        for (final Arena arena : ArenaManager.getArenas()) {
            if (name.equalsIgnoreCase(arena.getName())) {
                return arena;
            }
        }
        return null;
    }

    /** A human-readable label for a guild: its tag if available, else a short UUID, else the player name. */
    private static String guildLabel(final UUID guildId, final Player fallbackPlayer) {
        if (guildId != null) {
            final String tag = GuildBridge.get().guildTag(guildId);
            final String clean = sanitizeTag(tag);
            if (!clean.isEmpty()) {
                return clean;
            }
        }
        if (fallbackPlayer != null) {
            return fallbackPlayer.getName() + "'s guild";
        }
        return guildId != null ? ("guild " + guildId.toString().substring(0, 8)) : "a guild";
    }

    /**
     * Make a player-set guild tag safe to put in a broadcast: strip color codes and any control
     * characters (newlines, etc.) so a crafted tag can't spoof extra chat lines. Returns "" when the
     * tag is null/blank after cleaning.
     */
    static String sanitizeTag(final String tag) {
        if (tag == null) {
            return "";
        }
        final String noColor = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', tag));
        return noColor.replaceAll("\\p{Cntrl}", "").trim();
    }
}
