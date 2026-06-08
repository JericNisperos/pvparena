package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A cancelable per-second countdown for a {@link Challenge}, used for two phases:
 * <ul>
 *   <li>{@link Phase#TELEPORT} — "teleporting in Ns" warning shown once both rosters are full, while
 *       players are still free in the world (before they're pulled into the arena).</li>
 *   <li>{@link Phase#FIGHT} — the in-lounge "fight starts in Ns" countdown after the teleport.</li>
 * </ul>
 * Either way it messages the challenge's roster members directly (chat + title), so it works whether
 * or not they're in the arena yet. On reaching zero it runs {@code onFinish}; stopped (without firing)
 * if a participant drops mid-countdown.
 */
final class GuildWarCountdown extends BukkitRunnable {

    enum Phase { TELEPORT, FIGHT }

    private final Challenge challenge;
    private final Phase phase;
    private final Runnable onFinish;
    private int remaining;
    private boolean active = true;

    GuildWarCountdown(final Challenge challenge, final int seconds, final Phase phase, final Runnable onFinish) {
        this.challenge = challenge;
        this.phase = phase;
        this.remaining = Math.max(1, seconds);
        this.onFinish = onFinish;
    }

    void start() {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        runTaskTimer(plugin, 0L, 20L);
    }

    void stop() {
        if (this.active) {
            this.active = false;
            try {
                cancel();
            } catch (final IllegalStateException ignored) {
                // not scheduled yet — fine
            }
        }
    }

    @Override
    public void run() {
        if (!this.active) {
            return;
        }
        if (this.remaining <= 0) {
            stop();
            this.onFinish.run();
            return;
        }

        final String chat = this.phase == Phase.TELEPORT
                ? ChatColor.GOLD + "Lineup complete! Teleporting in " + ChatColor.YELLOW + this.remaining + ChatColor.GOLD + "s…"
                : ChatColor.GOLD + "Fight starts in " + ChatColor.YELLOW + this.remaining + ChatColor.GOLD + "s…";
        final String subtitle = this.phase == Phase.TELEPORT ? "Get ready!" : "Prepare to fight!";
        for (final Player p : participants()) {
            p.sendMessage(GuildWarMessages.PREFIX + chat);
            p.sendTitle(ChatColor.YELLOW + "" + this.remaining, ChatColor.GRAY + subtitle, 0, 25, 5);
        }
        this.remaining--;
    }

    private Set<Player> participants() {
        final Set<Player> out = new LinkedHashSet<>();
        final Set<UUID> all = new LinkedHashSet<>();
        all.addAll(this.challenge.rosterA);
        all.addAll(this.challenge.rosterB);
        for (final UUID id : all) {
            final Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
