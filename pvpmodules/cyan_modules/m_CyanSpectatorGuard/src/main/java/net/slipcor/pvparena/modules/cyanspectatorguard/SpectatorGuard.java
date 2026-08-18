package net.slipcor.pvparena.modules.cyanspectatorguard;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>SpectatorGuard — stop spectators from leaking items into a running arena.</pre>
 *
 * <p>{@code FlySpectate} puts spectators in <b>CREATIVE</b> mode. Its listener cancels normal clicks
 * and interacts, but <b>not</b> the creative middle-click item-grab ({@code InventoryCreativeEvent})
 * nor {@code PlayerDropItemEvent} — so a spectator can middle-click any block/item and Q-drop it to
 * the fighters below. The core only blocks drops for {@code READY}/{@code LOUNGE} players, not for
 * spectators.</p>
 *
 * <p>This module cancels both events for any player who is spectating an arena that has the module
 * attached. No core files are touched; works alongside FlySpectate (or any spectate mode).</p>
 *
 * <p>It also <b>hides spectators outright</b>: creative-mode watchers flying over a match are a
 * distraction and a source of "is he ghosting me?" complaints. Guarded spectators are hidden from
 * every non-spectator via {@code Player.hidePlayer}, which removes the body, the nameplate and the
 * tab-list entry — while spectators still see each other.</p>
 *
 * <p>Visibility is re-synced once a second rather than hooked: PVP Arena only tells the chosen
 * spectate module when someone starts watching ({@code commitSpectate}/{@code switchToSpectate} are
 * not dispatched to other modules), so a tick that simply asks "who is spectating right now?" is
 * both shorter and self-healing — it covers joining as a spectator, dying into spectate, and
 * players logging in mid-match alike.</p>
 *
 * <p>ponytail: one global 1s task, alive as long as the plugin is. Cheap enough (it only walks
 * online players and CraftBukkit ignores a repeat hide/show), and only players this module hid are
 * ever shown again — so a staff vanish plugin is never overridden.</p>
 */
public class SpectatorGuard extends ArenaModule {

    static final String NAME = "SpectatorGuard";

    private static volatile boolean listenerRegistered = false;

    /**
     * Players this module hid, mapped to the collision setting they had before — so nobody else's
     * invisibility is ever undone, and their own collision is put back exactly as it was.
     */
    private static final Map<UUID, Boolean> CONCEALED = new ConcurrentHashMap<>();

    public SpectatorGuard() {
        super(NAME);
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public void configParse(final YamlConfiguration config) {
        ensureListener();
    }

    /** True when the player is spectating an arena that has SpectatorGuard attached. */
    static boolean isGuardedSpectator(final Player player) {
        if (player == null) {
            return false;
        }
        final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);
        final Arena arena = arenaPlayer.getArena();
        if (arena == null) {
            return false;
        }
        final boolean spectating = arenaPlayer.isSpectating() || arenaPlayer.getStatus() == PlayerStatus.WATCH;
        return spectating && arena.getMods().stream().anyMatch(mod -> NAME.equals(mod.getName()));
    }

    private static synchronized void ensureListener() {
        if (listenerRegistered) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new SpectatorGuardListener(), PVPArena.getInstance());
            Bukkit.getScheduler().runTaskTimer(PVPArena.getInstance(), SpectatorGuard::syncVisibility, 20L, 20L);
            listenerRegistered = true;
        } catch (final Throwable t) {
            log("could not register listener: " + t.getMessage());
        }
    }

    /** Hide everyone who is spectating right now, and give back anyone who stopped. */
    private static void syncVisibility() {
        // A player who logs out while concealed is never walked below again, so their entry would
        // sit here for the life of the server. Hiding and collision don't survive a reconnect
        // anyway, so the entry is worthless once they're gone.
        CONCEALED.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);

        for (final Player player : Bukkit.getOnlinePlayers()) {
            final UUID uuid = player.getUniqueId();
            if (player.getGameMode() == GameMode.SPECTATOR) {
                // Already unseen, uncollidable and unable to touch anything — vanilla did the work.
                // Leaving these alone also keeps this module from fighting CyanSpectate over whose
                // saved collision setting gets restored on the way out.
                continue;
            }
            if (isGuardedSpectator(player)) {
                if (CONCEALED.putIfAbsent(uuid, player.isCollidable()) == null) {
                    // Hiding a player does not remove their hitbox: core turns collision on from
                    // player.collision, which would leave an invisible wall in a doorway.
                    player.setCollidable(false);
                }
                setVisible(player, false); // repeat calls are no-ops; catches viewers who just logged in
            } else {
                final Boolean wasCollidable = CONCEALED.remove(uuid);
                if (wasCollidable != null) {
                    player.setCollidable(wasCollidable);
                    setVisible(player, true);
                }
            }
        }
    }

    /** Spectators stay visible to each other — only the people still playing lose sight of them. */
    private static void setVisible(final Player spectator, final boolean visible) {
        final PVPArena plugin = PVPArena.getInstance();
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(spectator)) {
                continue;
            }
            if (visible) {
                viewer.showPlayer(plugin, spectator);
            } else if (!isGuardedSpectator(viewer)) {
                viewer.hidePlayer(plugin, spectator);
            }
        }
    }

    static void log(final String message) {
        final PVPArena instance = PVPArena.getInstance();
        (instance != null ? instance.getLogger() : Bukkit.getLogger())
                .warning("[SpectatorGuard] " + message);
    }
}
