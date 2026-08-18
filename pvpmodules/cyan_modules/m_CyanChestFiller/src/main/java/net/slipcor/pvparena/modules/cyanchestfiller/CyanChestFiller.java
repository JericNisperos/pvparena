package net.slipcor.pvparena.modules.cyanchestfiller;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.classes.PABlockLocation;
import net.slipcor.pvparena.commands.AbstractArenaCommand;
import net.slipcor.pvparena.commands.CommandTree;
import net.slipcor.pvparena.core.Config;
import net.slipcor.pvparena.core.ItemStackUtils;
import net.slipcor.pvparena.core.Language;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.core.StringParser;
import net.slipcor.pvparena.core.Utils;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.loadables.ArenaRegionShape;
import net.slipcor.pvparena.managers.PermissionManager;
import net.slipcor.pvparena.regions.ArenaRegion;
import net.slipcor.pvparena.regions.RegionType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static net.slipcor.pvparena.config.Debugger.debug;

public class CyanChestFiller extends ArenaModule {

    // Config lives entirely on the module side: raw paths in the arena config, no core CFG enum entries.
    private static final String ROOT = "modules.cyanchestfiller.";
    private static final String CFG_SOURCELOCATIONS = ROOT + "sourceLocations";
    private static final String CFG_CONTAINERLIST = ROOT + "containerList";
    private static final String CFG_ITEMS = ROOT + "items";
    private static final String CFG_CLEAR = ROOT + "clear";
    private static final String CFG_AUTODETECT = ROOT + "autoDetect";
    private static final String CFG_RANDOMSLOTS = ROOT + "randomSlots";
    private static final String CFG_MAXITEMS = ROOT + "maxItems";
    private static final String CFG_MINITEMS = ROOT + "minItems";
    private static final String CFG_CHUNKSPERTICK = ROOT + "chunksPerTick";
    private static final String CFG_REFILLSECONDS = ROOT + "refillSeconds";
    private static final String CFG_REFILLPERTICK = ROOT + "refillPerTick";
    private static final String CFG_REFILLMESSAGE = ROOT + "refillMessage";

    private static final boolean DEF_CLEAR = false;
    private static final boolean DEF_AUTODETECT = true;
    private static final boolean DEF_RANDOMSLOTS = true;
    private static final int DEF_MAXITEMS = 5;
    private static final int DEF_MINITEMS = 0;
    private static final int DEF_CHUNKSPERTICK = 4;
    private static final int DEF_REFILLSECONDS = 0;
    private static final int DEF_REFILLPERTICK = 8;
    private static final String DEF_REFILLMESSAGE = "&aThe chests have been refilled!";

    // Hard ceilings so a hand-edited config can't freeze the server or overflow an int.
    // A double chest is 54 slots; 256 is generous headroom, not a target.
    private static final int MAX_FILL_COUNT = 256;
    private static final int MAX_CHUNKS_PER_TICK = 64;
    private static final int MAX_REFILL_PER_TICK = 128;

    private boolean clear;
    private boolean autoDetect;
    private boolean randomSlots;
    private int minItems;
    private int maxItems;
    private int chunksPerTick;
    private int refillSeconds;
    private int refillPerTick;
    private String refillMessage;
    private BukkitTask scanTask;
    private BukkitTask refillTask;
    private BukkitTask fillTask;

    public CyanChestFiller() {
        super("CyanChestFiller");
    }

    /** Raw read access to this arena's config. */
    private YamlConfiguration yaml() {
        return this.arena.getConfig().getYamlConfiguration();
    }

