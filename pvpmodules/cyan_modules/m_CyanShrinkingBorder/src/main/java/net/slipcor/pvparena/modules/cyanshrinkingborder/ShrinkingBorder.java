package net.slipcor.pvparena.modules.cyanshrinkingborder;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PABlockLocation;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.regions.ArenaRegion;
import net.slipcor.pvparena.regions.RegionType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.TreeMap;

/**
 * <pre>ShrinkingBorder — a battle-royale style closing border for any arena.</pre>
 *
 * At match start every fighter gets a <b>per-player virtual world border</b> (1.18.2+ API)
 * sized exactly to the arena's BATTLE region — the real world border is never touched, so
 * nothing outside the arena is affected. At configured times the border shrinks by a
 * configured percentage of the original size, with a smooth animation.
 *
 * <p>Vanilla does <b>not</b> push players inward, and virtual borders apply no server-side
 * damage — so this module damages players outside the border on its own schedule
 * (default: every 2 seconds) until they die or get back inside.</p>
 *
 * <p>All knobs live per-arena in the arena's own config.yml under
 * {@code modules.shrinkingborder.*} (defaults are written on first load):</p>
 * <pre>
 * modules:
 *   shrinkingborder:
 *     startDelaySeconds: 0        # extra delay added to every stage time
 *     shrinkDurationSeconds: 30   # how long each shrink animates
 *     damageIntervalSeconds: 2    # hurt players outside every N seconds
 *     damageAmount: 2.0           # damage per hit (2.0 = 1 heart)
 *     warningDistance: 5          # red vignette this close to the border
 *     centerRandomRadius: 0       # final zone center is picked randomly within this many
 *                                 # blocks of the exact arena center (0 = always exact center)
 *     stages:                     # "secondsIntoMatch:percentSmaller"
 *       - "300:20"
 *       - "600:40"
 *       - "900:60"
 *       - "1200:80"
 *       - "1800:95"
 * </pre>
 */
public class ShrinkingBorder extends ArenaModule {

    private static final String ROOT = "modules.shrinkingborder.";

    private WorldBorder border;
    private BukkitTask task;

    private String worldName;
    private double centerX;          // exact arena center
    private double centerZ;
    private double offsetX;          // secret random target offset from the exact center
    private double offsetZ;
    private double currentCenterX;   // effective border center right now (drifts toward the target)
    private double currentCenterZ;
    private double halfExtentX;      // arena half-extents, per axis — the border must stay inside
    private double halfExtentZ;

    // Our own size interpolation — virtual borders don't reliably lerp getSize() server-side.
    private double shrinkFrom;
    private double shrinkTo;
    private int shrinkStartSecond;
    private int shrinkDurationSeconds;

