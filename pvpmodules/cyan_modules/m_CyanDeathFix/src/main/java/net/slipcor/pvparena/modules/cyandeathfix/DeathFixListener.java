package net.slipcor.pvparena.modules.cyandeathfix;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.managers.WorkflowManager;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The handlers that implement the death fix. Every handler no-ops unless the affected player is an
 * arena fighter whose arena has {@link CyanDeathFix} enabled (per-arena opt-in).
 *
 * <ul>
 *     <li><b>Combat-tag recorder</b> ({@code EntityDamageByEntityEvent} @ MONITOR): remembers the
 *         last player who hit a fighter, for kill credit (last-damager wins).</li>
 *     <li><b>Pre-emption</b> ({@code EntityDamageEvent} @ HIGHEST): for environmental causes that
 *         <i>do</i> fire a damage event, fake the death before the player really dies — seamless,
 *         no death screen, routed as a normal arena death. This is the common path and covers
 *         cactus, fire, lava, drowning, suffocation, poison, wither, lightning, gradual void, fall…</li>
 *     <li><b>Safety net</b> ({@code PlayerDeathEvent} @ LOWEST): for the rarer event-less kills
 *         ({@code /kill}, {@code setHealth(0)}, void hard-kill) the player already died — for an
 *         active fighter we <b>count the death and credit the killer</b> via {@code handlePlayerDeath}
 *         (which runs {@code setStatus(DEAD)} + {@code StatisticsManager.kill} before any teleport),
 *         then force the respawn next tick to skip the death screen. Note: core's
 *         {@code onPlayerRespawn} force-leaves arena players on any real respawn, so a module cannot
 *         make these respawn back into the arena — the death is counted, then the player exits
 *         (correct for elimination goals; a limitation for respawning goals on these exotic causes).
 *         <p>The net also covers deaths during the <b>END cooldown</b> and while already
 *         lost/spectating ({@code DEAD}/{@code LOST}/{@code WATCH}): those are <i>not</i> re-counted
 *         (the fight is over), but the player is still force-respawned immediately so the exit happens
 *         before the {@code EndRunnable}'s {@code reset()} fires. That ordering matters — if reset()
 *         runs first it restores the player's {@code PlayerState} onto a still-dead body and the later
 *         respawn clobbers it, stranding the player at base 20 HP, unable to eat or attack, and
 *         seemingly still inside the arena.</p></li>
 * </ul>
 */
public class DeathFixListener implements Listener {

    private final CombatTagTracker combatTags = new CombatTagTracker();
    /** Re-entrancy guard so a single death is never routed twice. */
    private final Set<UUID> handlingDeath = ConcurrentHashMap.newKeySet();

