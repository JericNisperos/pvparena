package net.slipcor.pvparena.modules.cyanspectatorguard;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;

/**
 * Everything a creative-mode spectator could otherwise still do. See {@link SpectatorGuard}.
 *
 * <p>Core already refuses pickups, block breaks and block places for a {@code WATCH} player — but
 * the block handlers bail out early when the location is in no arena region at all
 * ({@code BlockListener.willBeSkipped}), so a spectator who flies out of the arena keeps full
 * creative powers over the surrounding map. These cancels are unconditional for a guarded
 * spectator, wherever they are.</p>
 */
class SpectatorGuardListener implements Listener {

    /** Creative middle-click item grab — the packet path FlySpectate's PlayerInteract cancel misses. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryCreative(final InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player
                && SpectatorGuard.isGuardedSpectator((Player) event.getWhoClicked())) {
            event.setCancelled(true);
        }
    }

    /** Dropping items (Q) into the arena. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(final PlayerDropItemEvent event) {
        if (SpectatorGuard.isGuardedSpectator(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Creative insta-break, anywhere — including outside the arena regions core stops caring about. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        if (SpectatorGuard.isGuardedSpectator(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Placing blocks — walling fighters in, bridging, or griefing the map around the arena. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (SpectatorGuard.isGuardedSpectator(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Buttons, levers, doors, pressure plates, chests, bows, buckets, eating — all one event. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (SpectatorGuard.isGuardedSpectator(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Mounting, villager trades, item frames, leads. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {
        if (SpectatorGuard.isGuardedSpectator(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Containers: looting a chest mid-match, or stuffing gear into one for a friend. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player
                && SpectatorGuard.isGuardedSpectator((Player) event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Punching a painting or item frame is not a block break — it has its own event. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player
                && SpectatorGuard.isGuardedSpectator((Player) event.getRemover())) {
            event.setCancelled(true);
        }
    }

    /** Same for boats and minecarts, which take vehicle damage rather than entity damage. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDamage(final VehicleDamageEvent event) {
        if (event.getAttacker() instanceof Player
                && SpectatorGuard.isGuardedSpectator((Player) event.getAttacker())) {
            event.setCancelled(true);
        }
    }

    /**
     * Both directions: a spectator must not land a hit, and must not take one either — an unseen
     * body in the middle of a fight would otherwise soak arrows aimed at a real target.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(final EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player
                && SpectatorGuard.isGuardedSpectator((Player) event.getEntity())) {
            event.setCancelled(true);
            return;
        }

        Entity damager = event.getDamager();
        if (damager instanceof Projectile
                && ((Projectile) damager).getShooter() instanceof Player) {
            damager = (Player) ((Projectile) damager).getShooter();
        }
        if (damager instanceof Player && SpectatorGuard.isGuardedSpectator((Player) damager)) {
            event.setCancelled(true);
        }
    }
}
