package net.slipcor.pvparena.modules.cyanfinaldeathdrop;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.core.Config.CFG;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Takes a <b>copy</b> of the inventory on a hit that looks fatal, and changes nothing else.
 *
 * <p>Whether the hit really kills is the core's call, made at {@code HIGHEST} — it cancels
 * would-be-fatal damage for friendly fire, spawn protection, NODAMAGE regions, an unstarted fight,
 * a non-fighting attacker and more. Touching the inventory here would strip players who never die,
 * so nothing is dropped until {@link FinalDeathDrop#resetPlayer} sees a confirmed elimination.</p>
 */
class FinalDeathDropListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final Player player = (Player) event.getEntity();
        final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);
        final Arena arena = arenaPlayer.getArena();

        if (arena == null || !FinalDeathDrop.isEnabledFor(arena)) {
            return;
        }
        if (arenaPlayer.getStatus() != PlayerStatus.FIGHT) {
            return;
        }
        if (!arena.getConfig().getBoolean(CFG.PLAYER_DROPSINVENTORY)) {
            return; // admin didn't ask for drops — leave everything to core
        }
        if (arenaPlayer.hasCustomClass()) {
            return; // custom class = the player's own gear; core keeps it on death, so must we
        }

        // Same fatal test as core's, but a false positive costs nothing here: the copy is thrown
        // away at the end of the tick unless the player is actually knocked out for good.
        if ((player.getHealth() - event.getFinalDamage()) > 0) {
            return;
        }

        FinalDeathDrop.remember(player);
    }
}