    /** Write a raw path and persist, keeping the Config value cache in sync. */
    private void setAndSave(final String path, final Object value) {
        this.arena.getConfig().setManually(path, value);
        this.arena.getConfig().save();
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public boolean checkCommand(final String s) {
        return "!ccf".equalsIgnoreCase(s) || s.equalsIgnoreCase("cyanchestfiller");
    }

    @Override
    public List<String> getMain() {
        return Collections.singletonList("cyanchestfiller");
    }

    @Override
    public List<String> getShort() {
        return Collections.singletonList("!ccf");
    }

    @Override
    public CommandTree<String> getSubs(final Arena arena) {
        final CommandTree<String> result = new CommandTree<>(null);
        result.define(new String[]{"sourcelocation"});
        result.define(new String[]{"sourcelocation", "none"});
        result.define(new String[]{"clear"});
        result.define(new String[]{"addcontainer"});
        result.define(new String[]{"detect"});
        return result;
    }

    @Override
    public void commitCommand(final CommandSender sender, final String[] args) {
        if (!PermissionManager.hasAdminPerm(sender) && !PermissionManager.hasBuilderPerm(sender, this.arena)) {
            this.arena.msg(sender, MSG.ERROR_NOPERM, Language.parse(MSG.ERROR_NOPERM_X_ADMIN));
            return;
        }

        if (!AbstractArenaCommand.argCountValid(sender, this.arena, args, new Integer[]{2, 3})) {
            return;
        }

        if ("sourcelocation".equalsIgnoreCase(args[1])) {
            if (args.length == 3) {
                if ("none".equalsIgnoreCase(args[2])) {
                    this.setAndSave(CFG_SOURCELOCATIONS, new ArrayList<String>());
                    this.arena.msg(sender, Language.parse(MSG.MODULE_CHESTFILLER_SOURCECHEST_REMOVED));
                } else {
                    this.arena.msg(sender, MSG.ERROR_ARGUMENT, args[2]);
                }
                return;
            }

            this.withTargetContainer(sender, loc -> {
                final List<String> sources = this.yaml().getStringList(CFG_SOURCELOCATIONS);
                if (!sources.contains(loc.toString())) {
                    sources.add(loc.toString());
                    this.setAndSave(CFG_SOURCELOCATIONS, sources);
                }
                this.arena.msg(sender, MSG.MODULE_CHESTFILLER_SOURCECHEST, loc.toString());
            });

        } else if ("addcontainer".equalsIgnoreCase(args[1])) {
            this.withTargetContainer(sender, loc -> {
                final List<String> chestsToFill = this.yaml().getStringList(CFG_CONTAINERLIST);
                if (!chestsToFill.contains(loc.toString())) {
                    chestsToFill.add(loc.toString());
                    this.setAndSave(CFG_CONTAINERLIST, chestsToFill);
                }
                this.arena.msg(sender, MSG.MODULE_CHESTFILLER_ADDEDTOLIST, loc.toString());
            });

        } else if ("detect".equalsIgnoreCase(args[1])) {
            this.startBatchedScan(this.yaml().getStringList(CFG_CONTAINERLIST), all -> {
                this.setAndSave(CFG_CONTAINERLIST, all);
                this.arena.msg(sender, MSG.MODULE_CHESTFILLER_ADDEDTOLIST, String.valueOf(all.size()));
            });

        } else if ("clear".equalsIgnoreCase(args[1])) {
            this.arena.getConfig().setManually(CFG_CONTAINERLIST, new ArrayList<String>());
            // re-arm auto-detection, otherwise clearing leaves nothing to fill ever again
            this.setAndSave(CFG_AUTODETECT, true);
            this.autoDetect = true;
            sender.sendMessage(Language.parse(MSG.MODULE_CHESTFILLER_CLEAR));

        } else {
            this.arena.msg(sender, MSG.ERROR_ARGUMENT, args[1]);
        }
    }

    private void withTargetContainer(final CommandSender sender, final Consumer<PABlockLocation> action) {
        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return;
        }
        final Block b = ((Player) sender).getTargetBlock(null, 10);
        if (b.getState() instanceof Container) {
            action.accept(new PABlockLocation(b.getLocation()));
        } else {
            this.arena.msg(sender, MSG.ERROR_NO_CONTAINER);
        }
    }

