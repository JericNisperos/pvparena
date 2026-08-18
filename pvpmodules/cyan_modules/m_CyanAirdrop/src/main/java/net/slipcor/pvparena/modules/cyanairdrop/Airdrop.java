package net.slipcor.pvparena.modules.cyanairdrop;

import net.slipcor.pvparena.PVPArena;
import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.classes.PABlockLocation;
import net.slipcor.pvparena.commands.AbstractArenaCommand;
import net.slipcor.pvparena.commands.CommandTree;
import net.slipcor.pvparena.core.Config;
import net.slipcor.pvparena.core.ItemStackUtils;
import net.slipcor.pvparena.core.Language;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.core.Utils;
import net.slipcor.pvparena.loadables.ArenaModule;
import net.slipcor.pvparena.managers.PermissionManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <pre>Airdrop — supply drops at fixed coordinates, at fixed times, announced in advance.</pre>
 *
 * <p>At configured points into a match ("10 minutes in") a set of real items spawns at an
 * admin-marked spot in the arena. Countdown warnings go out beforehand, so both teams know when and
 * where to be. Players pick the items up into their inventory and use them normally.</p>
 *
 * <p>Not a powerup: {@code CyanPowerups} cancels the pickup and applies a potion effect instead —
 * here the item <b>is</b> the reward, so the pickup is left alone.</p>
 *
 * <p>Everything lives per-arena under {@code modules.cyanairdrop.*} (defaults are written on first
 * load). Drop points are captured in-game with {@code /pa &lt;arena&gt; !cad ...} rather than typed
 * by hand — a serialized ItemStack is not something anyone should be editing in YAML:</p>
 * <pre>
 * modules:
 *   cyanairdrop:
 *     announce: true
 *     announceSeconds:                # "&lt;seconds before the drop&gt;:&lt;message&gt;"
 *       - "60:&amp;fThe &amp;e[drop] &amp;fwill drop at &amp;e[coords] &amp;fin &amp;e60 seconds&amp;f!"
 *       - "10:&amp;fThe &amp;e[drop] &amp;fwill drop at &amp;e[coords] &amp;fin &amp;e10 seconds&amp;f!"
 *     announceDrop: "&amp;fThe &amp;e[drop] &amp;fhas dropped at &amp;e[coords]&amp;f!"
 *     announcePickup: "&amp;fThe &amp;e[drop] &amp;fhas been picked up by &amp;a[playername]&amp;f!"
 *     drops:
 *       supply1:
 *         atSeconds: 600              # 10 minutes into the match
 *         location: world,100,64,200
 *         name: "&amp;6Supply Drop"
 *         items:                      # serialized ItemStacks — use '!cad item'
 *           - ==: org.bukkit.inventory.ItemStack
 *             v: 3337
 *             type: DIAMOND_SWORD
 * </pre>
 *
 * <h2>Commands</h2>
 * <ul>
 *   <li>{@code /pa <arena> !cad set <name> <seconds>} — the block you're standing on becomes the
 *       drop point for {@code <name>}, at {@code <seconds>} into the match.</li>
 *   <li>{@code /pa <arena> !cad item <name>} — append the item in your hand to that drop.</li>
 *   <li>{@code /pa <arena> !cad remove <name>} — delete the drop.</li>
 * </ul>
 */
public class Airdrop extends ArenaModule {

    static final String NAME = "Airdrop";

    private static final String ROOT = "modules.cyanairdrop.";
    private static final String CFG_ANNOUNCE = ROOT + "announce";
    private static final String CFG_ANNOUNCE_SECONDS = ROOT + "announceSeconds";
    private static final String CFG_DROPS = ROOT + "drops";

    private static final String CFG_ANNOUNCE_DROP = ROOT + "announceDrop";
    private static final String CFG_ANNOUNCE_PICKUP = ROOT + "announcePickup";

    private static final List<String> DEF_ANNOUNCE_SECONDS = Arrays.asList(
            "60:&fThe &e[drop] &fwill drop at &e[coords] &fin &e60 seconds&f!",
            "30:&fThe &e[drop] &fwill drop at &e[coords] &fin &e30 seconds&f! Brace yourselves!",
            "10:&fThe &e[drop] &fwill drop at &e[coords] &fin &e10 seconds&f!");
    private static final String DEF_ANNOUNCE_DROP = "&fThe &e[drop] &fhas dropped at &e[coords]&f!";
    private static final String DEF_ANNOUNCE_PICKUP = "&fThe &e[drop] &fhas been picked up by &a[playername]&f!";
    private static final String DEF_LABEL = "&6Supply Drop";

