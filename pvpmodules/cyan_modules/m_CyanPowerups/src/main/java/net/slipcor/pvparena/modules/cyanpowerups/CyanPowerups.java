package net.slipcor.pvparena.modules.cyanpowerups;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PABlockLocation;
import net.slipcor.pvparena.compatibility.EffectTypeAdapter;
import net.slipcor.pvparena.compatibility.ParticleAdapter;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.regions.ArenaRegion;
import net.slipcor.pvparena.regions.RegionType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <pre>CyanPowerups — potion-effect powerups that drop into the arena floor.</pre>
 *
 * Derived from the upstream Powerups module, cut down to what Cyan actually uses: every
 * powerup is one potion effect (Invisibility, Strength, Speed, Health Boost, Heal), picked up
 * by walking over the dropped item.
 *
 * <p>Differences from upstream Powerups:</p>
 * <ul>
 *   <li>Drops are placed <b>2 blocks above the arena floor</b> (configurable), found by scanning
 *       down through any roof slab first — no more items raining onto a ceiling.</li>
 *   <li>Drops land <b>near the fight</b> — around the centre of the living fighters, or the arena
 *       centre when nobody is in yet — never in a far corner.</li>
 *   <li>The dropped item glows (outline visible through walls), carries a floating name, a
 *       particle beam and a halo so it is findable across the arena.</li>
 *   <li>Drop, pickup and expiry are broadcast to the arena.</li>
 * </ul>
 *
 * <p>All knobs live per-arena in the arena's own config.yml under
 * {@code modules.cyanpowerups.*} (defaults are written on first load):</p>
 * <pre>
 * modules:
 *   cyanpowerups:
 *     spawnIntervalSeconds: 90    # a powerup drops this often
 *     itemLifetimeSeconds: 60     # uncollected powerups vanish after this
 *     heightAboveGround: 2        # blocks above the floor block the item spawns at
 *     dropRadius: 16              # blocks around the fight centre a drop may land in
 *     beamHeight: 6               # height of the beam, 0 disables beam and halo
 *     beamParticle: CAMPFIRE_SIGNAL_SMOKE  # any name from PVP Arena's ParticleAdapter
 *     beamDensity: 1              # particles per half-block of beam
 *     announce: true              # broadcast drop / pickup / expiry
 *     powerups:                   # "EFFECT:seconds:amplifier:MATERIAL" (amplifier 0 = level I)
 *       - "SPEED:20:1:SUGAR"
 *       - "STRENGTH:15:0:BLAZE_POWDER"
 *       - "INVISIBILITY:15:0:FERMENTED_SPIDER_EYE"
 *       - "HEALTH_BOOST:45:1:GOLDEN_APPLE"
 *       - "HEAL:0:1:GLISTERING_MELON_SLICE"
 * </pre>
 */
public class CyanPowerups extends ArenaModule {

    private static final String ROOT = "modules.cyanpowerups.";

    /**
     * The five supported effects. Values come from PVP Arena's EffectTypeAdapter, which already
     * bridges the 1.20.5 potion renames — never touch PotionEffectType constants directly here.
     */
    private static final Map<String, PotionEffectType> EFFECTS = new LinkedHashMap<>();

    static {
        EFFECTS.put("INVISIBILITY", EffectTypeAdapter.INVISIBILITY);
        EFFECTS.put("STRENGTH", EffectTypeAdapter.STRENGTH);
        EFFECTS.put("SPEED", EffectTypeAdapter.SPEED);
        EFFECTS.put("HEALTH_BOOST", EffectTypeAdapter.HEALTH_BOOST);
        EFFECTS.put("HEAL", EffectTypeAdapter.INSTANT_HEALTH);
    }

    /** Minecraft has no "beacon beam" particle — a real beam needs a beacon block with sky access. */
    private static final String DEFAULT_BEAM_PARTICLE = "CAMPFIRE_SIGNAL_SMOKE";

    /** Keep drops this far off the region wall, so nobody has to fight in a corner. */
    private static final int EDGE_INSET = 3;

    private static final List<String> DEFAULT_POWERUPS = Arrays.asList(
            "SPEED:20:1:SUGAR",
            "STRENGTH:15:0:BLAZE_POWDER",
            "INVISIBILITY:15:0:FERMENTED_SPIDER_EYE",
            "HEALTH_BOOST:45:1:GOLDEN_APPLE",
            "HEAL:0:1:GLISTERING_MELON_SLICE");

    /** One configured powerup. Amplifier 0 = level I. */
    private record Powerup(String key, String label, PotionEffectType type, int seconds,
                           int amplifier, Material material) {
    }

    private final List<Powerup> powerups = new ArrayList<>();
    /** Live dropped items -> the elapsed second at which they vanish. */
    private final Map<Item, Integer> liveItems = new HashMap<>();