    @Override
    public void configParse(YamlConfiguration yamlConfig) {
        this.writeMissingDefaults();

        final YamlConfiguration yaml = this.yaml();
        this.clear = yaml.getBoolean(CFG_CLEAR, DEF_CLEAR);
        this.autoDetect = yaml.getBoolean(CFG_AUTODETECT, DEF_AUTODETECT);
        this.randomSlots = yaml.getBoolean(CFG_RANDOMSLOTS, DEF_RANDOMSLOTS);
        // Clamp to [0, MAX_FILL_COUNT] and keep min <= max: prevents a runaway fill loop and the
        // int overflow in fill()'s r.nextInt(max - min + 1) when a config is edited to extreme values.
        this.maxItems = Math.max(0, Math.min(yaml.getInt(CFG_MAXITEMS, DEF_MAXITEMS), MAX_FILL_COUNT));
        this.minItems = Math.max(0, Math.min(yaml.getInt(CFG_MINITEMS, DEF_MINITEMS), this.maxItems));
        // guard both ends: 0/negative stalls the scan forever, a huge value re-freezes the server
        this.chunksPerTick = Math.max(1, Math.min(yaml.getInt(CFG_CHUNKSPERTICK, DEF_CHUNKSPERTICK), MAX_CHUNKS_PER_TICK));
        this.refillSeconds = yaml.getInt(CFG_REFILLSECONDS, DEF_REFILLSECONDS);
        this.refillPerTick = Math.max(1, Math.min(yaml.getInt(CFG_REFILLPERTICK, DEF_REFILLPERTICK), MAX_REFILL_PER_TICK));
        this.refillMessage = yaml.getString(CFG_REFILLMESSAGE, DEF_REFILLMESSAGE);
    }

    /**
     * Core knows nothing about these keys, so seed them into the arena config on first load —
     * otherwise admins have no way to discover what's tunable.
     */
    private void writeMissingDefaults() {
        final Config config = this.arena.getConfig();
        final YamlConfiguration yaml = this.yaml();
        boolean dirty = false;

        if (!yaml.contains(CFG_CLEAR)) {
            config.setManually(CFG_CLEAR, DEF_CLEAR);
            dirty = true;
        }
        if (!yaml.contains(CFG_AUTODETECT)) {
            config.setManually(CFG_AUTODETECT, DEF_AUTODETECT);
            dirty = true;
        }
        if (!yaml.contains(CFG_RANDOMSLOTS)) {
            config.setManually(CFG_RANDOMSLOTS, DEF_RANDOMSLOTS);
            dirty = true;
        }
        if (!yaml.contains(CFG_MAXITEMS)) {
            config.setManually(CFG_MAXITEMS, DEF_MAXITEMS);
            dirty = true;
        }
        if (!yaml.contains(CFG_MINITEMS)) {
            config.setManually(CFG_MINITEMS, DEF_MINITEMS);
            dirty = true;
        }
        if (!yaml.contains(CFG_CHUNKSPERTICK)) {
            config.setManually(CFG_CHUNKSPERTICK, DEF_CHUNKSPERTICK);
            dirty = true;
        }
        if (!yaml.contains(CFG_REFILLSECONDS)) {
            config.setManually(CFG_REFILLSECONDS, DEF_REFILLSECONDS);
            dirty = true;
        }
        if (!yaml.contains(CFG_REFILLPERTICK)) {
            config.setManually(CFG_REFILLPERTICK, DEF_REFILLPERTICK);
            dirty = true;
        }
        if (!yaml.contains(CFG_REFILLMESSAGE)) {
            config.setManually(CFG_REFILLMESSAGE, DEF_REFILLMESSAGE);
            dirty = true;
        }
        if (!yaml.contains(CFG_SOURCELOCATIONS)) {
            config.setManually(CFG_SOURCELOCATIONS, new ArrayList<String>());
            dirty = true;
        }
        if (!yaml.contains(CFG_CONTAINERLIST)) {
            config.setManually(CFG_CONTAINERLIST, new ArrayList<String>());
            dirty = true;
        }
        if (!yaml.contains(CFG_ITEMS)) {
            // Must be the serialized map form: getItemStacksFromConfig casts each entry to Map.
            // Writing raw ItemStacks would blow up on the same run, before any save/reload round-trip.
            config.setManually(CFG_ITEMS, Utils.getItemStacksFromMaterials(Material.STONE));
            dirty = true;
        }

        if (dirty) {
            config.save();
        }
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        final YamlConfiguration yaml = this.yaml();
        final List<String> sources = yaml.getStringList(CFG_SOURCELOCATIONS);
        sender.sendMessage("items: " + (sources.isEmpty() ?
                StringParser.getItems(this.getConfiguredItems()) :
                String.join(", ", sources)));
        final int refill = yaml.getInt(CFG_REFILLSECONDS, DEF_REFILLSECONDS);
        sender.sendMessage(String.format("max: %d | min: %d | clear: %s | randomSlots: %s | autoDetect: %s | containers: %d | refill: %s",
                yaml.getInt(CFG_MAXITEMS, DEF_MAXITEMS),
                yaml.getInt(CFG_MINITEMS, DEF_MINITEMS),
                yaml.getBoolean(CFG_CLEAR, DEF_CLEAR),
                yaml.getBoolean(CFG_RANDOMSLOTS, DEF_RANDOMSLOTS),
                yaml.getBoolean(CFG_AUTODETECT, DEF_AUTODETECT),
                yaml.getStringList(CFG_CONTAINERLIST).size(),
                refill > 0 ? refill + "s" : "off"));
    }

