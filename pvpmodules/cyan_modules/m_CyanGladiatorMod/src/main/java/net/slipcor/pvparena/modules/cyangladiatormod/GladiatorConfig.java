package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Gladiator (Guild Royal Rumble) settings, backed by {@code plugins/PVPArena/cyan_gladiator_config.yml}.
 *
 * <p>Written with documented defaults on first run, reloaded on enable / {@code /gladiator reload}.
 * The thin goal jar reads only {@code lives} from this same file (see {@code GoalGladiator}).</p>
 *
 * <table>
 *   <tr><th>key</th><th>default</th><th>meaning</th></tr>
 *   <tr><td>{@code min-guilds}</td><td>2</td><td>distinct guilds that must be present before a rumble may start</td></tr>
 *   <tr><td>{@code lives}</td><td>1</td><td>lives per player (1 = single-life battle royale)</td></tr>
 *   <tr><td>{@code friendly-fire}</td><td>false</td><td>false = guild-mates can't damage each other; true = anything goes</td></tr>
 *   <tr><td>{@code announce-eliminations}</td><td>true</td><td>broadcast "&lt;guild&gt; was eliminated — N guilds remain" as guilds are wiped out</td></tr>
 *   <tr><td>{@code reward-scope}</td><td>PARTICIPANTS</td><td>who reward commands target: {@code PARTICIPANTS} (only the guild's fighters) or {@code ALL_MEMBERS} (every online guild member)</td></tr>
 *   <tr><td>{@code winner-commands}</td><td>(one broadcast)</td><td>EventActions-style {@code prefix<=>command} rewards for the winning guild</td></tr>
 *   <tr><td>{@code participation-commands}</td><td>(empty)</td><td>consolation {@code prefix<=>command} rewards for every other guild that took part; empty = none</td></tr>
 * </table>
 */
final class GladiatorConfig {

    static final String FILE_NAME = "cyan_gladiator_config.yml";

    private static final int DEF_MIN_GUILDS = 2;
    private static final int DEF_LIVES = 1;
    private static final boolean DEF_FRIENDLY_FIRE = false;
    private static final boolean DEF_ANNOUNCE = true;
    private static final String DEF_REWARD_SCOPE = "PARTICIPANTS";
    private static final List<String> DEF_WINNER_COMMANDS = Collections.singletonList(
            "console<=>broadcast &6Guild &e%guild%&6 won the Gladiator at %arena%!");
    private static final List<String> DEF_PARTICIPATION_COMMANDS = Collections.emptyList();

    private static GladiatorConfig instance;

    private int minGuilds = DEF_MIN_GUILDS;
    private int lives = DEF_LIVES;
    private boolean friendlyFire = DEF_FRIENDLY_FIRE;
    private boolean announceEliminations = DEF_ANNOUNCE;
    private boolean rewardAllMembers = false;
    private List<String> winnerCommands = DEF_WINNER_COMMANDS;
    private List<String> participationCommands = DEF_PARTICIPATION_COMMANDS;

    static GladiatorConfig get() {
        if (instance == null) {
            instance = new GladiatorConfig();
        }
        return instance;
    }

    void load() {
        final PVPArena plugin = PVPArena.getInstance();
        if (plugin == null) {
            return;
        }
        final File file = new File(plugin.getDataFolder(), FILE_NAME);
        final boolean existed = file.exists();
        final YamlConfiguration yaml = existed
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        // Write on first run AND whenever an upgrade introduces new keys, so existing installs pick
        // up new settings without having to delete their config.
        if (ensureDefaults(yaml) || !existed) {
            saveConfig(yaml, file);
        }

        this.minGuilds = Math.max(2, yaml.getInt("min-guilds", DEF_MIN_GUILDS));
        this.lives = Math.max(1, yaml.getInt("lives", DEF_LIVES));
        this.friendlyFire = yaml.getBoolean("friendly-fire", DEF_FRIENDLY_FIRE);
        this.announceEliminations = yaml.getBoolean("announce-eliminations", DEF_ANNOUNCE);
        this.rewardAllMembers = "ALL_MEMBERS".equalsIgnoreCase(yaml.getString("reward-scope", DEF_REWARD_SCOPE));
        this.winnerCommands = yaml.getStringList("winner-commands");
        this.participationCommands = yaml.getStringList("participation-commands");
    }

    int minGuilds() {
        return this.minGuilds;
    }

    int lives() {
        return this.lives;
    }

    /** {@code true} = guild-mates may damage each other; {@code false} = same-guild damage is cancelled. */
    boolean friendlyFire() {
        return this.friendlyFire;
    }

    boolean announceEliminations() {
        return this.announceEliminations;
    }

    /** {@code true} = reward every online guild member; {@code false} = only that guild's fighters. */
    boolean rewardAllMembers() {
        return this.rewardAllMembers;
    }

    /** EventActions-style {@code prefix<=>command} rewards for the winning guild (may be empty). */
    List<String> winnerCommands() {
        return this.winnerCommands;
    }

    /** Consolation rewards for every other participating guild (may be empty). */
    List<String> participationCommands() {
        return this.participationCommands;
    }

    /** Set any key that's missing to its default. Returns {@code true} if anything was added. */
    private boolean ensureDefaults(final YamlConfiguration yaml) {
        boolean changed = false;
        changed |= setIfAbsent(yaml, "min-guilds", DEF_MIN_GUILDS);
        changed |= setIfAbsent(yaml, "lives", DEF_LIVES);
        changed |= setIfAbsent(yaml, "friendly-fire", DEF_FRIENDLY_FIRE);
        changed |= setIfAbsent(yaml, "announce-eliminations", DEF_ANNOUNCE);
        changed |= setIfAbsent(yaml, "reward-scope", DEF_REWARD_SCOPE);
        changed |= setIfAbsent(yaml, "winner-commands", DEF_WINNER_COMMANDS);
        changed |= setIfAbsent(yaml, "participation-commands", DEF_PARTICIPATION_COMMANDS);
        return changed;
    }

    private static boolean setIfAbsent(final YamlConfiguration yaml, final String key, final Object value) {
        if (yaml.isSet(key)) {
            return false;
        }
        yaml.set(key, value);
        return true;
    }

    private void saveConfig(final YamlConfiguration yaml, final File file) {
        yaml.options().header("CyanGladiator (Guild Royal Rumble) settings.\n"
                + "min-guilds: distinct guilds that must be present before a rumble may start (>= 2).\n"
                + "lives: lives per player. 1 = single-life battle royale. Also read by the goal jar.\n"
                + "friendly-fire: false = guild-mates can't damage each other; true = anything goes.\n"
                + "announce-eliminations: broadcast '<guild> was eliminated - N guilds remain' as guilds fall.\n"
                + "reward-scope: who reward commands target. PARTICIPANTS = only that guild's fighters;\n"
                + "  ALL_MEMBERS = every ONLINE member of the guild. Applies to winner and participation commands.\n"
                + "winner-commands / participation-commands: EventActions-style 'prefix<=>command' entries run\n"
                + "  when a rumble ends. prefix is 'console' (run by the server) or 'player' (run by each recipient).\n"
                + "  winner-commands run for the surviving guild; participation-commands run for every OTHER guild\n"
                + "  that took part. Placeholders: %player% (recipient), %guild% (recipient's tag),\n"
                + "  %winner% (winning guild's tag), %arena%. Entries with %player% run once per recipient; else once.\n"
                + "  Leave a list empty to disable it. Feed UltimateClans' Rewards Center via its own command,\n"
                + "  e.g. 'console<=>clan addpoints %guild% 50'.");
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (final IOException e) {
            log().warning("[Gladiator] Could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
