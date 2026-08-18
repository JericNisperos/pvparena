package net.slipcor.pvparena.modules.cyanspectate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The two things vanilla spectator mode does <b>not</b> handle for us.
 *
 * <p>Everything else a spectator might try — breaking, placing, interacting, picking up, hitting or
 * being hit — is already impossible in {@code GameMode.SPECTATOR}, so there is nothing to cancel.</p>
 */
class CyanSpectateListener implements Listener {

    /**
     * A player who logs out while spectating would be saved as a spectator and come back as one,
     * loose in the world. Core's leave handling normally beats us to it; this is the backstop for
     * when it does not.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        CyanSpectate.release(event.getPlayer());
    }

    /**
     * Spectator mode offers a teleport menu covering every player on the server. Flying around the
     * arena is the point; being dropped next to a fighter in another world, still flagged as
     * watching this arena, is not.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        final Player player = event.getPlayer();
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE
                && CyanSpectate.isSpectating(player)) {
            event.setCancelled(true);
        }
    }
}