    private BukkitTask task;
    private Particle beamParticle;

    private int spawnInterval;
    private int itemLifetime;
    private int heightAboveGround;
    private int dropRadius;
    private int beamHeight;
    private int beamDensity;
    private boolean announce;

    public CyanPowerups() {
        super("CyanPowerups");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public boolean needsBattleRegion() {
        return true;
    }

    @Override
    public void configParse(final YamlConfiguration config) {
        boolean dirty = false;
        dirty |= setDefault(config, "spawnIntervalSeconds", 90);
        dirty |= setDefault(config, "itemLifetimeSeconds", 60);
        dirty |= setDefault(config, "heightAboveGround", 2);
        dirty |= setDefault(config, "dropRadius", 16);
        dirty |= setDefault(config, "beamHeight", 6);
        dirty |= setDefault(config, "beamParticle", DEFAULT_BEAM_PARTICLE);
        dirty |= setDefault(config, "beamDensity", 1);
        dirty |= setDefault(config, "announce", true);
        dirty |= setDefault(config, "powerups", DEFAULT_POWERUPS);
        if (dirty) {
            this.arena.getConfig().save();
        }

        this.spawnInterval = Math.max(1, config.getInt(ROOT + "spawnIntervalSeconds", 90));
        this.itemLifetime = Math.max(1, config.getInt(ROOT + "itemLifetimeSeconds", 60));
        this.heightAboveGround = Math.max(0, config.getInt(ROOT + "heightAboveGround", 2));
        this.dropRadius = Math.max(1, config.getInt(ROOT + "dropRadius", 16));
        this.beamHeight = Math.max(0, config.getInt(ROOT + "beamHeight", 6));
        this.beamDensity = Math.max(1, config.getInt(ROOT + "beamDensity", 1));
        this.announce = config.getBoolean(ROOT + "announce", true);
        this.beamParticle = parseParticle(config.getString(ROOT + "beamParticle", DEFAULT_BEAM_PARTICLE));

        this.powerups.clear();
        for (final String entry : config.getStringList(ROOT + "powerups")) {
            final Powerup powerup = parsePowerup(entry);
            if (powerup == null) {
                log("ignoring bad powerup entry '" + entry + "' in " + this.arena.getName());
            } else {
                this.powerups.add(powerup);
            }
        }
    }

    private boolean setDefault(final YamlConfiguration config, final String key, final Object value) {
        if (!config.contains(ROOT + key)) {
            this.arena.getConfig().setManually(ROOT + key, value);
            return true;
        }
        return false;
    }

    /** "EFFECT:seconds:amplifier:MATERIAL" */
    private static Powerup parsePowerup(final String entry) {
        final String[] parts = entry.split(":");
        if (parts.length < 4) {
            return null;
        }
        final String key = parts[0].trim().toUpperCase();
        // Known five first; anything else falls back to whatever the running server knows.
        PotionEffectType type = EFFECTS.get(key);
        if (type == null) {
            type = PotionEffectType.getByName(key);
        }
        final Material material = Material.matchMaterial(parts[3].trim().toUpperCase());
        if (type == null || material == null) {
            return null;
        }
        try {
            return new Powerup(key, prettify(key), type,
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), material);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolved through PVP Arena's ParticleAdapter, which carries the 1.20.5 particle renames —
     * so config uses the legacy names (CAMPFIRE_SIGNAL_SMOKE, SMOKE_LARGE, END_ROD, FLAME, ...).
     */
    private static Particle parseParticle(final String name) {
        try {
            return ParticleAdapter.valueOf(name.trim().toUpperCase()).getValue();
        } catch (final IllegalArgumentException e) {
            log("unknown beamParticle '" + name + "', falling back to " + DEFAULT_BEAM_PARTICLE);
            return ParticleAdapter.valueOf(DEFAULT_BEAM_PARTICLE).getValue();
        }
    }

    /** HEALTH_BOOST -> "Health Boost" */
    private static String prettify(final String key) {
        final StringBuilder result = new StringBuilder();
        for (final String word : key.split("_")) {
            if (!word.isEmpty()) {
                result.append(result.length() > 0 ? " " : "")
                        .append(word.charAt(0))
                        .append(word.substring(1).toLowerCase());
            }
        }
        return result.toString();
    }

    @Override
    public void parseStart() {
        this.reset(false); // defensive: never leak a task or item from a previous match

        if (this.powerups.isEmpty()) {
            log("no valid powerups configured — CyanPowerups stays inactive for " + this.arena.getName());
            return;
        }

        this.task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                this.elapsed++;
                if (this.elapsed % CyanPowerups.this.spawnInterval == 0) {
                    CyanPowerups.this.dropPowerup(this.elapsed);
                }
                CyanPowerups.this.tickLiveItems(this.elapsed);
            }
        }.runTaskTimer(PVPArena.getInstance(), 20L, 20L);
    }

