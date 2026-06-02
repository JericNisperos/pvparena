package net.slipcor.pvparena.modules.cyandeathfix;

import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per victim, the most recent {@link EntityDamageByEntityEvent} in which a <b>player</b>
 * damaged them. Used to attribute kill credit when the victim subsequently dies to an environmental
 * cause (e.g. knocked into the void): if the player hit happened within {@link #windowMillis}, the
 * stored event is reused so PVP Arena's normal killer computation credits that attacker.
 *
 * <p>We store the real Bukkit event object (never construct one) so this stays compatible across
 * Minecraft versions — PVP Arena only reads {@code getCause()} / {@code getDamager()} from it.</p>
 */
class CombatTagTracker {

    /** How long after a player hit an environmental death still counts as that player's kill. */
    static final long DEFAULT_WINDOW_MILLIS = 15_000L;

    private final long windowMillis;
    private final ConcurrentHashMap<UUID, Tag> tags = new ConcurrentHashMap<>();

    CombatTagTracker() {
        this(DEFAULT_WINDOW_MILLIS);
    }

    CombatTagTracker(final long windowMillis) {
        this.windowMillis = windowMillis;
    }

    void record(final UUID victim, final EntityDamageByEntityEvent event) {
        this.tags.put(victim, new Tag(event, System.currentTimeMillis()));
    }

    /**
     * Returns and removes the stored player-hit event for the victim if it is still within the
     * combat window, otherwise {@code null}.
     */
    EntityDamageByEntityEvent consume(final UUID victim) {
        final Tag tag = this.tags.remove(victim);
        if (tag == null) {
            return null;
        }
        if (System.currentTimeMillis() - tag.timestamp > this.windowMillis) {
            return null;
        }
        return tag.event;
    }

    void clear(final UUID victim) {
        this.tags.remove(victim);
    }

    private static final class Tag {
        final EntityDamageByEntityEvent event;
        final long timestamp;

        Tag(final EntityDamageByEntityEvent event, final long timestamp) {
            this.event = event;
            this.timestamp = timestamp;
        }
    }
}
