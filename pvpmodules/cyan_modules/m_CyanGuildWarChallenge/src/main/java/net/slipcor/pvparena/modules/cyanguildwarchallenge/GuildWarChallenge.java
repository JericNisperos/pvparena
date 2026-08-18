package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.managers.WorkflowManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Challenge-mode orchestrator: validates and issues challenges, drives accept/deny/join/leave/cancel,
 * fills rosters, runs the countdown, force-starts the fight and records results.
 *
 * <p>Everything runs on the Bukkit main thread (command + events), so the shared
 * {@link ChallengeRegistry} and per-{@link Challenge} state need no synchronization. The arena claim
 * (a registry entry) is taken atomically before any join, so two challenges can't grab one arena.</p>
 */
final class GuildWarChallenge {

    static final String CMD_PERM = "pvparena.cmds.guildwar";

    /**
     * Players whose join to a {@code guildwar*} arena we initiated this tick — the privacy listener
     * lets these through and blocks every other walk-up join. Always added/removed around a single
     * synchronous {@link WorkflowManager#handleJoin} call.
     */
    private static final Set<UUID> JOIN_PERMITS = new HashSet<>();

    /**
     * Players we're moving <i>out</i> of the arena back to staging (e.g. a countdown was aborted) but
     * who are <b>staying in the war roster</b>. The leave listener ignores their {@code PALeaveEvent} so
     * a relocation isn't mistaken for them quitting the war.
     */
    private static final Set<UUID> RELOCATING = new HashSet<>();

    private GuildWarChallenge() {
    }

    static boolean isPermitted(final UUID playerId) {
        return playerId != null && JOIN_PERMITS.contains(playerId);
    }

    // ------------------------------------------------------------------------------------ challenge