    @Override
    public boolean needsBattleRegion() {
        return true;
    }

    @Override
    public void parseStart() {
        // Defensive: never leave a task from a previous match running.
        this.cancelScan();
        this.cancelRefill();
        this.cancelFill();

        final ItemStack[] fillingContent = this.getFillingContent();
        if (fillingContent == null || fillingContent.length == 0) {
            // Empty source chest or empty items list: nothing to hand out, and filling would divide by zero.
            debug("[CyanChestFiller] no filling content configured, skipping fill");
            return;
        }

        final List<String> saved = this.yaml().getStringList(CFG_CONTAINERLIST);

        if (!this.autoDetect) {
            // Normal path: list already known, nothing to scan.
            // Delay a tick to let blockRestore save container contents first.
            this.scheduleFill(saved, fillingContent, 1);
            return;
        }

        // One-shot detection, spread over ticks so a cold region doesn't stall the server on start.
        // The battle region doesn't move, so this runs once and then switches itself off.
        this.startBatchedScan(saved, detected -> {
            this.arena.getConfig().setManually(CFG_CONTAINERLIST, detected);
            this.setAndSave(CFG_AUTODETECT, false);
            this.autoDetect = false;
            debug("[CyanChestFiller] auto-detected {} containers, autoDetect disabled", detected.size());
            this.scheduleFill(detected, fillingContent, 0);
        });
    }

    @Override
    public void reset(final boolean force) {
        this.cancelScan();
        this.cancelRefill();
        this.cancelFill();
    }

    private void cancelScan() {
        if (this.scanTask != null) {
            this.scanTask.cancel();
            this.scanTask = null;
        }
    }

    private void cancelRefill() {
        if (this.refillTask != null) {
            this.refillTask.cancel();
            this.refillTask = null;
        }
    }

    private void cancelFill() {
        if (this.fillTask != null) {
            this.fillTask.cancel();
            this.fillTask = null;
        }
    }

    private void scheduleFill(final List<String> targets, final ItemStack[] fillingContent, final long delayTicks) {
        // Never fill a source container: it would feed its own random items back into the pool next game.
        final List<String> sources = this.yaml().getStringList(CFG_SOURCELOCATIONS);
        final List<String> toFill = targets.stream()
                .filter(loc -> !sources.contains(loc))
                .collect(Collectors.toList());

        // No message on the initial fill; refills announce themselves.
        Bukkit.getScheduler().runTaskLater(PVPArena.getInstance(),
                () -> this.startBatchedFill(toFill, fillingContent, null), delayTicks);

        this.startRefill(toFill);
    }