    public ShrinkingBorder() {
        super("ShrinkingBorder");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public boolean needsBattleRegion() {
        return true;
    }

    /** Write editable defaults into the arena's config.yml the first time the module loads. */
    @Override
    public void configParse(final YamlConfiguration config) {
        boolean dirty = false;
        dirty |= setDefault(config, "startDelaySeconds", 0);
        dirty |= setDefault(config, "shrinkDurationSeconds", 30);
        dirty |= setDefault(config, "damageIntervalSeconds", 2);
        dirty |= setDefault(config, "damageAmount", 2.0);
        dirty |= setDefault(config, "warningDistance", 5);
        dirty |= setDefault(config, "centerRandomRadius", 0);
        dirty |= setDefault(config, "stages", java.util.Arrays.asList(
                "300:20", "600:40", "900:60", "1200:80", "1800:95"));
        if (dirty) {
            this.arena.getConfig().save();
        }
    }

    private boolean setDefault(final YamlConfiguration config, final String key, final Object value) {
        if (!config.contains(ROOT + key)) {
            this.arena.getConfig().setManually(ROOT + key, value);
            return true;
        }
        return false;
    }

    @Override
    public void parseStart() {
        this.reset(false); // defensive: never leak a task from a previous match

        final ArenaRegion battle = this.arena.getRegionsByType(RegionType.BATTLE).stream()
                .findFirst().orElse(null);
        if (battle == null) {
            log("no BATTLE region — ShrinkingBorder stays inactive for " + this.arena.getName());
            return;
        }

        final PABlockLocation min = battle.getShape().getMinimumLocation();
        final PABlockLocation max = battle.getShape().getMaximumLocation();
        this.worldName = battle.getWorldName();
        this.centerX = (min.getX() + max.getX() + 1) / 2.0;
        this.centerZ = (min.getZ() + max.getZ() + 1) / 2.0;
        this.currentCenterX = this.centerX;
        this.currentCenterZ = this.centerZ;
        this.halfExtentX = (max.getX() - min.getX() + 1) / 2.0;
        this.halfExtentZ = (max.getZ() - min.getZ() + 1) / 2.0;
        // Border is square: take the larger horizontal extent so the whole region fits.
        final double initialSize = Math.max(max.getX() - min.getX(), max.getZ() - min.getZ()) + 1;

        final YamlConfiguration cfg = this.arena.getConfig().getYamlConfiguration();
        final int startDelay = cfg.getInt(ROOT + "startDelaySeconds", 0);
        final int shrinkDuration = Math.max(1, cfg.getInt(ROOT + "shrinkDurationSeconds", 30));
        final int damageInterval = Math.max(1, cfg.getInt(ROOT + "damageIntervalSeconds", 2));
        final double damageAmount = cfg.getDouble(ROOT + "damageAmount", 2.0);
        final int warningDistance = cfg.getInt(ROOT + "warningDistance", 5);
        final double centerRandomRadius = cfg.getDouble(ROOT + "centerRandomRadius", 0);

        // Pick this match's secret final center: uniform within a disc of centerRandomRadius.
        this.offsetX = 0;
        this.offsetZ = 0;
        if (centerRandomRadius > 0) {
            final java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            final double angle = random.nextDouble(2 * Math.PI);
            final double distance = Math.sqrt(random.nextDouble()) * centerRandomRadius;
            this.offsetX = Math.cos(angle) * distance;
            this.offsetZ = Math.sin(angle) * distance;
        }

        // "secondsIntoMatch:percentSmaller" → (seconds+delay) → target size
        final TreeMap<Integer, Double> stages = new TreeMap<>();
        for (final String entry : cfg.getStringList(ROOT + "stages")) {
            final String[] parts = entry.split(":");
            try {
                final int at = Integer.parseInt(parts[0].trim()) + startDelay;
                final double pct = Double.parseDouble(parts[1].trim());
                stages.put(at, Math.max(1.0, initialSize * (1 - pct / 100.0)));
            } catch (final Exception e) {
                log("ignoring bad stage entry '" + entry + "' in " + this.arena.getName());
            }
        }

        this.border = Bukkit.createWorldBorder();
        this.border.setCenter(this.centerX, this.centerZ);
        this.border.setSize(initialSize);
        this.border.setWarningDistance(warningDistance);
        this.border.setDamageAmount(0); // damage is ours, and virtual borders don't deal it anyway
        this.shrinkFrom = initialSize;
        this.shrinkTo = initialSize;
        this.shrinkStartSecond = 0;
        this.shrinkDurationSeconds = 0;

        this.arena.getFighters().forEach(this::applyBorder);

        this.task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                this.elapsed++;

                if (this.elapsed == 1) {
                    // Heal pass: start teleports can make the server re-send the world's real
                    // border, wiping the virtual one — re-apply once everyone has arrived.
                    ShrinkingBorder.this.arena.getFighters().forEach(ShrinkingBorder.this::applyBorder);
                }

                final Map.Entry<Integer, Double> stage = stages.floorEntry(this.elapsed);
                if (stage != null && stage.getValue() < ShrinkingBorder.this.shrinkTo) {
                    ShrinkingBorder.this.shrinkFrom = ShrinkingBorder.this.currentSize(this.elapsed);
                    ShrinkingBorder.this.shrinkTo = stage.getValue();
                    ShrinkingBorder.this.shrinkStartSecond = this.elapsed;
                    ShrinkingBorder.this.shrinkDurationSeconds = shrinkDuration;
                    ShrinkingBorder.this.moveCenterFor(stage.getValue());
                    ShrinkingBorder.this.border.setSize(stage.getValue(), shrinkDuration);
                    ShrinkingBorder.this.arena.broadcast(org.bukkit.ChatColor.RED
                            + "The border is shrinking!");
                }

                if (this.elapsed % damageInterval == 0) {
                    ShrinkingBorder.this.damageOutsidePlayers(this.elapsed, damageAmount);
                }
            }
        }.runTaskTimer(PVPArena.getInstance(), 20L, 20L);
    }

    /**
     * Drift the border center toward the secret random target, clamped per axis so the border
     * square never leaves the arena. Small borders allow big offsets, big borders small ones —
     * so the center converges on the target gradually, stage by stage. (Vanilla can't animate
     * center moves, only size — the per-stage clamp keeps each jump small.)
     */
    private void moveCenterFor(final double newSize) {
        final double allowedX = Math.max(0, this.halfExtentX - newSize / 2.0);
        final double allowedZ = Math.max(0, this.halfExtentZ - newSize / 2.0);
        this.currentCenterX = this.centerX + clamp(this.offsetX, allowedX);
        this.currentCenterZ = this.centerZ + clamp(this.offsetZ, allowedZ);
        this.border.setCenter(this.currentCenterX, this.currentCenterZ);
    }

    private static double clamp(final double value, final double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    /** Current border half-width follows our own linear interpolation during a shrink. */
    private double currentSize(final int elapsed) {
        if (this.shrinkDurationSeconds <= 0
                || elapsed >= this.shrinkStartSecond + this.shrinkDurationSeconds) {
            return this.shrinkTo;
        }
        final double t = (elapsed - this.shrinkStartSecond) / (double) this.shrinkDurationSeconds;
        return this.shrinkFrom + (this.shrinkTo - this.shrinkFrom) * t;
    }

    private void damageOutsidePlayers(final int elapsed, final double damageAmount) {
        final double half = this.currentSize(elapsed) / 2.0;
        for (final ArenaPlayer arenaPlayer : this.arena.getFighters()) {
            if (arenaPlayer.getStatus() != PlayerStatus.FIGHT) {
                continue;
            }
            final Player player = arenaPlayer.getPlayer();
            if (player == null || player.isDead()
                    || !player.getWorld().getName().equals(this.worldName)) {
                continue;
            }
            final Location loc = player.getLocation();
            if (Math.abs(loc.getX() - this.currentCenterX) > half
                    || Math.abs(loc.getZ() - this.currentCenterZ) > half) {
                player.damage(damageAmount);
            }
        }
    }

    private void applyBorder(final ArenaPlayer arenaPlayer) {
        final Player player = arenaPlayer.getPlayer();
        if (player != null && this.border != null) {
            player.setWorldBorder(this.border);
        }
    }

    /** Late joiners get the current border too — delayed past the join teleport, which would wipe it. */
    @Override
    public void lateJoin(final Player player) {
        if (this.border != null) {
            Bukkit.getScheduler().runTaskLater(PVPArena.getInstance(),
                    () -> {
                        if (this.border != null && player.isOnline()) {
                            player.setWorldBorder(this.border);
                        }
                    }, 2L);
        }
    }

    /** Respawning makes the server re-send the real world border — put ours back. */
    @Override
    public void parseRespawn(final Player player, final net.slipcor.pvparena.arena.ArenaTeam team,
                             final org.bukkit.event.entity.EntityDamageEvent.DamageCause cause,
                             final org.bukkit.entity.Entity damager) {
        this.lateJoin(player);
    }

    /** Every player leaving the match (death, quit, end) gets the real world border back. */
    @Override
    public void resetPlayer(final Player player, final boolean soft, final boolean force) {
        if (player != null) {
            player.setWorldBorder(null);
        }
    }

    @Override
    public void reset(final boolean force) {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        if (this.border != null) {
            // Defensive sweep: anyone still holding the virtual border gets released.
            this.arena.getFighters().forEach(ap -> {
                final Player player = ap.getPlayer();
                if (player != null) {
                    player.setWorldBorder(null);
                }
            });
            this.border = null;
        }
    }

    private static void log(final String message) {
        final PVPArena instance = PVPArena.getInstance();
        (instance != null ? instance.getLogger() : Bukkit.getLogger())
                .warning("[ShrinkingBorder] " + message);
    }
}