    /** Fallback for a bare {@code "60"} entry — the pre-message config format. */
    private static final String DEF_WARNING = "&fThe &e[drop] &fdrops in &e[seconds] &fat &e[coords]&f!";

    /** One configured drop. {@code items} is never empty — empty drops are skipped at parse time. */
    private record Drop(String key, String label, int atSeconds, PABlockLocation location, ItemStack[] items) {
    }

    /** A countdown warning: how long before the drop, and what to say. */
    private record Warning(int leadSeconds, String message) {
    }

    private final List<Drop> drops = new ArrayList<>();
    /** Item entities we spawned and nobody has picked up yet. */
    private final List<Item> liveItems = new ArrayList<>();

    private List<Warning> warnings = new ArrayList<>();
    private String announceDrop = DEF_ANNOUNCE_DROP;
    private String announcePickup = DEF_ANNOUNCE_PICKUP;
    private boolean announce = true;
    private BukkitTask task;

    public Airdrop() {
        super(NAME);
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    private YamlConfiguration yaml() {
        return this.arena.getConfig().getYamlConfiguration();
    }

    // ---- config ---------------------------------------------------------------------------------

    @Override
    public void configParse(final YamlConfiguration config) {
        final Config cfg = this.arena.getConfig();
        boolean dirty = false;
        if (!config.contains(CFG_ANNOUNCE)) {
            cfg.setManually(CFG_ANNOUNCE, true);
            dirty = true;
        }
        if (!config.contains(CFG_ANNOUNCE_SECONDS)) {
            cfg.setManually(CFG_ANNOUNCE_SECONDS, DEF_ANNOUNCE_SECONDS);
            dirty = true;
        }
        if (!config.contains(CFG_ANNOUNCE_DROP)) {
            cfg.setManually(CFG_ANNOUNCE_DROP, DEF_ANNOUNCE_DROP);
            dirty = true;
        }
        if (!config.contains(CFG_ANNOUNCE_PICKUP)) {
            cfg.setManually(CFG_ANNOUNCE_PICKUP, DEF_ANNOUNCE_PICKUP);
            dirty = true;
        }
        if (dirty) {
            cfg.save();
        }

        this.announce = config.getBoolean(CFG_ANNOUNCE, true);
        this.announceDrop = config.getString(CFG_ANNOUNCE_DROP, DEF_ANNOUNCE_DROP);
        this.announcePickup = config.getString(CFG_ANNOUNCE_PICKUP, DEF_ANNOUNCE_PICKUP);
        this.warnings = this.parseWarnings(config.getList(CFG_ANNOUNCE_SECONDS));
        this.loadDrops();
    }

    /**
     * {@code "seconds:message"} per entry. A bare {@code "60"} (the format before messages were
     * configurable) still works and gets {@link #DEF_WARNING}, so existing arena configs keep running.
     */
    private List<Warning> parseWarnings(final List<?> raw) {
        final List<Warning> parsed = new ArrayList<>();
        if (raw == null) {
            return parsed;
        }
        for (final Object element : raw) {
            if (!(element instanceof String) && !(element instanceof Number)) {
                // YAML reads "10:&fReady: go!" as a mapping, not a string, because of the colon+space.
                // Without this the entry would just silently disappear.
                log("ignoring announceSeconds entry " + element + " in arena '" + this.arena.getName()
                        + "' — a message containing \": \" must be quoted: - \"10:&fReady: go!\"");
                continue;
            }
            final String entry = String.valueOf(element);
            final String[] parts = entry.split(":", 2);
            final int seconds;
            try {
                seconds = Integer.parseInt(parts[0].trim());
            } catch (final NumberFormatException e) {
                log("ignoring announceSeconds entry '" + entry + "' in arena '" + this.arena.getName()
                        + "' — expected \"<seconds>:<message>\"");
                continue;
            }
            if (seconds <= 0) {
                log("ignoring announceSeconds entry '" + entry + "' — the lead time must be positive");
                continue;
            }
            final String message = parts.length > 1 && !parts[1].trim().isEmpty() ? parts[1] : DEF_WARNING;
            parsed.add(new Warning(seconds, message));
        }
        return parsed;
    }

    /** Re-read every configured drop. Also called after a command edits one, so no reload is needed. */
    private void loadDrops() {
        this.drops.clear();
        final ConfigurationSection section = this.yaml().getConfigurationSection(CFG_DROPS);
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(false)) {
            final Drop drop = this.parseDrop(section, key);
            if (drop != null) {
                this.drops.add(drop);
            }
        }
    }