    static void challenge(final Player player, final String guildQuery, final String countStr,
                          final String gamemodeArg) {
        ChallengeRegistry.sweepStale(); // heal any leaked claim before the "already in a war" checks
        final GuildBridge guilds = GuildBridge.get();
        if (!guilds.isAvailable()) {
            GuildWarMessages.send(player, ChatColor.RED + "The guild system (UltimateClans) is unavailable right now.");
            return;
        }
        if (!guilds.hasGuild(player)) {
            GuildWarMessages.send(player, ChatColor.RED + "You must be in a guild to start a Guild War.");
            return;
        }
        final UUID ownGuild = guilds.guildId(player);
        if (ownGuild == null) {
            GuildWarMessages.send(player, ChatColor.RED + "Could not determine your guild. Try again in a moment.");
            return;
        }
        if (ArenaPlayer.fromPlayer(player).getArena() != null) {
            GuildWarMessages.send(player, ChatColor.RED + "You're already in an arena — leave it first.");
            return;
        }
        final long ownCooldown = cooldownRemainingMillis(ownGuild);
        if (ownCooldown > 0) {
            GuildWarMessages.send(player, ChatColor.RED + "Your guild is on cooldown after a recent Guild War loss — wait "
                    + ChatColor.YELLOW + formatDuration(ownCooldown) + ChatColor.RED + " before challenging again.");
            return;
        }

        final UUID enemyGuild = guilds.clanByQuery(guildQuery);
        if (enemyGuild == null) {
            GuildWarMessages.send(player, ChatColor.RED + "No online guild matches " + ChatColor.YELLOW + guildQuery + ChatColor.RED + ".");
            return;
        }
        if (enemyGuild.equals(ownGuild)) {
            GuildWarMessages.send(player, ChatColor.RED + "You can't challenge your own guild.");
            return;
        }
        // "Already in a war" gates — with a counter-invite exception: if THIS enemy already has a
        // PENDING challenge out against US (we're its side-B target), our invite replaces theirs. The
        // old one is auto-denied so two guilds never have two live challenges between them. Anyone who
        // could deny can instead counter with their preferred gamemode by sending their own invite.
        final Challenge ownExisting = ChallengeRegistry.byGuild(ownGuild);
        final Challenge enemyExisting = ChallengeRegistry.byGuild(enemyGuild);
        final boolean counterInvite = ownExisting != null && ownExisting == enemyExisting
                && ownExisting.state == Challenge.State.PENDING
                && ownExisting.sideOfGuild(enemyGuild) == 'A';
        if (counterInvite) {
            cancelChallenge(ownExisting, ChatColor.YELLOW + GuildWarText.guildLabel(ownGuild)
                    + ChatColor.GOLD + " declined and sent their own challenge instead.");
        } else {
            if (ownExisting != null) {
                GuildWarMessages.send(player, ChatColor.RED + "Your guild is already in a Guild War.");
                return;
            }
            if (enemyExisting != null) {
                GuildWarMessages.send(player, ChatColor.RED + "That guild is already in a Guild War.");
                return;
            }
        }
        final long enemyCooldown = cooldownRemainingMillis(enemyGuild);
        if (enemyCooldown > 0) {
            GuildWarMessages.send(player, ChatColor.YELLOW + GuildWarText.guildLabel(enemyGuild) + ChatColor.RED
                    + " is on cooldown after a recent Guild War loss — try again in "
                    + ChatColor.YELLOW + formatDuration(enemyCooldown) + ChatColor.RED + ".");
            return;
        }

        final int onlineOwn = guilds.onlineMembers(ownGuild).size();
        final int onlineEnemy = guilds.onlineMembers(enemyGuild).size();
        if (onlineEnemy < 1) {
            GuildWarMessages.send(player, ChatColor.RED + "That guild has no online members to challenge.");
            return;
        }
        final int maxCount = maxCount(onlineOwn, onlineEnemy);
        final int minCount = GuildWarConfig.get().minCount();
        final Integer count = parseCount(countStr);
        if (count == null || count < 1) {
            GuildWarMessages.send(player, ChatColor.RED + "Count must be a number between " + minCount + " and " + maxCount + ".");
            return;
        }
        if (count < minCount) {
            GuildWarMessages.send(player, ChatColor.RED + "Count too low — a Guild War needs at least "
                    + ChatColor.YELLOW + minCount + ChatColor.RED + " players per side.");
            return;
        }
        if (minCount > maxCount) {
            GuildWarMessages.send(player, ChatColor.RED + "Not enough online players — a Guild War needs at least "
                    + ChatColor.YELLOW + minCount + ChatColor.RED + " per side, and both guilds can field at most "
                    + ChatColor.YELLOW + maxCount + ChatColor.RED + " right now.");
            return;
        }
        if (count > maxCount) {
            GuildWarMessages.send(player, ChatColor.RED + "Count too high — both guilds can field at most "
                    + ChatColor.YELLOW + maxCount + ChatColor.RED + " online players right now.");
            return;
        }

        // Resolve the gamemode (the arena-name suffix, e.g. "domination" -> guildwardomination*). An
        // explicit arg must match a configured GuildWar arena; omitted rolls a random gamemode that
        // currently has a free arena, announced up front so the challenged guild knows what they're in.
        String gamemode = gamemodeArg == null ? null : gamemodeArg.toLowerCase(Locale.ROOT);
        if (gamemode != null) {
            if (!GuildWarArenas.gamemodeExists(gamemode)) {
                GuildWarMessages.send(player, ChatColor.RED + "Unknown gamemode " + ChatColor.YELLOW + gamemodeArg
                        + ChatColor.RED + ". Available: " + ChatColor.WHITE + gamemodeList());
                return;
            }
        } else {
            gamemode = GuildWarArenas.randomAvailableGamemode();
            if (gamemode == null) {
                GuildWarMessages.send(player, ChatColor.RED + "No free Guild War arena is available right now.");
                return;
            }
        }
        final Arena arena = GuildWarArenas.findAvailable(gamemode);
        if (arena == null) {
            GuildWarMessages.send(player, ChatColor.RED + "No free " + ChatColor.YELLOW
                    + GuildWarText.prettyGamemode(gamemode) + ChatColor.RED
                    + " Guild War arena is available right now.");
            return;
        }
        final ArenaTeam[] teams = GuildWarArenas.twoTeams(arena);
        if (teams == null) {
            GuildWarMessages.send(player, ChatColor.RED + "The Guild War arena isn't configured with two teams.");
            return;
        }

        // Claim the arena up front (reserves it) — but DON'T pull anyone in yet. Players stay free in
        // the world; we only teleport everyone in once both rosters are full (see onCountdownFinish).
        final Challenge challenge = ChallengeRegistry.open(new Challenge(
                arena.getName(), ownGuild, enemyGuild,
                teams[0].getName(), teams[1].getName(), count, player.getUniqueId(), gamemode));
        challenge.rosterA.add(player.getUniqueId());

        final String ownLabel = GuildWarText.guildLabel(ownGuild);
        final String enemyLabel = GuildWarText.guildLabel(enemyGuild);
        final String modeLabel = GuildWarText.prettyGamemode(gamemode);

        GuildWarMessages.broadcast(ChatColor.YELLOW + ownLabel + ChatColor.GOLD + " challenged "
                + ChatColor.YELLOW + enemyLabel + ChatColor.GOLD + " to a "
                + ChatColor.WHITE + count + "v" + count + ChatColor.GOLD + " "
                + ChatColor.AQUA + modeLabel + ChatColor.GOLD + " Guild War!");

        // DM the enemy guild: clickable accept/deny to officers, an informational note to the rest.
        for (final Player member : guilds.onlineMembers(enemyGuild)) {
            if (guilds.canManageWar(member, enemyGuild)) {
                GuildWarMessages.sendAcceptPrompt(member, ownLabel, count, modeLabel);
            } else {
                GuildWarMessages.send(member, ChatColor.GRAY + "Your guild was challenged by "
                        + ChatColor.YELLOW + ownLabel + ChatColor.GRAY + " — an officer must accept.");
            }
        }

        scheduleAcceptTimeout(challenge);
    }

    // -------------------------------------------------------------------------------- accept / deny

    static void accept(final Player player) {
        final UUID guildId = guildOf(player);
        if (guildId == null) {
            return;
        }
        final Challenge challenge = ChallengeRegistry.byGuild(guildId);
        if (challenge == null || challenge.state != Challenge.State.PENDING || challenge.sideOfGuild(guildId) != 'B') {
            GuildWarMessages.send(player, ChatColor.RED + "Your guild has no pending challenge to accept.");
            return;
        }
        if (!GuildBridge.get().canManageWar(player, guildId)) {
            GuildWarMessages.send(player, ChatColor.RED + "Only your guild's leader/officers can accept a Guild War.");
            return;
        }
        if (ArenaPlayer.fromPlayer(player).getArena() != null) {
            GuildWarMessages.send(player, ChatColor.RED + "You're already in an arena — leave it first.");
            return;
        }

        if (challenge.acceptTask != null) {
            challenge.acceptTask.cancel();
            challenge.acceptTask = null;
        }
        challenge.state = Challenge.State.STAGING;
        challenge.rosterB.add(player.getUniqueId()); // logical only — no teleport yet

        final String aLabel = GuildWarText.guildLabel(challenge.guildA);
        final String bLabel = GuildWarText.guildLabel(challenge.guildB);
        GuildWarMessages.broadcast(ChatColor.YELLOW + bLabel + ChatColor.GREEN + " accepted "
                + ChatColor.YELLOW + aLabel + ChatColor.GREEN + "'s " + ChatColor.AQUA
                + GuildWarText.prettyGamemode(challenge.gamemode) + ChatColor.GREEN
                + " challenge! Both guilds: fill your roster.");

        scheduleStagingTimeout(challenge);
        promptRoster(challenge, 'A');
        promptRoster(challenge, 'B');
        announceProgress(challenge, 'B', player);
        checkBothFull(challenge);
    }

