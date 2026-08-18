package net.slipcor.pvparena.modules.cyanspectate;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PALocation;
import net.slipcor.pvparena.classes.PASpawn;
import net.slipcor.pvparena.core.Language;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.exceptions.GameplayException;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.loadables.ModuleType;
import net.slipcor.pvparena.managers.SpawnManager;
import net.slipcor.pvparena.managers.TeleportManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.slipcor.pvparena.config.Debugger.debug;

/**
 * <pre>CyanSpectate — spectating in real GameMode.SPECTATOR, not creative flight.</pre>
 *
 * <p>Replaces {@code FlySpectate}. Vanilla spectator mode gives away, for free, every guard the
 * creative-mode approach had to bolt on with cancelled events: no block breaking or placing, no
 * interaction, no item pickup, no dealing or taking damage, no hitbox to body-block a doorway or
 * soak an arrow, and invisibility to everyone still playing. Flight is inherent, and a spectator
 * <b>cannot fall</b> — the "spectators drop into the void" problem came from FlySpectate only
 * setting a gamemode when {@code general.gamemode} happened to be configured
 * ({@code FlySpectate.teleportAndChangeState}), which left spectators in survival with a flight
 * flag that any reset could clear.</p>
 *
 * <p>Fixed here relative to FlySpectate:</p>
 * <ul>
 *   <li>gamemode is set unconditionally, and re-asserted — {@code PlayerState.fullReset} runs
 *       <i>after</i> the death path hands a player over and re-applies the arena's gamemode and
 *       collision from config;</li>
 *   <li>the original gamemode/flight/collision is remembered per player and handed back on the way
 *       out, instead of being guessed ({@code FlySpectate.unload} forced {@code collidable=true});</li>
 *   <li>inventory is cleared <i>before</i> the state snapshot, so armor max-health modifiers are
 *       gone first — FlySpectate had those two calls the wrong way round;</li>
 *   <li>spectators are hidden from every online player, not only from arena members at that
 *       instant, so latecomers cannot see them either;</li>
 *   <li>one global listener and one global task, instead of a fresh listener per arena that was
 *       never unregistered, holding {@code Player} objects in a set that quitting never cleaned.</li>
 * </ul>
 *
 * <p>ponytail: state is re-asserted once a second rather than hooked to every event that could
 * disturb it. Shorter than chasing each one, and self-healing after a teleport, a respawn, or
 * another plugin's gamemode change.</p>
 */
public class CyanSpectate extends ArenaModule {

    /** Below StandardSpectate (2) and FlySpectate (3), so this one wins when several are attached. */
    public static final int PRIORITY = 1;

    static final String NAME = "CyanSpectate";

    /** Everyone currently spectating, mapped to what they were before. Also the "is spectating" set. */
    private static final Map<UUID, Restorable> SPECTATORS = new ConcurrentHashMap<>();

    private static volatile boolean runtimeStarted = false;

    /** What a player looked like before they started spectating. */
    private record Restorable(GameMode gameMode, boolean allowFlight, boolean flying, boolean collidable) {
    }

    public CyanSpectate() {
        super(NAME);
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public ModuleType getType() {
        return ModuleType.SPECTATE;
    }

    @Override
    public void configParse(final YamlConfiguration config) {
        ensureRuntime();
    }

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        final Set<PASpawn> missing = new HashSet<>();
        if (spawns.stream().noneMatch(spawn ->
                PASpawn.SPECTATOR.equals(spawn.getName()) && spawn.getTeamName() == null)) {
            missing.add(new PASpawn(null, PASpawn.SPECTATOR, null, null));
        }
        return missing;
    }

    @Override
    public boolean hasSpawn(final String spawnName, final String teamName) {
        return PASpawn.SPECTATOR.equalsIgnoreCase(spawnName);
    }