    /** One drop, or {@code null} (with a log line) if it is unusable. */
    private Drop parseDrop(final ConfigurationSection section, final String key) {
        final int atSeconds = section.getInt(key + ".atSeconds", -1);
        final String rawLocation = section.getString(key + ".location");
        if (atSeconds < 0 || rawLocation == null) {
            log("drop '" + key + "' in arena '" + this.arena.getName() + "' has no time or location — ignored");
            return null;
        }

        final PABlockLocation location;
        try {
            location = new PABlockLocation(rawLocation);
        } catch (final RuntimeException e) {
            // A hand-edited or truncated location string; one bad drop must not kill the rest.
            log("drop '" + key + "' has an unreadable location '" + rawLocation + "' — ignored");
            return null;
        }

        final ItemStack[] items = readItems(section.getList(key + ".items"));
        if (items.length == 0) {
            log("drop '" + key + "' in arena '" + this.arena.getName()
                    + "' has no items — add some with '/pa " + this.arena.getName() + " !cad item " + key + "'");
            return null;
        }
        final String label = section.getString(key + ".name", DEF_LABEL);
        return new Drop(key, ChatColor.translateAlternateColorCodes('&', label), atSeconds, location, items);
    }

    /** Core's {@code Config#getItems} is CFG-only, so hand the raw list to the same deserializer. */
    private static ItemStack[] readItems(final List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ItemStack[0];
        }
        try {
            return ItemStackUtils.getItemStacksFromConfig(raw);
        } catch (final Exception e) {
            log("could not read a drop's items: " + e.getMessage());
            return new ItemStack[0];
        }
    }

    // ---- commands -------------------------------------------------------------------------------

    @Override
    public boolean checkCommand(final String s) {
        return "!cad".equalsIgnoreCase(s) || "cyanairdrop".equalsIgnoreCase(s);
    }

    @Override
    public List<String> getMain() {
        return Collections.singletonList("cyanairdrop");
    }

    @Override
    public List<String> getShort() {
        return Collections.singletonList("!cad");
    }

    @Override
    public CommandTree<String> getSubs(final Arena arena) {
        final CommandTree<String> result = new CommandTree<>(null);
        result.define(new String[]{"set"});
        result.define(new String[]{"item"});
        result.define(new String[]{"remove"});
        return result;
    }

    @Override
    public void commitCommand(final CommandSender sender, final String[] args) {
        if (!PermissionManager.hasAdminPerm(sender) && !PermissionManager.hasBuilderPerm(sender, this.arena)) {
            this.arena.msg(sender, MSG.ERROR_NOPERM, Language.parse(MSG.ERROR_NOPERM_X_ADMIN));
            return;
        }
        if (!AbstractArenaCommand.argCountValid(sender, this.arena, args, new Integer[]{3, 4})) {
            return;
        }

        final String sub = args[1].toLowerCase(Locale.ROOT);
        final String key = args[2];

        if ("set".equals(sub)) {
            if (args.length != 4) {
                this.msg(sender, ChatColor.RED + "Usage: /pa " + this.arena.getName() + " !cad set <name> <seconds>");
                return;
            }
            this.setDrop(sender, key, args[3]);

        } else if ("item".equals(sub)) {
            this.addItem(sender, key);

        } else if ("remove".equals(sub)) {
            if (this.yaml().getConfigurationSection(CFG_DROPS + "." + key) == null) {
                this.msg(sender, ChatColor.RED + "No drop named " + ChatColor.YELLOW + key + ChatColor.RED + ".");
                return;
            }
            this.write(CFG_DROPS + "." + key, null);
            this.msg(sender, ChatColor.YELLOW + "Removed drop " + ChatColor.WHITE + key + ChatColor.YELLOW + ".");

        } else {
            this.msg(sender, ChatColor.RED + "Unknown argument " + ChatColor.YELLOW + args[1]
                    + ChatColor.RED + " — expected set, item or remove.");
        }
    }

    /** {@code !cad set <name> <seconds>} — the sender's own block becomes the drop point. */
    private void setDrop(final CommandSender sender, final String key, final String secondsArg) {
        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return;
        }
        final int seconds;
        try {
            seconds = Integer.parseInt(secondsArg);
        } catch (final NumberFormatException e) {
            this.msg(sender, ChatColor.RED + "'" + secondsArg + "' is not a number of seconds.");
            return;
        }
        if (seconds < 0) {
            this.msg(sender, ChatColor.RED + "The drop time can't be negative.");
            return;
        }

        final PABlockLocation location = new PABlockLocation(((Player) sender).getLocation());
        this.arena.getConfig().setManually(CFG_DROPS + "." + key + ".atSeconds", seconds);
        this.arena.getConfig().setManually(CFG_DROPS + "." + key + ".location", location.toString());
        if (!this.yaml().contains(CFG_DROPS + "." + key + ".name")) {
            this.arena.getConfig().setManually(CFG_DROPS + "." + key + ".name", DEF_LABEL);
        }
        this.write(null, null);

        this.msg(sender, ChatColor.YELLOW + "Drop " + ChatColor.WHITE + key + ChatColor.YELLOW + " set at "
                + ChatColor.WHITE + location + ChatColor.YELLOW + ", " + ChatColor.WHITE + formatLead(seconds)
                + ChatColor.YELLOW + " into the match.");
        if (this.yaml().getList(CFG_DROPS + "." + key + ".items") == null) {
            this.msg(sender, ChatColor.GRAY + "Now add items: hold one and run " + ChatColor.WHITE
                    + "/pa " + this.arena.getName() + " !cad item " + key);
        }
    }

    /** {@code !cad item <name>} — append the held item to a drop. */
    private void addItem(final CommandSender sender, final String key) {
        if (!(sender instanceof Player)) {
            Arena.pmsg(sender, MSG.ERROR_ONLY_PLAYERS);
            return;
        }
        if (this.yaml().getConfigurationSection(CFG_DROPS + "." + key) == null) {
            this.msg(sender, ChatColor.RED + "No drop named " + ChatColor.YELLOW + key + ChatColor.RED
                    + " — create it first with !cad set " + key + " <seconds>.");
            return;
        }
        final ItemStack held = ((Player) sender).getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            this.msg(sender, ChatColor.RED + "Hold the item you want dropped.");
            return;
        }

        // Must be the serialized map form: getItemStacksFromConfig casts each entry to Map.
        final String path = CFG_DROPS + "." + key + ".items";
        final List<Object> items = new ArrayList<>();
        final List<?> existing = this.yaml().getList(path);
        if (existing != null) {
            items.addAll(existing);
        }
        final List<Map<String, Object>> serialized = Utils.getSerializableItemStacks(held.clone());
        items.addAll(serialized);
        this.write(path, items);

        this.msg(sender, ChatColor.YELLOW + "Added " + ChatColor.WHITE + held.getAmount() + "x "
                + held.getType() + ChatColor.YELLOW + " to drop " + ChatColor.WHITE + key
                + ChatColor.YELLOW + " (" + items.size() + " total).");
    }

    /** Persist an optional path change, then re-read the drops so the edit takes effect immediately. */
    private void write(final String path, final Object value) {
        if (path != null) {
            this.arena.getConfig().setManually(path, value);
        }
        this.arena.getConfig().save();
        this.loadDrops();
    }

    // ---- match ----------------------------------------------------------------------------------

    @Override
    public void parseStart() {
        this.reset(false); // defensive: never leak a task or an item from a previous match

        if (this.drops.isEmpty()) {
            return; // nothing configured — parseDrop already logged why
        }

        this.task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                this.elapsed++;
                Airdrop.this.tick(this.elapsed);
            }
        }.runTaskTimer(PVPArena.getInstance(), 20L, 20L);
    }

    /** One second of match time: warn, drop, and keep what's on the ground from despawning. */
    private void tick(final int elapsed) {
        for (final Drop drop : this.drops) {
            final int remaining = drop.atSeconds() - elapsed;
            if (remaining == 0) {
                this.spawn(drop);
                continue;
            }
            for (final Warning warning : this.warnings) {
                if (warning.leadSeconds() == remaining) {
                    this.broadcast(format(warning.message(), drop, remaining, null));
                }
            }
        }

        // Vanilla despawns a dropped item after 5 minutes. A drop nobody contested for that long is
        // exactly the one still worth fighting over, so keep it until the match ends.
        this.liveItems.removeIf(item -> {
            if (!item.isValid()) {
                return true;
            }
            item.setTicksLived(1);
            return false;
        });
    }

    private void spawn(final Drop drop) {
        final Location location = drop.location().toLocation();
        if (location.getWorld() == null) {
            log("drop '" + drop.key() + "' points at an unloaded world — skipped");
            return;
        }
        final Location center = location.add(0.5, 0.5, 0.5);

        for (final ItemStack configured : drop.items()) {
            final ItemStack stack = configured.clone();
            final ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                // Tag the stack so the pickup hook can tell our drop from any other item on the floor.
                meta.getPersistentDataContainer().set(key(), PersistentDataType.STRING, drop.key());
                stack.setItemMeta(meta);
            }

            final Item item = center.getWorld().dropItem(center, stack);
            item.setVelocity(new Vector()); // dropItem scatters by default; land it on the mark
            item.setGlowing(true);          // outline, visible through walls
            item.setCustomName(drop.label());
            item.setCustomNameVisible(true);
            item.setPickupDelay(0);
            item.setInvulnerable(true);     // a drop point in lava or a fire is still a drop point
            this.liveItems.add(item);
        }

        center.getWorld().playSound(center, "block.beacon.activate", 1f, 1.4f);
        this.broadcast(format(this.announceDrop, drop, 0, null));
    }

    /** The item is the reward, so the pickup is deliberately not cancelled — only announced. */
    @Override
    public void onPlayerPickupItem(final EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        final Item entity = event.getItem();
        final String tag = tagOf(entity.getItemStack());
        if (tag == null) {
            return;
        }

        // The drop can be gone from the config by now (an admin edit mid-match); the item still
        // carries its own nameplate, so fall back to that rather than dropping the announcement.
        final Drop drop = this.drops.stream().filter(d -> d.key().equals(tag)).findFirst().orElse(null);
        final String label = drop != null ? drop.label()
                : (entity.getCustomName() == null ? "airdrop" : entity.getCustomName());
        final String where = drop != null ? coords(drop.location()) : coords(entity.getLocation());

        this.liveItems.remove(entity);
        this.broadcast(format(this.announcePickup, label, where, 0, event.getEntity().getName()));
    }

    private static String tagOf(final ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(key(), PersistentDataType.STRING);
    }

    /** Built on demand: the plugin instance is not guaranteed at module class-load time. */
    private static NamespacedKey key() {
        return new NamespacedKey(PVPArena.getInstance(), "cyanairdrop");
    }

    @Override
    public void reset(final boolean force) {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        this.liveItems.forEach(Item::remove);
        this.liveItems.clear();
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage(String.format("drops: %d | announce: %s | warnings at: %s",
                this.drops.size(), this.announce,
                this.warnings.stream().map(w -> formatLead(w.leadSeconds()))
                        .collect(java.util.stream.Collectors.joining(", "))));
        for (final Drop drop : this.drops) {
            sender.sendMessage(String.format("  %s: %s at %s (%d item%s)",
                    drop.key(), formatLead(drop.atSeconds()), coords(drop.location()),
                    drop.items().length, drop.items().length == 1 ? "" : "s"));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void broadcast(final String message) {
        if (this.announce) {
            this.arena.broadcast(message);
        }
    }

    private void msg(final CommandSender sender, final String message) {
        this.arena.msg(sender, message);
    }

    private static String coords(final PABlockLocation location) {
        return location.getX() + ", " + location.getY() + ", " + location.getZ();
    }

    private static String coords(final Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    /** Placeholder substitution for a configured message, then {@code &} colour codes. */
    private static String format(final String template, final Drop drop, final int seconds,
                                 final String playerName) {
        return format(template, drop.label(), coords(drop.location()), seconds, playerName);
    }

    /**
     * Supported placeholders: {@code [drop]}, {@code [coords]}, {@code [seconds]} and
     * {@code [playername]}. The drop label arrives already coloured (translated at parse time), so
     * the second pass here only resolves the {@code &} codes the message itself carries.
     */
    private static String format(final String template, final String label, final String where,
                                 final int seconds, final String playerName) {
        final String filled = template
                .replace("[drop]", label)
                .replace("[coords]", where)
                .replace("[seconds]", formatLead(seconds))
                .replace("[playername]", playerName == null ? "" : playerName);
        return ChatColor.translateAlternateColorCodes('&', filled);
    }

    /** 600 -> "10m", 90 -> "1m30s", 30 -> "30s". */
    private static String formatLead(final int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        final int minutes = seconds / 60;
        final int rest = seconds % 60;
        return rest == 0 ? minutes + "m" : minutes + "m" + rest + "s";
    }

    private static void log(final String message) {
        final PVPArena instance = PVPArena.getInstance();
        (instance != null ? instance.getLogger() : Bukkit.getLogger()).warning("[Airdrop] " + message);
    }
}
