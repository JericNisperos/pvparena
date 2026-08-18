package net.slipcor.pvparena.goals.cyanvillagedefense;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.ArenaPlayer;
import net.slipcor.pvparena.arena.ArenaTeam;
import net.slipcor.pvparena.arena.PlayerStatus;
import net.slipcor.pvparena.classes.PADeathInfo;
import net.slipcor.pvparena.classes.PASpawn;
import net.slipcor.pvparena.core.Config.CFG;
import net.slipcor.pvparena.events.goal.PAGoalEndEvent;
import net.slipcor.pvparena.goals.AbstractPlayerLivesGoal;
import net.slipcor.pvparena.loadables.ArenaModuleManager;
import net.slipcor.pvparena.managers.SpawnManager;
import net.slipcor.pvparena.managers.WorkflowManager;
import net.slipcor.pvparena.runnables.EndRunnable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * <pre>VillageDefense — a co-op PvE goal inspired by Plugily's Village Defense.</pre>
 *
 * Players defend a set of villagers against waves of mobs. There are <b>no winners</b>: the match is
 * over when <b>every villager is dead</b> or <b>every defender is dead</b> — game over, everyone out.
 * No respawns by default (one life per player, configurable).
 *
 * <p><b>Spawns</b> (all FFA-style, no teams):</p>
 * <ul>
 *     <li>{@code fight1..N} — where the defenders start (standard FFA spawns)</li>
 *     <li>{@code villager1..N} — one (or more) villagers are spawned at each of these</li>
 *     <li>{@code mob1..N} — wave mobs appear here, round-robin</li>
 * </ul>
 *
 * <p>All tuning lives in the <b>arena config</b> under {@code goal.villagedefense.*} (written by
 * {@link #setDefaults}), so every arena can have its own wave curve and it is all editable with
 * {@code /pa <arena> set goal.villagedefense.<key> <value>}. The companion module that used to own a
 * shared YAML file is gone — the goal jar hot-reloads, so it carries everything itself, including the
 * auto-start countdown ({@link VillageDefenseAutoStart}).</p>
 *
 * <p>The wave spawner is deliberately <b>simple</b> (vanilla mobs, linear scaling) — it will be
 * replaced by a MythicMobs-backed spawner later (see {@code plans/villagedefense-phases.md}).</p>
 *
 * <p>Shipped as an external goal jar in {@code PVPArena/goals/} — no core edit.</p>
 */
public class GoalVillageDefense extends AbstractPlayerLivesGoal implements Listener {

    static final String VILLAGER_SPAWN = "villager";
    static final String MOB_SPAWN = "mob";

    private static final String CFG_AUTOSTART = "goal.villagedefense.autostart-seconds";
    private static final String CFG_LIVES = "goal.villagedefense.player-lives";
    private static final String CFG_ANNOUNCE_WAVE_START = "goal.villagedefense.announce.wave-start";
    private static final String CFG_ANNOUNCE_WAVE_CLEARED = "goal.villagedefense.announce.wave-cleared";
    private static final String CFG_ANNOUNCE_VILLAGER_DEATH = "goal.villagedefense.announce.villager-death";
    private static final String CFG_VILLAGERS_PER_SPAWN = "goal.villagedefense.villagers.per-spawn";
    private static final String CFG_PROTECT_VILLAGERS = "goal.villagedefense.villagers.protect-from-players";
    private static final String CFG_PROFESSION = "goal.villagedefense.villagers.profession";
    private static final String CFG_TRADES = "goal.villagedefense.villagers.trades";
    private static final String CFG_TRADE_MAX_USES = "goal.villagedefense.villagers.trade-max-uses";
    private static final String CFG_FIRST_DELAY = "goal.villagedefense.waves.first-delay-seconds";
    private static final String CFG_INTERVAL = "goal.villagedefense.waves.interval-seconds";
    private static final String CFG_BASE_MOBS = "goal.villagedefense.waves.base-mobs";
    private static final String CFG_MOBS_PER_WAVE = "goal.villagedefense.waves.mobs-per-wave";
    private static final String CFG_MOBS_PER_PLAYER = "goal.villagedefense.waves.mobs-per-player";
    private static final String CFG_MAX_MOBS_ALIVE = "goal.villagedefense.waves.max-mobs-alive";
    private static final String CFG_MOB_TYPE = "goal.villagedefense.waves.mob-type";
    private static final String CFG_FIRE_RESISTANT = "goal.villagedefense.waves.fire-resistant-mobs";
    private static final String CFG_EMERALD_CHANCE = "goal.villagedefense.drops.emerald-chance";
    private static final String CFG_EMERALD_BASE = "goal.villagedefense.drops.emerald-base";
    private static final String CFG_EMERALD_WAVES_PER_EXTRA = "goal.villagedefense.drops.emerald-waves-per-extra";

    private static final int DEF_AUTOSTART = 60;
    private static final int DEF_LIVES = 1;
    private static final int DEF_VILLAGERS_PER_SPAWN = 1;
    private static final int DEF_FIRST_DELAY = 15;
    private static final int DEF_INTERVAL = 30;
    private static final int DEF_BASE_MOBS = 3;
    private static final int DEF_MOBS_PER_WAVE = 2;
    private static final int DEF_MOBS_PER_PLAYER = 1;
    private static final int DEF_MAX_MOBS_ALIVE = 40;
    private static final String DEF_MOB_TYPE = "ZOMBIE";
    private static final double DEF_EMERALD_CHANCE = 0.4d;
    private static final int DEF_EMERALD_BASE = 1;
    private static final int DEF_EMERALD_WAVES_PER_EXTRA = 3;
    private static final String DEF_PROFESSION = "WEAPONSMITH";
    private static final int DEF_TRADE_MAX_USES = 9999;

    /** {@code <cost material> [amount] > <result material> [amount]} — cost must be emerald(s). */
    private static final List<String> DEF_TRADES = Arrays.asList(
            "EMERALD 8 > IRON_SWORD 1",
            "EMERALD 16 > IRON_CHESTPLATE 1",
            "EMERALD 24 > BOW 1",
            "EMERALD 4 > ARROW 16",
            "EMERALD 6 > GOLDEN_APPLE 1",
            "EMERALD 12 > COOKED_BEEF 16",
            "EMERALD_BLOCK 2 > DIAMOND_SWORD 1",
            "EMERALD_BLOCK 3 > DIAMOND_CHESTPLATE 1");

    private final Set<UUID> villagers = new HashSet<>();
    private final Set<UUID> waveMobs = new HashSet<>();
    private BukkitRunnable waveTask;
    private int currentWave;
    private boolean villagersSpawned;
    private boolean listening;

    public GoalVillageDefense() {
        super("VillageDefense");
        VillageDefenseAutoStart.ensureRegistered();
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public boolean isFreeForAll() {
        return true;
    }

    // ---- config (arena config, goal.villagedefense.*) ---------------------------------------------

    @Override
    public void setDefaults(final YamlConfiguration config) {
        config.addDefault(CFG_AUTOSTART, DEF_AUTOSTART);
        config.addDefault(CFG_LIVES, DEF_LIVES);
        config.addDefault(CFG_ANNOUNCE_WAVE_START, true);
        config.addDefault(CFG_ANNOUNCE_WAVE_CLEARED, true);
        config.addDefault(CFG_ANNOUNCE_VILLAGER_DEATH, true);
        config.addDefault(CFG_VILLAGERS_PER_SPAWN, DEF_VILLAGERS_PER_SPAWN);
        config.addDefault(CFG_PROTECT_VILLAGERS, true);
        config.addDefault(CFG_PROFESSION, DEF_PROFESSION);
        config.addDefault(CFG_TRADES, DEF_TRADES);
        config.addDefault(CFG_TRADE_MAX_USES, DEF_TRADE_MAX_USES);
        config.addDefault(CFG_FIRST_DELAY, DEF_FIRST_DELAY);
        config.addDefault(CFG_INTERVAL, DEF_INTERVAL);
        config.addDefault(CFG_BASE_MOBS, DEF_BASE_MOBS);
        config.addDefault(CFG_MOBS_PER_WAVE, DEF_MOBS_PER_WAVE);
        config.addDefault(CFG_MOBS_PER_PLAYER, DEF_MOBS_PER_PLAYER);
        config.addDefault(CFG_MAX_MOBS_ALIVE, DEF_MAX_MOBS_ALIVE);
        config.addDefault(CFG_MOB_TYPE, DEF_MOB_TYPE);
        config.addDefault(CFG_FIRE_RESISTANT, true);
        config.addDefault(CFG_EMERALD_CHANCE, DEF_EMERALD_CHANCE);
        config.addDefault(CFG_EMERALD_BASE, DEF_EMERALD_BASE);
        config.addDefault(CFG_EMERALD_WAVES_PER_EXTRA, DEF_EMERALD_WAVES_PER_EXTRA);
    }

    private YamlConfiguration yaml() {
        return this.arena.getConfig().getYamlConfiguration();
    }

    /** Seconds between the first player joining and the match auto-starting. */
    int autostartSeconds() {
        return Math.max(5, this.yaml().getInt(CFG_AUTOSTART, DEF_AUTOSTART));
    }

    @Override
    protected int getLivesConfigValue() {
        return Math.max(1, this.yaml().getInt(CFG_LIVES, DEF_LIVES));
    }

    private int villagersPerSpawn() {
        return Math.max(1, this.yaml().getInt(CFG_VILLAGERS_PER_SPAWN, DEF_VILLAGERS_PER_SPAWN));
    }

    private int firstWaveDelaySeconds() {
        return Math.max(1, this.yaml().getInt(CFG_FIRST_DELAY, DEF_FIRST_DELAY));
    }

    private int waveIntervalSeconds() {
        return Math.max(5, this.yaml().getInt(CFG_INTERVAL, DEF_INTERVAL));
    }

    /** Emeralds dropped by a wave mob on the given wave: base + one extra every N waves. */
    private int emeraldDropAmount(final int wave) {
        final int base = Math.max(1, this.yaml().getInt(CFG_EMERALD_BASE, DEF_EMERALD_BASE));
        final int per = Math.max(1, this.yaml().getInt(CFG_EMERALD_WAVES_PER_EXTRA, DEF_EMERALD_WAVES_PER_EXTRA));
        return base + Math.max(0, wave - 1) / per;
    }

    /**
     * Resolve the configured profession, or {@code null} if it can't be resolved at all.
     *
     * <p>Looked up through {@link Registry} rather than {@code Villager.Profession.valueOf(..)}: the
     * profession is an enum in the 1.18 API we compile against but a registry-backed <i>interface</i>
     * on modern servers, so a direct enum call blows up at runtime with IncompatibleClassChangeError.
     * {@code Registry.get} is an interface method in both, so it links either way.</p>
     */
    private Villager.Profession profession() {
        final String name = this.yaml().getString(CFG_PROFESSION, DEF_PROFESSION);
        Villager.Profession profession = professionByName(name);
        if (profession == null) {
            PVPArena.getInstance().getLogger().warning("[VillageDefense] Unknown " + CFG_PROFESSION + " '"
                    + name + "' — falling back to " + DEF_PROFESSION + ".");
            profession = professionByName(DEF_PROFESSION);
        }
        return profession;
    }

    private static Villager.Profession professionByName(final String name) {
        try {
            return Registry.VILLAGER_PROFESSION.get(
                    NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Build a fresh set of {@link MerchantRecipe}s from {@link #CFG_TRADES}. Each line is
     * {@code <cost material> [amount] > <result material> [amount]}; the cost must be EMERALD or
     * EMERALD_BLOCK, so emeralds stay the only currency. Malformed lines are logged and skipped
     * rather than aborting the match.
     */
    private List<MerchantRecipe> buildTrades() {
        final int maxUses = Math.max(1, this.yaml().getInt(CFG_TRADE_MAX_USES, DEF_TRADE_MAX_USES));
        List<String> lines = this.yaml().getStringList(CFG_TRADES);
        if (lines.isEmpty()) {
            lines = DEF_TRADES;
        }

        final List<MerchantRecipe> recipes = new ArrayList<>();
        for (final String line : lines) {
            final String[] halves = line.split(">");
            if (halves.length != 2) {
                this.warnTrade(line, "expected '<cost> > <result>'");
                continue;
            }
            final ItemStack cost = parseItem(halves[0]);
            final ItemStack result = parseItem(halves[1]);
            if (cost == null || result == null) {
                this.warnTrade(line, "unknown material or amount");
                continue;
            }
            if (cost.getType() != Material.EMERALD && cost.getType() != Material.EMERALD_BLOCK) {
                this.warnTrade(line, "cost must be EMERALD or EMERALD_BLOCK");
                continue;
            }
            final MerchantRecipe recipe = new MerchantRecipe(result, maxUses);
            recipe.addIngredient(cost);
            recipes.add(recipe);
        }
        return recipes;
    }

    /** {@code "EMERALD 8"} / {@code "IRON_SWORD"} -> ItemStack, or null if unparseable. */
    private static ItemStack parseItem(final String token) {
        final String[] parts = token.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        final Material material = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), amount)));
    }

    private void warnTrade(final String line, final String why) {
        PVPArena.getInstance().getLogger().warning("[VillageDefense] Arena '" + this.arena.getName()
                + "' skipping bad trade '" + line + "': " + why + ".");
    }

    private EntityType mobType() {
        final String typeName = this.yaml().getString(CFG_MOB_TYPE, DEF_MOB_TYPE);
        try {
            final EntityType parsed = EntityType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
            if (parsed.isAlive() && parsed.isSpawnable()) {
                return parsed;
            }
        } catch (final IllegalArgumentException e) {
            PVPArena.getInstance().getLogger().warning("[VillageDefense] Unknown " + CFG_MOB_TYPE + " '"
                    + typeName + "' — falling back to ZOMBIE.");
        }
        return EntityType.ZOMBIE;
    }

    // ---- spawns: fight (players) + villager + mob ------------------------------------------------

    @Override
    public Set<PASpawn> checkForMissingSpawns(final Set<PASpawn> spawns) {
        final Set<PASpawn> missing = SpawnManager.getMissingFFASpawn(this.arena, spawns);
        if (spawns.stream().noneMatch(spawn -> spawn.getName().toLowerCase(Locale.ROOT).startsWith(VILLAGER_SPAWN))) {
            missing.add(new PASpawn(null, VILLAGER_SPAWN + "1", null, null));
        }
        if (spawns.stream().noneMatch(spawn -> spawn.getName().toLowerCase(Locale.ROOT).startsWith(MOB_SPAWN))) {
            missing.add(new PASpawn(null, MOB_SPAWN + "1", null, null));
        }
        return missing;
    }

    /** Let admins set {@code villager*} and {@code mob*} spawns in addition to the FFA fight spawns. */
    @Override
    public boolean hasFfaSpawn(final String spawnName) {
        final String lower = spawnName.toLowerCase(Locale.ROOT);
        return super.hasFfaSpawn(spawnName)
                || lower.startsWith(VILLAGER_SPAWN)
                || lower.startsWith(MOB_SPAWN);
    }

    // ---- match lifecycle -------------------------------------------------------------------------

    @Override
    public void parseStart() {
        super.parseStart();

        this.currentWave = 0;
        this.spawnVillagers();
        this.registerListener();
        this.startWaveTimer();

        this.arena.broadcast(ChatColor.GOLD + "Defend the villagers! First wave in "
                + ChatColor.YELLOW + this.firstWaveDelaySeconds() + "s" + ChatColor.GOLD + ".");
    }

    private void spawnVillagers() {
        this.villagers.clear();
        this.villagersSpawned = false;

        final List<PASpawn> villagerSpawns = this.spawnsStartingWith(VILLAGER_SPAWN);
        if (villagerSpawns.isEmpty()) {
            PVPArena.getInstance().getLogger().warning("[VillageDefense] Arena '" + this.arena.getName()
                    + "' has no 'villager' spawns — nothing to defend! Set them with /pa "
                    + this.arena.getName() + " spawn set villager1");
            return;
        }

        final int perSpawn = this.villagersPerSpawn();
        final Villager.Profession profession = this.profession();
        for (final PASpawn spawn : villagerSpawns) {
            final Location location = spawn.getPALocation().toLocation();
            for (int i = 0; i < perSpawn; i++) {
                final Villager villager = location.getWorld().spawn(location, Villager.class);
                villager.setRemoveWhenFarAway(false);
                if (profession != null) {
                    villager.setProfession(profession);
                }
                // level >= 2 so the villager is past the "novice acquiring a profession" state, which
                // is what re-rolls the trade list out from under us
                villager.setVillagerLevel(2);
                // fresh recipe objects per villager: MerchantRecipe carries its own use counter, so
                // sharing one list would let a trade at one villager exhaust it at all the others
                final List<MerchantRecipe> recipes = this.buildTrades();
                if (!recipes.isEmpty()) {
                    villager.setRecipes(recipes);
                }
                this.villagers.add(villager.getUniqueId());
            }
        }
        this.villagersSpawned = !this.villagers.isEmpty();
    }

    private void startWaveTimer() {
        this.stopWaveTimer();
        this.waveTask = new BukkitRunnable() {
            @Override
            public void run() {
                GoalVillageDefense.this.spawnNextWave();
            }
        };
        this.waveTask.runTaskTimer(PVPArena.getInstance(),
                this.firstWaveDelaySeconds() * 20L,
                this.waveIntervalSeconds() * 20L);
    }

    private void stopWaveTimer() {
        if (this.waveTask != null) {
            try {
                this.waveTask.cancel();
            } catch (final IllegalStateException ignored) {
            }
            this.waveTask = null;
        }
    }

    private void spawnNextWave() {
        if (!this.arena.isFightInProgress() || this.arena.realEndRunner != null) {
            return;
        }
        final List<PASpawn> mobSpawns = this.spawnsStartingWith(MOB_SPAWN);
        if (mobSpawns.isEmpty()) {
            PVPArena.getInstance().getLogger().warning("[VillageDefense] Arena '" + this.arena.getName()
                    + "' has no 'mob' spawns — waves cannot spawn.");
            return;
        }

        this.waveMobs.removeIf(id -> {
            final Entity entity = Bukkit.getEntity(id);
            return entity == null || entity.isDead() || !entity.isValid();
        });

        this.currentWave++;
        final int fighting = this.countFightingPlayers();
        final int maxAlive = Math.max(1, this.yaml().getInt(CFG_MAX_MOBS_ALIVE, DEF_MAX_MOBS_ALIVE));
        int amount = Math.max(1, this.yaml().getInt(CFG_BASE_MOBS, DEF_BASE_MOBS))
                + Math.max(0, this.yaml().getInt(CFG_MOBS_PER_WAVE, DEF_MOBS_PER_WAVE)) * (this.currentWave - 1)
                + Math.max(0, this.yaml().getInt(CFG_MOBS_PER_PLAYER, DEF_MOBS_PER_PLAYER)) * fighting;
        amount = Math.min(amount, Math.max(0, maxAlive - this.waveMobs.size()));

        final EntityType type = this.mobType();
        final boolean fireResistant = this.yaml().getBoolean(CFG_FIRE_RESISTANT, true);
        for (int i = 0; i < amount; i++) {
            this.spawnWaveMob(mobSpawns.get(i % mobSpawns.size()), type, fireResistant);
        }

        if (this.yaml().getBoolean(CFG_ANNOUNCE_WAVE_START, true)) {
            this.arena.broadcast(ChatColor.RED + "Wave " + ChatColor.YELLOW + this.currentWave
                    + ChatColor.RED + " incoming! " + ChatColor.GRAY + "(" + amount + " mobs)");
        }
    }

    private void spawnWaveMob(final PASpawn spawn, final EntityType type, final boolean fireResistant) {
        final Location location = spawn.getPALocation().toLocation();
        final Entity entity = location.getWorld().spawnEntity(location, type);

        if (entity instanceof LivingEntity) {
            final LivingEntity living = (LivingEntity) entity;
            living.setRemoveWhenFarAway(false);
            if (fireResistant) {
                // vanilla zombies burn in daylight — keep test waves alive on day-time maps
                living.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            }
        }
        if (entity instanceof Creature) {
            final LivingEntity target = this.pickTarget();
            if (target != null) {
                ((Creature) entity).setTarget(target);
            }
        }
        this.waveMobs.add(entity.getUniqueId());
    }

    /** Prefer a random living villager (attack the village!), else a random fighting player. */
    private LivingEntity pickTarget() {
        final List<LivingEntity> aliveVillagers = this.villagers.stream()
                .map(Bukkit::getEntity)
                .filter(e -> e instanceof LivingEntity && !e.isDead() && e.isValid())
                .map(e -> (LivingEntity) e)
                .collect(Collectors.toList());
        if (!aliveVillagers.isEmpty()) {
            return aliveVillagers.get(ThreadLocalRandom.current().nextInt(aliveVillagers.size()));
        }
        final List<Player> fighting = this.arena.getFighters().stream()
                .filter(ap -> ap.getStatus() == PlayerStatus.FIGHT && ap.getPlayer() != null)
                .map(ArenaPlayer::getPlayer)
                .collect(Collectors.toList());
        if (fighting.isEmpty()) {
            return null;
        }
        return fighting.get(ThreadLocalRandom.current().nextInt(fighting.size()));
    }

    private int countFightingPlayers() {
        return (int) this.arena.getFighters().stream()
                .filter(ap -> ap.getStatus() == PlayerStatus.FIGHT)
                .count();
    }

    private List<PASpawn> spawnsStartingWith(final String prefix) {
        return this.arena.getSpawns().stream()
                .filter(spawn -> spawn.getName().toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(Comparator.comparing(PASpawn::getName))
                .collect(Collectors.toList());
    }

    // ---- villager & mob tracking -----------------------------------------------------------------

    private void registerListener() {
        if (!this.listening) {
            Bukkit.getPluginManager().registerEvents(this, PVPArena.getInstance());
            this.listening = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(final EntityDeathEvent event) {
        final UUID id = event.getEntity().getUniqueId();

        if (this.villagers.remove(id)) {
            if (!this.arena.isFightInProgress() || this.arena.realEndRunner != null) {
                return;
            }
            if (this.yaml().getBoolean(CFG_ANNOUNCE_VILLAGER_DEATH, true)) {
                this.arena.broadcast(ChatColor.RED + "A villager has died! " + ChatColor.YELLOW
                        + this.villagers.size() + ChatColor.RED + " remaining.");
            }
            if (this.villagers.isEmpty()) {
                WorkflowManager.handleEnd(this.arena, false);
            }
        } else if (this.waveMobs.remove(id)) {
            if (ThreadLocalRandom.current().nextDouble()
                    < this.yaml().getDouble(CFG_EMERALD_CHANCE, DEF_EMERALD_CHANCE)) {
                event.getDrops().add(new ItemStack(Material.EMERALD, this.emeraldDropAmount(this.currentWave)));
            }
            if (this.waveMobs.isEmpty() && this.arena.isFightInProgress()
                    && this.arena.realEndRunner == null
                    && this.yaml().getBoolean(CFG_ANNOUNCE_WAVE_CLEARED, true)
                    && this.currentWave > 0) {
                this.arena.broadcast(ChatColor.GREEN + "Wave " + ChatColor.YELLOW + this.currentWave
                        + ChatColor.GREEN + " cleared!");
            }
        }
    }

    /** True if this player is actively defending in <i>this</i> arena (alive and fighting). */
    private boolean isDefending(final Player player) {
        final ArenaPlayer arenaPlayer = ArenaPlayer.fromPlayer(player);
        return arenaPlayer != null
                && this.arena.equals(arenaPlayer.getArena())
                && arenaPlayer.getStatus() == PlayerStatus.FIGHT;
    }

    /**
     * Stop wave mobs from chasing dead players / spectators. Vanilla AI happily acquires any nearby
     * player, so filtering only in {@link #pickTarget()} isn't enough — this is the choke point every
     * retarget goes through.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobTarget(final EntityTargetLivingEntityEvent event) {
        if (!this.waveMobs.contains(event.getEntity().getUniqueId())
                || !(event.getTarget() instanceof Player)) {
            return;
        }
        if (!this.isDefending((Player) event.getTarget())) {
            event.setTarget(this.pickTarget());
        }
    }

    /** A target already locked in doesn't fire a new target event — repoint those mobs by hand. */
    private void retargetAwayFrom(final Player player) {
        for (final UUID id : this.waveMobs) {
            final Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Creature && player.equals(((Creature) entity).getTarget())) {
                ((Creature) entity).setTarget(this.pickTarget());
            }
        }
    }

    @Override
    public void commitPlayerDeath(final ArenaPlayer arenaPlayer, final boolean doesRespawn,
                                  final PADeathInfo deathInfo) {
        super.commitPlayerDeath(arenaPlayer, doesRespawn, deathInfo);
        if (arenaPlayer.getPlayer() != null) {
            this.retargetAwayFrom(arenaPlayer.getPlayer());
        }
    }

    @Override
    public void parseLeave(final ArenaPlayer arenaPlayer) {
        super.parseLeave(arenaPlayer);
        if (arenaPlayer.getPlayer() != null) {
            this.retargetAwayFrom(arenaPlayer.getPlayer());
        }
    }

    /** Defenders shouldn't be able to hurt their own villagers (configurable). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerDamage(final EntityDamageByEntityEvent event) {
        if (!this.villagers.contains(event.getEntity().getUniqueId())
                || !this.yaml().getBoolean(CFG_PROTECT_VILLAGERS, true)) {
            return;
        }
        if (ArenaPlayer.getLastDamagingPlayer(event) != null) {
            event.setCancelled(true);
        }
    }

    // ---- end condition: all villagers dead OR all defenders dead ----------------------------------

    @Override
    public boolean checkEnd() {
        if (this.villagersSpawned && this.villagers.isEmpty()) {
            return true;
        }
        return this.getActivePlayerLifeMap().isEmpty();
    }

    /**
     * Game over — <b>nobody wins</b>. Unlike the parent's commitEnd we never crown winners or fire
     * module win rewards; we just announce the result and schedule the arena reset.
     */
    @Override
    public void commitEnd(final boolean force) {
        if (this.endRunner != null || this.arena.realEndRunner != null) {
            return;
        }
        final PAGoalEndEvent gEvent = new PAGoalEndEvent(this.arena, this);
        Bukkit.getPluginManager().callEvent(gEvent);

        this.stopWaveTimer();

        final String reason = (this.villagersSpawned && this.villagers.isEmpty())
                ? "All villagers have been slain"
                : "All defenders have fallen";
        final String message = ChatColor.DARK_RED + "Game over! " + ChatColor.RED + reason
                + ChatColor.GRAY + " (wave " + Math.max(1, this.currentWave) + ")";

        ArenaModuleManager.announce(this.arena, message, "END");
        this.arena.broadcast(message);

        this.endRunner = new EndRunnable(this.arena, this.arena.getConfig().getInt(CFG.TIME_ENDCOUNTDOWN));
    }

    // these are only used by the parent's commitEnd, which is fully overridden above (no winners)
    @Override
    protected void setWinnerAndBroadcastEndMessages(final ArenaTeam teamToCheck) {
    }

    @Override
    protected ArenaPlayer getWinningPlayerIfNeeded(final ArenaTeam teamToCheck) {
        return null;
    }

    // ---- cleanup -----------------------------------------------------------------------------------

    @Override
    public void reset(final boolean force) {
        super.reset(force);
        this.stopWaveTimer();
        this.removeTrackedEntities();
        this.currentWave = 0;
        this.villagersSpawned = false;
        if (this.listening) {
            HandlerList.unregisterAll(this);
            this.listening = false;
        }
    }

    private void removeTrackedEntities() {
        final List<UUID> tracked = new ArrayList<>(this.villagers.size() + this.waveMobs.size());
        tracked.addAll(this.villagers);
        tracked.addAll(this.waveMobs);
        this.villagers.clear();
        this.waveMobs.clear();
        for (final UUID id : tracked) {
            final Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    @Override
    public void displayInfo(final org.bukkit.command.CommandSender sender) {
        sender.sendMessage("lives: " + this.getLivesConfigValue() + " / autostart: " + this.autostartSeconds() + "s");
        sender.sendMessage("waves: first " + this.firstWaveDelaySeconds() + "s, every "
                + this.waveIntervalSeconds() + "s, mob " + this.mobType());
        sender.sendMessage("villager spawns: " + this.spawnsStartingWith(VILLAGER_SPAWN).size()
                + " / mob spawns: " + this.spawnsStartingWith(MOB_SPAWN).size());
    }
}