    static void deny(final Player player) {
        final UUID guildId = guildOf(player);
        if (guildId == null) {
            return;
        }
        final Challenge challenge = ChallengeRegistry.byGuild(guildId);
        if (challenge == null || challenge.state != Challenge.State.PENDING || challenge.sideOfGuild(guildId) != 'B') {
            GuildWarMessages.send(player, ChatColor.RED + "Your guild has no pending challenge to deny.");
            return;
        }
        if (!GuildBridge.get().canManageWar(player, guildId)) {
            GuildWarMessages.send(player, ChatColor.RED + "Only your guild's leader/officers can deny a Guild War.");
            return;
        }
        final String bLabel = GuildWarText.guildLabel(challenge.guildB);
        cancelChallenge(challenge, ChatColor.YELLOW + bLabel + ChatColor.RED + " denied the Guild War challenge.");
    }

    // -------------------------------------------------------------------------------- join / leave

    static void join(final Player player) {
        final UUID guildId = guildOf(player);
        if (guildId == null) {
            GuildWarMessages.send(player, ChatColor.RED + "You must be in a guild.");
            return;
        }
        final Challenge challenge = ChallengeRegistry.byGuild(guildId);
        if (challenge == null) {
            GuildWarMessages.send(player, ChatColor.RED + "Your guild has no Guild War to join.");
            return;
        }
        if (challenge.state != Challenge.State.STAGING) {
            GuildWarMessages.send(player, ChatColor.RED + "Your Guild War isn't accepting players right now.");
            return;
        }
        final char side = challenge.sideOfGuild(guildId);
        if (challenge.involvesPlayer(player.getUniqueId())) {
            GuildWarMessages.send(player, ChatColor.YELLOW + "You're already in the Guild War roster.");
            return;
        }
        if (!GuildBridge.get().canJoinWar(player, guildId)) {
            GuildWarMessages.send(player, ChatColor.RED + "Your guild role isn't allowed to join the Guild War roster.");
            return;
        }
        if (challenge.roster(side).size() >= challenge.count) {
            GuildWarMessages.send(player, ChatColor.RED + "Your side's roster is already full.");
            return;
        }
        if (ArenaPlayer.fromPlayer(player).getArena() != null) {
            GuildWarMessages.send(player, ChatColor.RED + "You're already in an arena — leave it first.");
            return;
        }

        challenge.roster(side).add(player.getUniqueId()); // logical only — no teleport yet
        announceProgress(challenge, side, player);
        checkBothFull(challenge);
    }

    static void leave(final Player player) {
        final Challenge challenge = ChallengeRegistry.byPlayer(player.getUniqueId());
        if (challenge == null) {
            GuildWarMessages.send(player, ChatColor.RED + "You're not in a Guild War roster.");
            return;
        }
        onParticipantRemoved(challenge, player.getUniqueId(), true);
    }

    static void cancel(final Player player) {
        final UUID guildId = guildOf(player);
        if (guildId == null) {
            return;
        }
        final Challenge challenge = ChallengeRegistry.byGuild(guildId);
        if (challenge == null || challenge.sideOfGuild(guildId) != 'A') {
            GuildWarMessages.send(player, ChatColor.RED + "Your guild has no Guild War to cancel.");
            return;
        }
        if (challenge.state == Challenge.State.RUNNING || challenge.state == Challenge.State.ENDED
                || challenge.state == Challenge.State.CANCELLED) {
            GuildWarMessages.send(player, ChatColor.RED + "The Guild War has already started.");
            return;
        }
        final boolean isChallenger = player.getUniqueId().equals(challenge.challengerId);
        if (!isChallenger && !GuildBridge.get().canManageWar(player, guildId)) {
            GuildWarMessages.send(player, ChatColor.RED + "Only the challenger or an officer can cancel.");
            return;
        }
        cancelChallenge(challenge, ChatColor.RED + "The Guild War was cancelled by "
                + GuildWarText.guildLabel(challenge.guildA) + ".");
    }

    // -------------------------------------------------------------------------------- spectating