    @Override
    public boolean handleSpectate(final Player player) throws GameplayException {
        final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);
        if (arenaPlayer.getArena() != null) {
            throw new GameplayException(Language.parse(
                    MSG.ERROR_ARENA_ALREADY_PART_OF, arenaPlayer.getArena().getName()));
        }
        if (this.arena.getFighters().isEmpty()) {
            throw new GameplayException(MSG.ERROR_NOPLAYERFOUND);
        }
        return true;
    }

    /** Joining as a spectator from outside the arena. */
    @Override
    public void commitSpectate(final Player player) {
        debug(player, "committing Cyan spectate");
        final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);

        arenaPlayer.setLocation(new PALocation(player.getLocation()));
        arenaPlayer.setArena(this.arena);
        arenaPlayer.setStatus(PlayerStatus.WATCH);

        if (arenaPlayer.getState() == null) {
            // Order matters: emptying the inventory first drops armor attribute modifiers, so the
            // snapshot records the player's real max health.
            ArenaPlayer.backupAndClearInventory(this.arena, player);
            arenaPlayer.createState(player);
            arenaPlayer.dump();
        }

        this.arena.msg(player, MSG.NOTICE_WELCOME_SPECTATOR);
        this.sendToSpectatorSpawn(arenaPlayer);
    }

    /** A fighter who has just been knocked out for good. */
    @Override
    public void switchToSpectate(final Player player) {
        debug(player, "becoming spectator using CyanSpectate");
        this.sendToSpectatorSpawn(ArenaPlayer.fromPlayer(player));
    }

    private void sendToSpectatorSpawn(final ArenaPlayer arenaPlayer) {
        final Player player = arenaPlayer.getPlayer();
        TeleportManager.teleportPlayerToRandomSpawn(this.arena, arenaPlayer,
                SpawnManager.getPASpawnsStartingWith(this.arena, PASpawn.SPECTATOR));
        arenaPlayer.setSpectating(true);
        beginSpectating(player);
    }

    static void beginSpectating(final Player player) {
        SPECTATORS.putIfAbsent(player.getUniqueId(), new Restorable(
                player.getGameMode(), player.getAllowFlight(), player.isFlying(), player.isCollidable()));
        applySpectatorState(player);

        // The death path calls us from inside handleDeathAndLose, and PlayerState.fullReset runs
        // straight after — re-applying the arena's gamemode and collision over ours. Say it again
        // once that has happened; the 1s task is the permanent backstop.
        try {
            Bukkit.getScheduler().runTaskLater(PVPArena.getInstance(), () -> {
                if (SPECTATORS.containsKey(player.getUniqueId()) && player.isOnline()) {
                    applySpectatorState(player);
                }
            }, 5L);
        } catch (final Throwable ignored) {
            // plugin shutting down — the immediate apply above already happened
        }
    }

    private static void applySpectatorState(final Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        // Spectator mode flies on its own and cannot fall. These only matter for the moment between
        // something dropping the player back to survival and the next sync putting it right.
        player.setAllowFlight(true);
        if (!player.isFlying()) {
            player.setFlying(true);
        }
        player.setCollidable(false);

        // Vanilla lets a spectator click a player to ride their first-person view — that is
        // ghosting, and Spigot has no event for it, so it gets undone here instead.
        if (player.getGameMode() == GameMode.SPECTATOR && player.getSpectatorTarget() != null) {
            player.setSpectatorTarget(null);
        }

        setVisible(player, false);
    }

    /** Give the player back exactly what they had. No-op for anyone this module never touched. */
    static void release(final Player player) {
        final Restorable previous = SPECTATORS.remove(player.getUniqueId());
        if (previous == null) {
            return;
        }
        setVisible(player, true);

        // Only undo what is still ours. Arena.resetPlayer runs PlayerState.unload before it reaches
        // the modules, and that restores the gamemode the player had before joining at all — a
        // better answer than the one recorded here, which for a dead fighter is merely whatever the
        // arena had them in. If they are already out of spectator mode, leave everything alone.
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return;
        }
        if (player.getSpectatorTarget() != null) {
            player.setSpectatorTarget(null);
        }
        player.setGameMode(previous.gameMode());
        player.setAllowFlight(previous.allowFlight());
        if (previous.allowFlight()) {
            player.setFlying(previous.flying());
        }
        player.setCollidable(previous.collidable());
    }

    static boolean isSpectating(final Player player) {
        return player != null && SPECTATORS.containsKey(player.getUniqueId());
    }

    /** Spectators still see each other; everyone still playing loses sight of them entirely. */
    private static void setVisible(final Player spectator, final boolean visible) {
        final PVPArena plugin = PVPArena.getInstance();
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(spectator)) {
                continue;
            }
            if (visible) {
                viewer.showPlayer(plugin, spectator);
            } else if (!isSpectating(viewer)) {
                viewer.hidePlayer(plugin, spectator);
            }
        }
    }

    @Override
    public void resetPlayer(final Player player, final boolean soft, final boolean force) {
        if (player != null) {
            release(player);
        }
    }

    @Override
    public void unload(final Player player) {
        if (player != null) {
            release(player);
        }
    }

    /**
     * Also the shutdown path: {@code PVPArena.onDisable} resets every arena, and this is the last
     * chance to take players out of spectator mode before the server saves them that way.
     */
    @Override
    public void reset(final boolean force) {
        for (final UUID uuid : SPECTATORS.keySet()) {
            final Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                SPECTATORS.remove(uuid);
                continue;
            }
            // A null arena means the reset already detached its players before reaching the
            // modules — release those too rather than trust the ordering.
            final Arena playerArena = ArenaPlayer.fromPlayer(player).getArena();
            if (playerArena == null || this.arena.equals(playerArena)) {
                release(player);
            }
        }
    }

    private static synchronized void ensureRuntime() {
        if (runtimeStarted) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new CyanSpectateListener(), PVPArena.getInstance());
            Bukkit.getScheduler().runTaskTimer(PVPArena.getInstance(), CyanSpectate::sync, 20L, 20L);
            runtimeStarted = true;
        } catch (final Throwable t) {
            log("could not start runtime: " + t.getMessage());
        }
    }

    /**
     * Keep every spectator in spectator state, and let go of anyone who stopped being one — the
     * self-heal for a teleport, a respawn, a foreign gamemode change, or a missed reset hook.
     */
    private static void sync() {
        for (final UUID uuid : SPECTATORS.keySet()) {
            final Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                SPECTATORS.remove(uuid); // gone; PlayerQuitEvent already restored them
                continue;
            }
            final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);
            if (arenaPlayer.getArena() == null
                    || !(arenaPlayer.isSpectating() || arenaPlayer.getStatus() == PlayerStatus.WATCH)) {
                release(player);
            } else {
                applySpectatorState(player);
            }
        }
    }

    static void log(final String message) {
        final PVPArena instance = PVPArena.getInstance();
        (instance != null ? instance.getLogger() : Bukkit.getLogger())
                .warning("[CyanSpectate] " + message);
    }
}