    /**
     * Fills the containers {@code refillPerTick} at a time across ticks so a large arena never
     * stalls in one burst, then broadcasts {@code messageOnDone} (if set) once every container is done.
     *
     * <p>Only one batched fill runs at a time: if a previous one is still draining (short
     * {@code refillSeconds} + many containers), this cycle is skipped rather than stacking work.</p>
     */
    private void startBatchedFill(final List<String> toFill, final ItemStack[] content, final String messageOnDone) {
        if (this.fillTask != null) {
            debug("[CyanChestFiller] previous fill still running, skipping this cycle");
            return;
        }

        final Deque<String> queue = new ArrayDeque<>(toFill);
        if (queue.isEmpty()) {
            this.broadcastRefill(messageOnDone);
            return;
        }

        debug("[CyanChestFiller] filling {} containers, {}/tick", queue.size(), this.refillPerTick);
        this.fillTask = Bukkit.getScheduler().runTaskTimer(PVPArena.getInstance(), new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < CyanChestFiller.this.refillPerTick && !queue.isEmpty(); i++) {
                    final String locString = queue.poll();
                    try {
                        // Isolate per container: one malformed config entry or a vanished container
                        // must not abort the rest of the fill.
                        CyanChestFiller.this.fill(new PABlockLocation(locString), content);
                    } catch (final Exception e) {
                        debug("[CyanChestFiller] failed to fill container '{}': {}", locString, e.getMessage());
                    }
                }

                if (queue.isEmpty()) {
                    CyanChestFiller.this.cancelFill();
                    CyanChestFiller.this.broadcastRefill(messageOnDone);
                }
            }
        }, 0L, 1L);
    }

    private void broadcastRefill(final String messageOnDone) {
        if (messageOnDone != null && !messageOnDone.trim().isEmpty()) {
            this.arena.broadcast(messageOnDone);
        }
    }

    /**
     * Refills the containers every {@code refillSeconds} while the match runs, re-reading the source
     * content each cycle so edits to a source chest take effect. Disabled when the value is 0 or less.
     */
    private void startRefill(final List<String> toFill) {
        this.cancelRefill();
        if (this.refillSeconds <= 0) {
            return;
        }

        final long period = this.refillSeconds * 20L;
        this.refillTask = Bukkit.getScheduler().runTaskTimer(PVPArena.getInstance(), () -> {
            // Defensive: reset() normally stops this, but self-cancel if the match ended without it.
            if (!this.arena.isFightInProgress()) {
                this.cancelRefill();
                return;
            }

            final ItemStack[] content = this.getFillingContent();
            if (content == null || content.length == 0) {
                debug("[CyanChestFiller] refill skipped: no filling content");
                return;
            }

            this.startBatchedFill(toFill, content, this.refillMessage);
        }, period, period);
    }

    /**
     * Contents of every configured source container, merged. Falls back to the items config
     * when no source location is set.
     */
    private ItemStack[] getFillingContent() {
        final List<String> sources = this.yaml().getStringList(CFG_SOURCELOCATIONS);

        if (sources.isEmpty()) {
            return this.getConfiguredItems();
        }

        final List<ItemStack> contents = new ArrayList<>();
        final Set<Location> seenInventories = new LinkedHashSet<>();
        for (String source : sources) {
            final Inventory inv = this.getSourceInventory(source);
            // Both halves of a double chest share one inventory: count it once, or its items get double weight
            if (inv == null || (inv.getLocation() != null && !seenInventories.add(inv.getLocation()))) {
                continue;
            }
            Arrays.stream(inv.getContents())
                    .filter(itemStack -> itemStack != null && !itemStack.getType().isAir())
                    .map(ItemStack::clone)
                    .forEach(contents::add);
        }
        return contents.toArray(new ItemStack[0]);
    }

    /**
     * The configured items list. Core's {@code Config#getItems} is CFG-only, so read the raw path
     * and hand it to the same deserializer core uses.
     */
    private ItemStack[] getConfiguredItems() {
        try {
            final List<?> raw = this.yaml().getList(CFG_ITEMS);
            if (raw == null || raw.isEmpty()) {
                return new ItemStack[0];
            }
            return ItemStackUtils.getItemStacksFromConfig(raw);
        } catch (final Exception e) {
            e.printStackTrace();
            return new ItemStack[0];
        }
    }

    /** Inventory of a configured source location, or null if it isn't a container (anymore). */
    private Inventory getSourceInventory(final String source) {
        try {
            final BlockState state = new PABlockLocation(source).toLocation().getBlock().getState();
            if (state instanceof Container) {
                return ((Container) state).getInventory();
            }
            debug("[CyanChestFiller] source location is no container anymore: {}", source);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /** Same container kinds the original ChestFiller picks up: chests, trapped chests, barrels, shulker boxes. */
    private static boolean isDefaultKindOfChest(final BlockState state) {
        if (state.getBlockData() instanceof org.bukkit.block.data.type.Chest) {
            // Skipping second part of double chests
            return ((org.bukkit.block.data.type.Chest) state.getBlockData()).getType() != org.bukkit.block.data.type.Chest.Type.RIGHT;
        }
        return state instanceof ShulkerBox || state instanceof Barrel;
    }

    /**
     * Scans the BATTLE regions for containers a few chunks per tick, then hands the merged list to
     * {@code onDone} on the main thread.
     *
     * <p>Walks the chunks' tile entities rather than every block in the region. Containers are
     * always tile entities, and a chunk holds a handful of those versus ~100k blocks, so this
     * avoids materialising the whole region and calling getState() on each block of it.</p>
     *
     * <p>Spigot 1.18 has no async chunk load, and Bukkit world access is main-thread-only, so an
     * unloaded chunk costs a blocking disk read. Budgeting them per tick turns one long freeze into
     * a short load phase.</p>
     */
    private void startBatchedScan(final List<String> existing, final Consumer<List<String>> onDone) {
        final Set<String> result = new LinkedHashSet<>(existing);
        final Deque<PendingChunk> queue = this.buildScanQueue();
        final int total = queue.size();
        final long startedAt = System.currentTimeMillis();

        if (queue.isEmpty()) {
            onDone.accept(new ArrayList<>(result));
            return;
        }

        this.cancelScan();
        this.scanTask = Bukkit.getScheduler().runTaskTimer(PVPArena.getInstance(), new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < CyanChestFiller.this.chunksPerTick && !queue.isEmpty(); i++) {
                    final PendingChunk pending = queue.poll();
                    CyanChestFiller.this.collectContainers(
                            pending.world.getChunkAt(pending.x, pending.z), pending.shape, result);
                }

                if (queue.isEmpty()) {
                    CyanChestFiller.this.cancelScan();
                    debug("[CyanChestFiller] scanned {} chunks in {}ms, {} containers",
                            total, System.currentTimeMillis() - startedAt, result.size());
                    onDone.accept(new ArrayList<>(result));
                }
            }
        }, 0L, 1L);
    }

    /** Every generated chunk the BATTLE regions touch, paired with the shape that claimed it. */
    private Deque<PendingChunk> buildScanQueue() {
        final Deque<PendingChunk> queue = new ArrayDeque<>();

        for (ArenaRegion battleRegion : this.arena.getRegionsByType(RegionType.BATTLE)) {
            final ArenaRegionShape shape = battleRegion.getShape();
            final PABlockLocation min = shape.getMinimumLocation();
            final PABlockLocation max = shape.getMaximumLocation();
            final World world = Bukkit.getWorld(min.getWorldName());
            if (world == null) {
                debug("[CyanChestFiller] world not loaded, skipping region: {}", min.getWorldName());
                continue;
            }

            for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
                for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                    // ponytail: skips chunks that aren't generated yet; a built arena is always generated,
                    // and this keeps the scan from generating fresh terrain at the region edges.
                    if (world.isChunkGenerated(cx, cz)) {
                        queue.add(new PendingChunk(world, cx, cz, shape));
                    }
                }
            }
        }
        return queue;
    }

    private static final class PendingChunk {
        private final World world;
        private final int x;
        private final int z;
        private final ArenaRegionShape shape;

        private PendingChunk(final World world, final int x, final int z, final ArenaRegionShape shape) {
            this.world = world;
            this.x = x;
            this.z = z;
            this.shape = shape;
        }
    }

    private void collectContainers(final Chunk chunk, final ArenaRegionShape shape, final Set<String> result) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Container && isDefaultKindOfChest(state)) {
                final PABlockLocation loc = new PABlockLocation(state.getLocation());
                if (shape.contains(loc)) {
                    result.add(loc.toString());
                }
            }
        }
    }

    private void fill(PABlockLocation loc, ItemStack[] fillingContent) {
        BlockState blockState = loc.toLocation().getBlock().getState();
        if (!(blockState instanceof Container)) {
            return;
        }
        final Inventory inv = ((Container) blockState).getInventory();
        if (this.clear) {
            inv.clear();
        }

        final Random r = new Random();
        int bound = Math.max(this.maxItems - this.minItems, 0);

        // if min == max or min > max, use max
        int count = (bound == 0) ? this.maxItems : r.nextInt(bound + 1) + this.minItems;

        if (this.randomSlots) {
            // Spread over random free slots instead of packing them from the first slot on.
            final List<Integer> freeSlots = new ArrayList<>();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                final ItemStack current = inv.getItem(slot);
                if (current == null || current.getType().isAir()) {
                    freeSlots.add(slot);
                }
            }
            Collections.shuffle(freeSlots, r);
            // no free slot left = nothing to place, addItem would silently drop it anyway
            count = Math.min(count, freeSlots.size());

            for (int i = 0; i < count; i++) {
                inv.setItem(freeSlots.get(i), fillingContent[r.nextInt(fillingContent.length)].clone());
            }
        } else {
            for (int i = 0; i < count; i++) {
                inv.addItem(fillingContent[r.nextInt(fillingContent.length)].clone());
            }
        }
    }
}