    /**
     * Spectate an in-progress Guild War: teleport the player into the arena's spectator area via the
     * core {@link WorkflowManager#handleSpectate} (which uses the arena's {@code spectator} spawn).
     *
     * <p>Target selection: an explicit {@code arenaArg} if given; otherwise the player's own guild's
     * running war, falling back to the single running war. We briefly permit the player past the
     * walk-up privacy gate (spectating fires a {@link net.slipcor.pvparena.events.PAJoinEvent}).</p>
     */
    static void spectate(final Player player, final String arenaArg) {
        if (player == null) {
            return;
        }
        if (ArenaPlayer.fromPlayer(player).getArena() != null) {
            GuildWarMessages.send(player, ChatColor.RED + "You're already in an arena — leave it first.");
            return;
        }
        ChallengeRegistry.sweepStale();

        final Arena arena = resolveSpectateArena(player, arenaArg);
        if (arena == null) {
            return; // resolveSpectateArena already messaged why
        }

        final UUID id = player.getUniqueId();
        JOIN_PERMITS.add(id);
        boolean ok;
        try {
            ok = WorkflowManager.handleSpectate(arena, player);
        } catch (final RuntimeException e) {
            CyanGuildWarChallenge.logger().warning("[GuildWarChallenge] handleSpectate threw for "
                    + player.getName() + " in '" + arena.getName() + "': " + e.getMessage());
            ok = false;
        } finally {
            JOIN_PERMITS.remove(id);
        }
        if (!ok) {
            GuildWarMessages.send(player, ChatColor.RED + "Couldn't spectate that Guild War — the arena needs the "
                    + "Spectate module and a 'spectator' spawn. Ask an admin.");
        }
    }

    /** Pick the arena to spectate, messaging the player and returning {@code null} on any problem. */
    private static Arena resolveSpectateArena(final Player player, final String arenaArg) {
        if (arenaArg != null && !arenaArg.trim().isEmpty()) {
            final Arena arena = GuildWarArenas.byName(arenaArg);
            if (arena == null || !GuildWarArenas.isGuildWarArena(arena)) {
                GuildWarMessages.send(player, ChatColor.RED + "No Guild War arena named "
                        + ChatColor.YELLOW + arenaArg + ChatColor.RED + ".");
                return null;
            }
            if (!isSpectatable(arena)) {
                GuildWarMessages.send(player, ChatColor.RED + "That Guild War isn't in progress.");
                return null;
            }
            return arena;
        }

        // No arg: prefer the player's own guild's running war.
        final UUID own = guildOf(player);
        final Challenge mine = own == null ? null : ChallengeRegistry.byGuild(own);
        if (mine != null && mine.state == Challenge.State.RUNNING) {
            final Arena arena = GuildWarArenas.byName(mine.arenaName);
            if (isSpectatable(arena)) {
                return arena;
            }
        }

        // Else the running wars; unambiguous only if exactly one.
        final List<Arena> running = new ArrayList<>();
        for (final Challenge c : ChallengeRegistry.running()) {
            final Arena a = GuildWarArenas.byName(c.arenaName);
            if (isSpectatable(a)) {
                running.add(a);
            }
        }
        if (running.isEmpty()) {
            GuildWarMessages.send(player, ChatColor.YELLOW + "No Guild War is in progress to spectate.");
            return null;
        }
        if (running.size() > 1) {
            final StringBuilder names = new StringBuilder();
            for (final Arena a : running) {
                if (names.length() > 0) {
                    names.append(ChatColor.GRAY).append(", ");
                }
                names.append(ChatColor.WHITE).append(a.getName());
            }
            GuildWarMessages.send(player, ChatColor.RED + "Several Guild Wars are running — pick one: "
                    + ChatColor.GRAY + "/guildwar spectate <arena> " + ChatColor.DARK_GRAY + "(" + names + ChatColor.DARK_GRAY + ")");
            return null;
        }
        return running.get(0);
    }

    private static boolean isSpectatable(final Arena arena) {
        return arena != null && GuildWarArenas.isGuildWarArena(arena) && arena.isFightInProgress();
    }

    // ----------------------------------------------------------------------------- exit handling

    /**
     * A roster member dropped (voluntary {@code /guildwar leave} or disconnect). Removes them from the
     * (logical) roster, notifies their guild, and — if the pre-teleport warning was running — stops it
     * and returns to staging so the short side can refill. Pre-fight only; ignored once RUNNING/ENDED.
     */
    static void onParticipantRemoved(final Challenge challenge, final UUID playerId, final boolean eject) {
        if (challenge == null || RELOCATING.contains(playerId) || !challenge.involvesPlayer(playerId)) {
            return; // relocation (staying in the war) or not ours — ignore
        }
        if (challenge.state == Challenge.State.RUNNING || challenge.state == Challenge.State.ENDED
                || challenge.state == Challenge.State.CANCELLED) {
            return; // fight already underway / done — NvN goal handles attrition
        }
        final char side = challenge.sideOfPlayer(playerId);
        challenge.roster(side).remove(playerId);

        // Only ever leaves OUR arena (during LOUNGE the player is in it; pre-teleport it's a no-op).
        if (eject) {
            leaveOurArena(challenge, Bukkit.getPlayer(playerId));
        }

        // Challenger bailing before the enemy accepts cancels the whole challenge.
        if (challenge.state == Challenge.State.PENDING && playerId.equals(challenge.challengerId)) {
            cancelChallenge(challenge, ChatColor.RED + "The challenger left — Guild War cancelled.");
            return;
        }

        final String name = nameOf(playerId);
        messageGuild(challenge.guild(side), ChatColor.YELLOW + name + ChatColor.GRAY
                + " left the Guild War roster. (" + challenge.roster(side).size() + "/" + challenge.count + ")");

        // A drop during either pre-fight countdown stops it and returns everyone to staging.
        if (challenge.state == Challenge.State.COUNTDOWN || challenge.state == Challenge.State.LOUNGE) {
            stopCountdown(challenge);
            final boolean fromLounge = challenge.state == Challenge.State.LOUNGE;
            challenge.state = Challenge.State.STAGING;
            messageParticipants(challenge, ChatColor.RED + "A player left — refilling rosters.");
            if (fromLounge) {
                ejectToStaging(challenge); // pull the rest back out of the arena, keep them in the war
            }
            scheduleStagingTimeout(challenge);
        }
        if (challenge.state == Challenge.State.STAGING) {
            promptRoster(challenge, side);
        }
    }

