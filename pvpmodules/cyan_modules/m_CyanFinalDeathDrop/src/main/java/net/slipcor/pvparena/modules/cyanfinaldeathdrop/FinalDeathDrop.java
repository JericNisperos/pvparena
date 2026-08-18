package net.slipcor.pvparena.modules.cyanfinaldeathdrop;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.managers.InventoryManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>FinalDeathDrop — make {@code player.dropsInventory} work on a player's <b>final</b> death.</pre>
 *
 * <p>Core bug this works around: on a lives-based goal (e.g. {@code PlayerLives}), when a death
 * removes a player's last life, {@code ArenaPlayer.handleDeathAndLose()} calls
 * {@code InventoryManager.clearInventory()} <b>before</b> {@code WorkflowManager} reaches its
 * {@code dropsInventory} drop step — so the inventory is already empty and nothing drops. It only
 * bites the elimination death; respawn deaths drop fine.</p>
 *
 * <p>Two steps, so that nothing is touched until a death is certain:</p>
 * <ol>
 *   <li>{@link FinalDeathDropListener} <b>copies</b> the inventory on a hit that looks fatal. It
 *       mutates nothing — whether the hit kills is the core's decision, made after ours.</li>
 *   <li>{@link #resetPlayer} runs from {@code Arena.resetPlayer}, reached only once the goal has
 *       actually eliminated the player ({@code PlayerStatus.LOST}) and still <b>before</b> the
 *       body is teleported away, so the gear lands where they fell. It puts the copy back for an
 *       instant and hands it to the core's own
 *       {@link net.slipcor.pvparena.managers.InventoryManager#drop}, which applies the arena's
 *       drop config, clears the inventory and flips {@code mayDropInventory} off — making the
 *       core's own later drop step a clean no-op, so nothing drops twice.</li>
 * </ol>
 *
 * <p>An earlier version dropped straight from the damage event. That stripped players who never
 * died, because the core cancels would-be-fatal damage at {@code HIGHEST} — after us — for friendly
 * fire, an unstarted fight, a non-fighting attacker and more. Copy-then-confirm removes the need to
 * predict any of that.</p>
 *
 * <p>Attach it to any arena that has {@code player.dropsInventory: true} and a lives-based goal.
 * If {@code dropsInventory} is off, the module does nothing. Players on the {@code custom} class
 * are skipped entirely: that gear is their own, and core deliberately keeps it on death.</p>
 *
 * <p>ponytail: covers deaths that pass through the core's player-damage pipeline (PvP hits, plus
 * environmental damage while fighting). Upgrade path if some exotic death cause slips through:
 * widen the snapshot trigger in the listener, or fix the ordering in core
 * {@code handleDeathAndLose}.</p>
 */
public class FinalDeathDrop extends ArenaModule {

    static final String NAME = "FinalDeathDrop";

    private static volatile boolean listenerRegistered = false;

    /** Inventory copies awaiting a confirmed elimination. Never outlives the tick it was taken in. */
    private static final Map<UUID, ItemStack[]> PENDING = new ConcurrentHashMap<>();

    public FinalDeathDrop() {
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

    /**
     * Keep a copy of what the player is carrying, in case this hit turns out to eliminate them.
     * The copy is discarded at the start of the next tick — the whole death flow (damage event →
     * WorkflowManager → goal → Arena.resetPlayer) runs synchronously inside the current one, so a
     * copy that survives into another tick belongs to a hit that killed nobody.
     */
    static void remember(final Player player) {
        final UUID uuid = player.getUniqueId();
        final ItemStack[] contents = player.getInventory().getContents();
        final ItemStack[] copy = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            copy[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        PENDING.put(uuid, copy);

        try {
            Bukkit.getScheduler().runTask(PVPArena.getInstance(), () -> PENDING.remove(uuid));
        } catch (final Throwable t) {
            PENDING.remove(uuid); // plugin shutting down — better no drop than a stale one
        }
    }

    /**
     * Reached from {@code Arena.resetPlayer}, before the body is teleported off the death spot.
     * Only a player the goal has knocked out for good is {@code LOST} by now; a respawn death is
     * still {@code DEAD} and was already dropped correctly by core.
     */
    @Override
    public void resetPlayer(final Player player, final boolean soft, final boolean force) {
        if (player == null) {
            return;
        }
        final ItemStack[] snapshot = PENDING.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);
        if (arenaPlayer.getStatus() != PlayerStatus.LOST) {
            return;
        }

        // Core emptied the inventory in handleDeathAndLose; hand the copy to its own drop routine
        // so the arena's drop config still decides what falls and what vanishes.
        try {
            player.getInventory().setContents(snapshot);
            InventoryManager.drop(player);
        } catch (final Throwable t) {
            InventoryManager.clearInventory(player); // never let a leaving player keep arena gear
            log("drop failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    /** True if this arena actually has FinalDeathDrop attached (the listener is global). */
    static boolean isEnabledFor(final Arena arena) {
        return arena != null && arena.getMods().stream().anyMatch(mod -> NAME.equals(mod.getName()));
    }

    private static synchronized void ensureListener() {
        if (listenerRegistered) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new FinalDeathDropListener(), PVPArena.getInstance());
            listenerRegistered = true;
        } catch (final Throwable t) {
            log("could not register listener: " + t.getMessage());
        }
    }

    static void log(final String message) {
        final PVPArena instance = PVPArena.getInstance();
        (instance != null ? instance.getLogger() : Bukkit.getLogger())
                .warning("[FinalDeathDrop] " + message);
    }
}
