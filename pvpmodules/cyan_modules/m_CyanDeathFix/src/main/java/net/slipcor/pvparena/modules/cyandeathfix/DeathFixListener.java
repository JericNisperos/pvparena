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
 *         ({@code /kill}, {@code setHealth(0)}, void hard-kill) the player already died — we
 *         <b>count the death and credit the killer</b> via {@code handlePlayerDeath} (which runs
 *         {@code setStatus(DEAD)} + {@code StatisticsManager.kill} before any teleport), then skip
 *         the death screen. Note: core's {@code onPlayerRespawn} force-leaves arena players on any
 *         real respawn, so a module cannot make these respawn back into the arena — the death is
 *         counted, then the player exits (correct for elimination goals; a limitation for respawning
 *         goals on these exotic causes).</li>
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
        // A real death of an active fighter means core's fake-death was bypassed.
        if (aPlayer.getStatus() != PlayerStatus.FIGHT) {
            return;
        }
        if (!this.handlingDeath.add(player.getUniqueId())) {
            return; // already being handled (defensive against duplicate listeners)
        }

        try {
            // Don't let vanilla scatter items / exp; the arena's death handling governs that.
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);

            final EntityDamageEvent deathEvent = resolveDeathEvent(player, player.getLastDamageCause());
            if (deathEvent != null) {
                // Count the death + credit the killer NOW, while the player is still in the arena.
                // handlePlayerDeath sets status DEAD and registers the kill before any teleport, so
                // the death/kill is recorded even though core will subsequently force-leave the
                // player on respawn (a module cannot prevent that without editing core).
                WorkflowManager.handlePlayerDeath(arena, player, deathEvent);
            } else {
                CyanDeathFix.logger().warning("[CyanDeathFix] No damage event available for "
                        + player.getName() + "'s true death; could not route it into the arena.");
            }
        } catch (final Throwable t) {
            CyanDeathFix.logger().warning("[CyanDeathFix] Error routing true death for "
                    + player.getName() + ": " + t.getMessage());
        }

        // Skip the death screen next tick. Core's onPlayerRespawn then cleanly removes the player.
        Bukkit.getScheduler().runTask(PVPArena.getInstance(), () -> {
            this.handlingDeath.remove(player.getUniqueId());
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        });
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