    // ------------------------------------------------ ready warning → teleport → lounge → fight

    private static void checkBothFull(final Challenge challenge) {
        reconcileRoster(challenge);
        if (challenge.state == Challenge.State.STAGING && challenge.bothFull()) {
            startWarning(challenge);
        }
    }

    /** Both sides full: short "teleporting in Ns" warning while everyone's still free in the world. */
    private static void startWarning(final Challenge challenge) {
        if (challenge.stagingTask != null) {
            challenge.stagingTask.cancel();
            challenge.stagingTask = null;
        }
        challenge.state = Challenge.State.COUNTDOWN;
        messageParticipants(challenge, ChatColor.GREEN + "Lineup complete!");
        challenge.countdown = new GuildWarCountdown(challenge, GuildWarConfig.get().teleportWarningSeconds(),
                GuildWarCountdown.Phase.TELEPORT, () -> onWarningFinish(challenge));
        challenge.countdown.start();
    }

    /** Warning elapsed: pull everyone into the lounge, then run the in-lounge countdown. */
    private static void onWarningFinish(final Challenge challenge) {
        challenge.countdown = null;
        reconcileRoster(challenge); // someone may have gone offline / joined another arena while waiting
        if (challenge.state != Challenge.State.COUNTDOWN || !challenge.bothFull()) {
            if (challenge.state == Challenge.State.COUNTDOWN) {
                revertToStaging(challenge, false);
            }
            return;
        }
        final Arena arena = GuildWarArenas.byName(challenge.arenaName);
        if (arena == null) {
            cancelChallenge(challenge, ChatColor.RED + "Guild War arena vanished — cancelled.");
            return;
        }

        // Teleport every roster member into their team's lounge slot. Roll back on any failure.
        final List<Player> joined = new ArrayList<>();
        boolean ok = true;
        for (final char side : new char[]{'A', 'B'}) {
            for (final UUID id : new HashSet<>(challenge.roster(side))) {
                final Player p = Bukkit.getPlayer(id);
                if (p != null && joinToArena(challenge, side, p)) {
                    joined.add(p);
                } else {
                    ok = false;
                    challenge.roster(side).remove(id);
                }
            }
        }
        if (!ok) {
            joined.forEach(p -> relocateOut(challenge, p));
            revertToStaging(challenge, false);
            return;
        }

        // In the lounge now — run the fight countdown, then force-start.
        challenge.state = Challenge.State.LOUNGE;
        challenge.countdown = new GuildWarCountdown(challenge, GuildWarConfig.get().loungeCountdownSeconds(),
                GuildWarCountdown.Phase.FIGHT, () -> onLoungeFinish(challenge));
        challenge.countdown.start();
    }

    /** Lounge countdown elapsed: force-start the fight (verifying it actually began). */
    private static void onLoungeFinish(final Challenge challenge) {
        challenge.countdown = null;
        final Arena arena = GuildWarArenas.byName(challenge.arenaName);
        if (arena == null) {
            cancelChallenge(challenge, ChatColor.RED + "Guild War arena vanished — cancelled.");
            return;
        }
        reconcileRoster(challenge);
        if (challenge.state != Challenge.State.LOUNGE || !challenge.bothFull()) {
            revertToStaging(challenge, true);
            return;
        }

        challenge.state = Challenge.State.RUNNING;
        arena.start(true); // forced — bypasses our PAStartEvent gate

        if (!arena.isFightInProgress()) {
            // The core refused to start (usually: no team battle-spawns, or no class/autoclass set on
            // the arena). Don't strand players in the lounge — cancel cleanly and say why.
            CyanGuildWarChallenge.logger().warning("[GuildWarChallenge] arena.start(true) did not begin a fight in '"
                    + arena.getName() + "'. Check that it has both teams' battle spawns and a class/autoclass.");
            cancelChallenge(challenge, ChatColor.RED + "Couldn't start the fight — the arena isn't fully set up "
                    + "(needs both teams' spawns and a class/autoclass). Ask an admin.");
            return;
        }

        // The fight is live — invite the whole server to come and watch.
        GuildWarMessages.broadcast(ChatColor.GOLD + "The Guild War between " + ChatColor.YELLOW
                + GuildWarText.guildLabel(challenge.guildA) + ChatColor.GOLD + " and " + ChatColor.YELLOW
                + GuildWarText.guildLabel(challenge.guildB) + ChatColor.GOLD + " has begun! "
                + ChatColor.GREEN + "Type " + ChatColor.WHITE + "/guildwar spectate" + ChatColor.GREEN + " to watch.");
    }

    /** Stop any running countdown, pull everyone back out of the arena (if needed) and resume staging. */
    private static void revertToStaging(final Challenge challenge, final boolean ejectFromArena) {
        stopCountdown(challenge);
        challenge.state = Challenge.State.STAGING;
        messageParticipants(challenge, ChatColor.RED + "A player dropped — refilling rosters.");
        if (ejectFromArena) {
            ejectToStaging(challenge);
        }
        scheduleStagingTimeout(challenge);
        promptRoster(challenge, 'A');
        promptRoster(challenge, 'B');
    }

    // ----------------------------------------------------------------------------------- results