    /** Expire what's due, beam what's left, forget what the world already removed. */
    private void tickLiveItems(final int elapsed) {
        this.liveItems.entrySet().removeIf(entry -> {
            final Item item = entry.getKey();
            if (!item.isValid()) {
                return true;
            }
            if (elapsed >= entry.getValue()) {
                item.remove();
                this.broadcast(ChatColor.GRAY + "The " + ChatColor.stripColor(name(item))
                        + " powerup vanished.");
                return true;
            }
            this.drawBeam(item);
            return false;
        });
    }

    private static String name(final Item item) {
        final String customName = item.getCustomName();
        return customName == null ? "powerup" : customName;
    }

    private void dropPowerup(final int elapsed) {
        final Powerup powerup = this.powerups.get(ThreadLocalRandom.current().nextInt(this.powerups.size()));
        final Location location = this.findDropLocation();
        if (location == null) {
            log("found no floor to drop a powerup on in " + this.arena.getName());
            return;
        }

        final ItemStack stack = new ItemStack(powerup.material());
        final ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + powerup.label() + " Powerup");
        meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, powerup.key());
        stack.setItemMeta(meta);

        final Item item = location.getWorld().dropItem(location, stack);
        item.setVelocity(new Vector());
        item.setGlowing(true); // outline, visible through walls
        item.setCustomName(ChatColor.GOLD + powerup.label());
        item.setCustomNameVisible(true);
        item.setPickupDelay(0);
        this.liveItems.put(item, elapsed + this.itemLifetime);

        location.getWorld().playSound(location, "block.beacon.activate", 1f, 1.4f);
        this.broadcast(ChatColor.YELLOW + "A " + ChatColor.GOLD + powerup.label()
                + ChatColor.YELLOW + " powerup dropped at " + ChatColor.WHITE
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + ChatColor.YELLOW + "!");
    }

    /**
     * Beacon-style column so the drop is visible from across the arena, plus a halo at item height
     * — item entities render small and cannot be scaled, so the halo is what gives them presence.
     */
    private void drawBeam(final Item item) {
        if (this.beamHeight <= 0 || this.beamParticle == null) {
            return;
        }
        final Location base = item.getLocation();
        final World world = base.getWorld();

        for (double offset = 0; offset < this.beamHeight; offset += 0.5) {
            world.spawnParticle(this.beamParticle, base.getX(), base.getY() + offset, base.getZ(),
                    this.beamDensity, 0, 0, 0, 0);
        }

        for (int step = 0; step < 8; step++) {
            final double angle = step * Math.PI / 4;
            world.spawnParticle(this.beamParticle,
                    base.getX() + Math.cos(angle) * 0.7, base.getY() + 0.3,
                    base.getZ() + Math.sin(angle) * 0.7, 1, 0, 0, 0, 0);
        }
    }

    /**
     * A spot near the fight, {@code heightAboveGround} blocks over the floor block. Upstream picked
     * uniformly across the whole region (and used {@code getHighestBlockAt}, which lands on the roof
     * of covered arenas), so powerups routinely spawned in a corner nobody would walk to.
     */
    private Location findDropLocation() {
        final ArenaRegion battle = this.arena.getRegionsByType(RegionType.BATTLE).stream()
                .findFirst().orElse(null);
        if (battle == null) {
            return null;
        }
        final PABlockLocation min = battle.getShape().getMinimumLocation();
        final PABlockLocation max = battle.getShape().getMaximumLocation();
        final World world = Bukkit.getWorld(min.getWorldName());
        if (world == null) {
            return null;
        }
        final int minY = Math.max(min.getY(), world.getMinHeight());
        final int maxY = Math.min(max.getY(), world.getMaxHeight() - 3);
        if (maxY <= minY) {
            return null;
        }

        final double centerX = (min.getX() + max.getX()) / 2.0;
        final double centerZ = (min.getZ() + max.getZ()) / 2.0;
        final double[] focus = this.fightCenter(world, centerX, centerZ);
        // Bounds keep the drop off the walls, but never exclude the arena centre in a small region.
        final int loX = Math.min(min.getX() + EDGE_INSET, (int) centerX);
        final int hiX = Math.max(max.getX() - EDGE_INSET, (int) centerX);
        final int loZ = Math.min(min.getZ() + EDGE_INSET, (int) centerZ);
        final int hiZ = Math.max(max.getZ() - EDGE_INSET, (int) centerZ);

        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int tries = 0; tries < 30; tries++) {
            // Widen as tries fail, so a fight standing on unusable ground still gets its powerup.
            final double radius = this.dropRadius * (1 + tries / 10.0);
            final double angle = random.nextDouble(2 * Math.PI);
            final double distance = Math.sqrt(random.nextDouble()) * radius;
            final int x = clamp((int) Math.round(focus[0] + Math.cos(angle) * distance), loX, hiX);
            final int z = clamp((int) Math.round(focus[1] + Math.sin(angle) * distance), loZ, hiZ);
            final Block ground = findGround(world, x, z, minY, maxY);
            if (ground != null) {
                return ground.getLocation().add(0.5, this.heightAboveGround, 0.5);
            }
        }
        return null;
    }

    /** Centre of the living fighters, or the arena centre while nobody is fighting. */
    private double[] fightCenter(final World world, final double centerX, final double centerZ) {
        double sumX = 0;
        double sumZ = 0;
        int counted = 0;
        for (final ArenaPlayer arenaPlayer : this.arena.getFighters()) {
            final Player player = arenaPlayer.getPlayer();
            if (arenaPlayer.getStatus() == PlayerStatus.FIGHT && player != null && !player.isDead()
                    && world.equals(player.getWorld())) {
                sumX += player.getLocation().getX();
                sumZ += player.getLocation().getZ();
                counted++;
            }
        }
        return counted == 0 ? new double[]{centerX, centerZ}
                : new double[]{sumX / counted, sumZ / counted};
    }

    private static int clamp(final int value, final int low, final int high) {
        return Math.max(low, Math.min(high, value));
    }

    /**
     * Lowest floor block in the column with 2 blocks of clear headroom. Scanning <b>up</b> from the
     * region floor — not down from the sky — is what keeps drops off the roof of covered arenas:
     * a ceiling is always above the floor, so the floor is found first.
     *
     * <p>ponytail: a BATTLE region whose min Y reaches far under the arena floor can therefore
     * pick a cave below it — tighten the region's lower bound rather than complicating this.</p>
     */
    private static Block findGround(final World world, final int x, final int z,
                                    final int minY, final int maxY) {
        for (int y = minY; y <= maxY - 2; y++) {
            final Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()
                    && isFree(world.getBlockAt(x, y + 1, z))
                    && isFree(world.getBlockAt(x, y + 2, z))) {
                return block;
            }
        }
        return null;
    }

    /** Walkable air: passable and not lava/water — an item must not drop into a liquid. */
    private static boolean isFree(final Block block) {
        return block.isPassable() && !block.isLiquid();
    }

    @Override
    public void onPlayerPickupItem(final EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final Item entity = event.getItem();
        final String key = tagOf(entity.getItemStack());
        if (key == null) {
            return;
        }

        event.setCancelled(true); // the effect is the reward, the item never enters the inventory
        this.liveItems.remove(entity);
        entity.remove();

        final Player player = (Player) event.getEntity();
        this.powerups.stream()
                .filter(powerup -> powerup.key().equals(key))
                .findFirst()
                .ifPresent(powerup -> {
                    final int ticks = powerup.type().isInstant() ? 1 : Math.max(1, powerup.seconds()) * 20;
                    player.addPotionEffect(new PotionEffect(powerup.type(), ticks, powerup.amplifier(),
                            false, true, true));
                    player.playSound(player.getLocation(), "entity.player.levelup", 1f, 1.6f);
                    this.broadcast(ChatColor.GREEN + player.getName() + ChatColor.YELLOW
                            + " grabbed the " + ChatColor.GOLD + powerup.label()
                            + ChatColor.YELLOW + " powerup!");
                });
    }

    private static String tagOf(final ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(key(), PersistentDataType.STRING);
    }

    /** Built on demand: the plugin instance is not guaranteed at module class-load time. */
    private static NamespacedKey key() {
        return new NamespacedKey(PVPArena.getInstance(), "cyanpowerup");
    }

    private void broadcast(final String message) {
        if (this.announce) {
            this.arena.broadcast(message);
        }
    }

    /** Powerup effects are ours — they never follow a player out of the arena. */
    @Override
    public void resetPlayer(final Player player, final boolean soft, final boolean force) {
        if (player != null) {
            EFFECTS.values().forEach(player::removePotionEffect);
        }
    }

    @Override
    public void reset(final boolean force) {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        this.liveItems.keySet().forEach(Item::remove);
        this.liveItems.clear();
        // Effects are cleared per player in resetPlayer — doing it here would also strip the
        // effects a class hands out, since parseStart calls reset() defensively.
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage(String.format(
                "Powerups: %d | Every: %ds | Lifetime: %ds | Height: +%d | Radius: %d | Beam: %d x%d",
                this.powerups.size(), this.spawnInterval, this.itemLifetime, this.heightAboveGround,
                this.dropRadius, this.beamHeight, this.beamDensity));
    }

    private static void log(final String message) {
        final PVPArena instance = PVPArena.getInstance();
        (instance != null ? instance.getLogger() : Bukkit.getLogger())
                .warning("[CyanPowerups] " + message);
    }
}