    // ---- Combat-tag recorder -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final Player victim = (Player) event.getEntity();
        final Arena arena = ArenaPlayer.fromPlayer(victim).getArena();
        if (!CyanDeathFix.isEnabledFor(arena)) {
            return;
        }
        final Player attacker = ArenaPlayer.getLastDamagingPlayer(event);
        if (attacker != null && !attacker.equals(victim)) {
            this.combatTags.record(victim.getUniqueId(), event);
        }
    }

    // ---- Pre-emption: environmental lethal damage that fires an event --------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnvironmentalDamage(final EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            // Entity/PvP damage is core's job; environmental EDBE is also handled by core.
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final Player player = (Player) event.getEntity();
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);
        final Arena arena = aPlayer.getArena();

        if (!CyanDeathFix.isEnabledFor(arena)) {
            return;
        }
        // Only intercept actively fighting players; lounge/spectator damage is core's concern.
        if (aPlayer.getStatus() != PlayerStatus.FIGHT || arena.realEndRunner != null) {
            return;
        }
        // Not lethal, or core already neutralized this event (status would have flipped) -> leave it.
        if ((player.getHealth() - event.getFinalDamage()) > 0) {
            return;
        }

        // Fake the death exactly like core does, then route it into the arena.
        Arrays.stream(DamageModifier.values())
                .filter(event::isApplicable)
                .forEach(modifier -> event.setDamage(modifier, 0));
        try {
            player.setHealth(2);
        } catch (final IllegalArgumentException ignored) {
            // max health < 2 (unusual attribute/goal setup); fall through, BASE=1 still applies.
        }
        event.setDamage(DamageModifier.BASE, 1);

        playDeathEffects(player);

        WorkflowManager.handlePlayerDeath(arena, player, resolveDeathEvent(player, event));
    }

    // ---- Safety net: true / event-less deaths --------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTrueDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final ArenaPlayer aPlayer = ArenaPlayer.fromPlayer(player);
        final Arena arena = aPlayer.getArena();

        if (!CyanDeathFix.isEnabledFor(arena)) {
            return;
        }
        // Act on any player still attached to the arena's playing/spectating area. This deliberately
        // includes DEAD/LOST/WATCH (and FIGHT during the END cooldown), not just active FIGHT: a real
        // death during the post-fight reset cooldown is the bug this fixes. Lobby states
        // (LOUNGE/READY/WARM) and already-out states (NULL/OFFLINE) are left to core.
        final PlayerStatus status = aPlayer.getStatus();
        if (!isInArenaArea(status)) {
            return;
        }
        if (!this.handlingDeath.add(player.getUniqueId())) {
            return; // already being handled (defensive against duplicate listeners)
        }

        // Only a mid-fight death needs the death counted + killer credited. A death during the END
        // cooldown (realEndRunner != null) or while already lost/spectating must NOT be re-counted —
        // the fight is already over — it only needs the player to exit cleanly. The pre-emption
        // handler disables itself while realEndRunner != null, so those deaths land here as real ones.
        final boolean activeFighter = status == PlayerStatus.FIGHT && arena.realEndRunner == null;

        try {
            // Don't let vanilla scatter items / exp; the arena's death handling governs that.
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);

            if (activeFighter) {
                final EntityDamageEvent deathEvent = resolveDeathEvent(player, player.getLastDamageCause());
                if (deathEvent != null) {
                    // Count the death + credit the killer NOW, while the player is still in the arena.
                    // handlePlayerDeath sets status DEAD and registers the kill before any teleport.
                    WorkflowManager.handlePlayerDeath(arena, player, deathEvent);
                } else {
                    CyanDeathFix.logger().warning("[CyanDeathFix] No damage event available for "
                            + player.getName() + "'s true death; could not route it into the arena.");
                }
            }
        } catch (final Throwable t) {
            CyanDeathFix.logger().warning("[CyanDeathFix] Error routing true death for "
                    + player.getName() + ": " + t.getMessage());
        }

        // Force the respawn next tick (skips the death screen). This is the crucial step for
        // end-phase deaths: it fires long before the EndRunnable's reset() at the end of the cooldown,
        // so core's onPlayerRespawn runs playerLeave while the arena is still attached AND the player
        // is alive again -- so PlayerState (max-health attribute, food, gamemode, potion effects, ...)
        // is restored onto a LIVE player. Letting reset() run first instead restores that state onto a
        // still-dead player, which the later respawn clobbers: that is what left the player at base
        // 20 HP, unable to eat or hit, and seemingly still inside the arena.
        try {
            Bukkit.getScheduler().runTask(PVPArena.getInstance(), () -> {
                this.handlingDeath.remove(player.getUniqueId());
                if (player.isOnline() && player.isDead()) {
                    player.spigot().respawn();
                }
            });
        } catch (final Throwable t) {
            // Server stopping: scheduling against a disabled plugin throws. Drop the re-entrancy
            // guard here instead, or this player's deaths would be ignored for the rest of the session.
            this.handlingDeath.remove(player.getUniqueId());
        }
    }

    /**
     * True for statuses where the player is still attached to the arena's playing/spectating area and
     * a real death must be routed into a clean arena exit. Lobby states (LOUNGE/READY/WARM) and
     * already-out states (NULL/OFFLINE) are intentionally excluded.
     */
    private static boolean isInArenaArea(final PlayerStatus status) {
        return status == PlayerStatus.FIGHT
                || status == PlayerStatus.DEAD
                || status == PlayerStatus.LOST
                || status == PlayerStatus.WATCH;
    }

    // ---- Housekeeping --------------------------------------------------------------------------

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        this.combatTags.clear(id);
        this.handlingDeath.remove(id);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /**
     * Prefer the stored player-hit event (combat-tag kill credit) if recent and the attacker is
     * still online; otherwise fall back to the given environmental event.
     */
    private EntityDamageEvent resolveDeathEvent(final Player player, final EntityDamageEvent fallback) {
        final EntityDamageByEntityEvent tagged = this.combatTags.consume(player.getUniqueId());
        if (tagged != null) {
            final Player attacker = ArenaPlayer.getLastDamagingPlayer(tagged);
            if (attacker != null && attacker.isOnline()) {
                return tagged;
            }
        }
        return fallback;
    }

    private static void playDeathEffects(final Player player) {
        try {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.5, 0),
                    40, 0.3, 0.6, 0.3, 0.02);
        } catch (final Throwable ignored) {
            // cosmetic only
        }
    }
}