    /** Record a win for {@code winner}'s guild (idempotent via {@link Challenge#resolved}). */
    static void resolveWin(final Arena arena, final Player winner) {
        if (arena == null || winner == null) {
            return;
        }
        final Challenge challenge = ChallengeRegistry.byArena(arena.getName());
        if (challenge == null || challenge.resolved) {
            return;
        }
        UUID winnerGuild = guildSideOf(challenge, winner.getUniqueId());
        if (winnerGuild == null) {
            final UUID g = GuildBridge.get().guildId(winner);
            if (challenge.guildA.equals(g) || challenge.guildB.equals(g)) {
                winnerGuild = g;
            }
        }
        if (winnerGuild == null) {
            return; // can't attribute — leave unresolved
        }
        final UUID loserGuild = winnerGuild.equals(challenge.guildA) ? challenge.guildB : challenge.guildA;

        challenge.resolved = true;
        final GuildBridge guilds = GuildBridge.get();
        final Set<UUID> winnerRoster = new HashSet<>(challenge.roster(challenge.sideOfGuild(winnerGuild)));
        final Set<UUID> loserRoster = new HashSet<>(challenge.roster(challenge.sideOfGuild(loserGuild)));

        GuildWarResultStore.get().recordResult(
                winnerGuild, GuildWarText.sanitize(guilds.clanName(winnerGuild)), winnerRoster,
                loserGuild, GuildWarText.sanitize(guilds.clanName(loserGuild)), loserRoster);
        GuildWarCooldownStore.get().recordLoss(loserGuild, System.currentTimeMillis());
        GuildWarMessages.broadcast(ChatColor.YELLOW + GuildWarText.guildLabel(winnerGuild)
                + ChatColor.GOLD + " defeated " + ChatColor.YELLOW + GuildWarText.guildLabel(loserGuild)
                + ChatColor.GOLD + " in the Guild War!");

        runRewards(arena.getName(), winnerGuild, winnerRoster, loserGuild, loserRoster);
    }

    /** Run the configured winner / loser command rewards for a just-decided war. */
    private static void runRewards(final String arenaName, final UUID winnerGuild, final Set<UUID> winnerRoster,
                                   final UUID loserGuild, final Set<UUID> loserRoster) {
        final GuildBridge guilds = GuildBridge.get();
        final String winTag = guilds.clanName(winnerGuild);
        final String loseTag = guilds.clanName(loserGuild);

        GuildWarRewards.run(arenaName, winTag, loseTag, winnerRoster, winnerGuild,
                GuildWarConfig.get().winnerCommands());
        GuildWarRewards.run(arenaName, loseTag, winTag, loserRoster, loserGuild,
                GuildWarConfig.get().loserCommands());
    }

    /** Arena ended — drop the challenge and free the arena (unresolved end = no score). */
    static void onArenaEnd(final Arena arena) {
        if (arena == null) {
            return;
        }
        final Challenge challenge = ChallengeRegistry.byArena(arena.getName());
        if (challenge == null) {
            return;
        }
        challenge.state = Challenge.State.ENDED;
        challenge.cancelTasks();

        // Core fires PAEndEvent BEFORE the per-winner PAWinEvent(s) within the same arena.reset()
        // call, so closing the challenge here would make resolveWin() (driven by PAWinEvent) find
        // nothing — no result would ever be recorded and the leaderboard would stay empty. Keep the
        // challenge registered until those wins are tallied, then close on the next tick.
        final PVPArena plugin = PVPArena.getInstance();
        final String arenaName = arena.getName();
        // Shutting down: the scheduler rejects tasks once the plugin is disabled, and there is no
        // next tick for the wins to be tallied in — close now.
        if (plugin != null && !plugin.isShuttingDown()) {
            Bukkit.getScheduler().runTask(plugin, () -> ChallengeRegistry.close(arenaName));
        } else {
            ChallengeRegistry.close(arenaName);
        }
    }

    // ----------------------------------------------------------------------------------- helpers

    private static boolean joinToArena(final Challenge challenge, final char side, final Player player) {
        final Arena arena = GuildWarArenas.byName(challenge.arenaName);
        if (arena == null) {
            return false;
        }
        final UUID id = player.getUniqueId();
        JOIN_PERMITS.add(id);
        boolean ok;
        try {
            ok = WorkflowManager.handleJoin(arena, player, new String[]{challenge.teamName(side)});
        } catch (final RuntimeException e) {
            CyanGuildWarChallenge.logger().warning("[GuildWarChallenge] handleJoin threw for "
                    + player.getName() + " in '" + arena.getName() + "': " + e.getMessage());
            ok = false;
        } finally {
            JOIN_PERMITS.remove(id);
        }
        if (ok) {
            suppressNativeStart(arena);
        }
        return ok;
    }

    /** Kill any native auto-start countdown so only our forced start ever begins the fight. */
    private static void suppressNativeStart(final Arena arena) {
        try {
            if (arena.startRunner != null) {
                arena.startRunner.cancel();
                arena.startRunner = null;
            }
        } catch (final Throwable ignored) {
            // best-effort
        }
    }

    private static void cancelChallenge(final Challenge challenge, final String announce) {
        challenge.cancelTasks();
        challenge.state = Challenge.State.CANCELLED;
        ejectAll(challenge);
        ChallengeRegistry.close(challenge.arenaName);
        if (announce != null) {
            messageGuild(challenge.guildA, announce);
            messageGuild(challenge.guildB, announce);
        }
    }

    private static void ejectAll(final Challenge challenge) {
        final Set<UUID> all = new HashSet<>();
        all.addAll(challenge.rosterA);
        all.addAll(challenge.rosterB);
        for (final UUID id : all) {
            leaveOurArena(challenge, Bukkit.getPlayer(id));
        }
        challenge.rosterA.clear();
        challenge.rosterB.clear();
    }

    /** Remove a player from the arena <b>only if</b> they're actually in this challenge's arena. */
    private static void leaveOurArena(final Challenge challenge, final Player player) {
        if (player == null) {
            return;
        }
        final Arena arena = ArenaPlayer.fromPlayer(player).getArena();
        if (arena != null && arena.getName().equalsIgnoreCase(challenge.arenaName)) {
            arena.playerLeave(player, CFG.TP_EXIT, true, true, false);
        }
    }

    private static void stopCountdown(final Challenge challenge) {
        if (challenge.countdown != null) {
            challenge.countdown.stop();
            challenge.countdown = null;
        }
    }

    /** Pull every remaining roster member back out of the arena while <b>keeping them in the war</b>. */
    private static void ejectToStaging(final Challenge challenge) {
        final Set<UUID> all = new HashSet<>();
        all.addAll(challenge.rosterA);
        all.addAll(challenge.rosterB);
        for (final UUID id : all) {
            relocateOut(challenge, Bukkit.getPlayer(id));
        }
    }

    /** Leave the arena without being treated as quitting the war (guarded via {@link #RELOCATING}). */
    private static void relocateOut(final Challenge challenge, final Player player) {
        if (player == null) {
            return;
        }
        final UUID id = player.getUniqueId();
        RELOCATING.add(id);
        try {
            leaveOurArena(challenge, player);
        } finally {
            RELOCATING.remove(id);
        }
    }

    /**
     * Drop roster entries for players who can no longer take part: offline, or already in some other
     * arena. (Pre-fight the players are NOT in our arena yet — they stay free in the world — so this
     * checks eligibility, not arena membership.)
     */
    private static void reconcileRoster(final Challenge challenge) {
        challenge.rosterA.removeIf(id -> !isEligible(id, challenge));
        challenge.rosterB.removeIf(id -> !isEligible(id, challenge));
    }

    private static boolean isEligible(final UUID playerId, final Challenge challenge) {
        final Player p = Bukkit.getPlayer(playerId);
        if (p == null || !p.isOnline()) {
            return false;
        }
        // Free in the world, or already pulled into our own arena (during the teleport step) — both ok.
        final Arena arena = ArenaPlayer.fromPlayer(p).getArena();
        return arena == null || arena.getName().equalsIgnoreCase(challenge.arenaName);
    }

    /** Send a prefixed line to every online roster member of a challenge. */
    private static void messageParticipants(final Challenge challenge, final String legacy) {
        final Set<UUID> all = new HashSet<>();
        all.addAll(challenge.rosterA);
        all.addAll(challenge.rosterB);
        for (final UUID id : all) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null) {
                GuildWarMessages.send(p, legacy);
            }
        }
    }

    /** Announce a join to the joiner's guild: "Your guildmate X joined the guild war! (cur/count)". */
    private static void announceProgress(final Challenge challenge, final char side, final Player joiner) {
        messageGuild(challenge.guild(side), ChatColor.GREEN + "Your guildmate " + ChatColor.YELLOW
                + joiner.getName() + ChatColor.GREEN + " just joined the Guild War! "
                + ChatColor.GRAY + "(" + challenge.roster(side).size() + "/" + challenge.count + ")");
    }

    /** Re-DM a {@code [Join]} prompt to online guild members who aren't in the roster yet. */
    private static void promptRoster(final Challenge challenge, final char side) {
        if (challenge.roster(side).size() >= challenge.count) {
            return;
        }
        final int need = challenge.count - challenge.roster(side).size();
        final String prompt = "Your guild's war needs " + need + " more — ("
                + challenge.roster(side).size() + "/" + challenge.count + ")";
        final UUID guildId = challenge.guild(side);
        for (final Player member : GuildBridge.get().onlineMembers(guildId)) {
            if (!challenge.involvesPlayer(member.getUniqueId())
                    && GuildBridge.get().canJoinWar(member, guildId)) {
                GuildWarMessages.sendJoinPrompt(member, prompt);
            }
        }
    }

    private static void messageGuild(final UUID guildId, final String legacy) {
        for (final Player member : GuildBridge.get().onlineMembers(guildId)) {
            GuildWarMessages.send(member, legacy);
        }
    }

    private static void scheduleAcceptTimeout(final Challenge challenge) {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        challenge.acceptTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (challenge.state == Challenge.State.PENDING) {
                cancelChallenge(challenge, ChatColor.RED + "Guild War challenge expired (no response).");
            }
        }, GuildWarConfig.get().acceptTimeoutSeconds() * 20L);
    }

    private static void scheduleStagingTimeout(final Challenge challenge) {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        if (challenge.stagingTask != null) {
            challenge.stagingTask.cancel();
        }
        challenge.stagingTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (challenge.state == Challenge.State.STAGING) {
                cancelChallenge(challenge, ChatColor.RED + "Guild War cancelled — rosters weren't filled in time.");
            }
        }, GuildWarConfig.get().stagingTimeoutSeconds() * 20L);
    }

    private static UUID guildSideOf(final Challenge challenge, final UUID playerId) {
        final char side = challenge.sideOfPlayer(playerId);
        return side == 0 ? null : challenge.guild(side);
    }

    private static UUID guildOf(final Player player) {
        final GuildBridge guilds = GuildBridge.get();
        return guilds.isAvailable() ? guilds.guildId(player) : null;
    }

    /** Comma-separated pretty list of every configured GuildWar gamemode (for the unknown-mode hint). */
    private static String gamemodeList() {
        final StringBuilder sb = new StringBuilder();
        for (final String g : GuildWarArenas.allGamemodes()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(GuildWarText.prettyGamemode(g));
        }
        return sb.length() == 0 ? "(none configured)" : sb.toString();
    }

    private static int maxCount(final int onlineOwn, final int onlineEnemy) {
        return Math.max(1, Math.min(Math.min(onlineOwn, onlineEnemy), GuildWarConfig.get().maxCount()));
    }

    /** Millis a guild still has to wait after a loss before it may take part again (0 = none / ready). */
    private static long cooldownRemainingMillis(final UUID guildId) {
        final long cooldownMs = (long) (GuildWarConfig.get().cooldownHours() * 3_600_000L);
        if (cooldownMs <= 0 || guildId == null) {
            return 0;
        }
        final long lastLoss = GuildWarCooldownStore.get().lastLoss(guildId);
        if (lastLoss <= 0) {
            return 0;
        }
        return Math.max(0, lastLoss + cooldownMs - System.currentTimeMillis());
    }

    /** Human-friendly remaining duration: {@code 2h 15m}, {@code 15m 3s}, or {@code 9s}. */
    private static String formatDuration(final long millis) {
        final long totalSec = (millis + 999) / 1000;
        final long h = totalSec / 3600;
        final long m = (totalSec % 3600) / 60;
        final long s = totalSec % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    private static Integer parseCount(final String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static String nameOf(final UUID id) {
        final Player p = Bukkit.getPlayer(id);
        if (p != null) {
            return p.getName();
        }
        final String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }

    /** Diagnostic dump: {@code /guildwar debug}. Shows what the guild bridge actually resolves. */
    static void debug(final Player player) {
        final GuildBridge guilds = GuildBridge.get();
        player.sendMessage(ChatColor.GOLD + "===== GuildWar debug =====");
        player.sendMessage(ChatColor.GRAY + "UClans bridge available: "
                + (guilds.isAvailable() ? ChatColor.GREEN + "yes" : ChatColor.RED + "no"));
        if (!guilds.isAvailable()) {
            player.sendMessage(ChatColor.RED + "Bridge not bound — check that UltimateClans is enabled. See server log.");
            return;
        }

        final GuildWarConfig cfg = GuildWarConfig.get();
        player.sendMessage(ChatColor.GRAY + "count range (config): " + ChatColor.WHITE
                + cfg.minCount() + ".." + cfg.maxCount()
                + ChatColor.GRAY + "  loss-cooldown=" + ChatColor.WHITE + cfg.cooldownHours() + "h");
        player.sendMessage(ChatColor.GRAY + "accept-roles: " + ChatColor.WHITE + cfg.acceptRoles()
                + ChatColor.GRAY + "  join-roles: " + ChatColor.WHITE
                + (cfg.joinRoles().isEmpty() ? "(any member)" : cfg.joinRoles())
                + ChatColor.GRAY + "  role-fallback: " + ChatColor.WHITE + cfg.roleFallbackAllowAnyMember());

        // Your guild
        final UUID own = guilds.guildId(player);
        player.sendMessage(ChatColor.YELLOW + "Your guild:");
        player.sendMessage(ChatColor.GRAY + "  hasGuild=" + guilds.hasGuild(player)
                + " id=" + (own == null ? "null" : own)
                + " tag=" + (own == null ? "-" : GuildWarText.guildLabel(own)));
        if (own != null) {
            player.sendMessage(ChatColor.GRAY + "  members=" + guilds.guildMembers(own).size()
                    + " online=" + guilds.onlineMembers(own).size()
                    + " yourRole=" + (guilds.canManageWar(player, own) ? "officer(can accept)" : "member"));
            final long ownCd = cooldownRemainingMillis(own);
            player.sendMessage(ChatColor.GRAY + "  loss-cooldown: " + ChatColor.WHITE
                    + (ownCd > 0 ? formatDuration(ownCd) + " remaining" : "ready"));
        }

        // All guilds with online members
        final Set<UUID> online = guilds.onlineGuilds();
        player.sendMessage(ChatColor.YELLOW + "Guilds with online members (" + online.size() + "):");
        if (online.isEmpty()) {
            player.sendMessage(ChatColor.RED + "  (none — that's why no enemy resolves / no count shows)");
        }
        for (final UUID g : online) {
            final String tag = GuildWarText.sanitize(guilds.clanName(g));
            final boolean resolves = g.equals(guilds.clanByQuery(tag));
            player.sendMessage(ChatColor.GRAY + "  - " + ChatColor.WHITE + (tag.isEmpty() ? "?" : tag)
                    + ChatColor.GRAY + " online=" + guilds.onlineMembers(g).size()
                    + " total=" + guilds.guildMembers(g).size()
                    + (g.equals(own) ? " (you)" : "")
                    + " query→" + (resolves ? ChatColor.GREEN + "ok" : ChatColor.RED + "FAIL"));
        }
        player.sendMessage(ChatColor.GRAY + "Tip: challenge with the TAG shown above: "
                + ChatColor.WHITE + "/guildwar <tag> <count>");
    }

    /** Tab-completion support: the max selectable count for a challenger vs a named enemy guild. */
    static int maxSelectableCount(final Player challenger, final UUID enemyGuild) {
        final GuildBridge guilds = GuildBridge.get();
        final UUID own = guilds.guildId(challenger);
        if (own == null || enemyGuild == null) {
            return 0;
        }
        return maxCount(guilds.onlineMembers(own).size(), guilds.onlineMembers(enemyGuild).size());
    }
}
