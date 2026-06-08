package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Challenge-mode settings, backed by {@code plugins/PVPArena/cyan_guildwarchallenge_config.yml}.
 *
 * <p>Written with documented defaults on first run, reloaded on enable / {@code /pa reloadall}.</p>
 *
 * <table>
 *   <tr><th>key</th><th>default</th><th>meaning</th></tr>
 *   <tr><td>{@code teleport-warning-seconds}</td><td>5</td><td>"teleporting in Ns" warning once both rosters are full, before pulling players in</td></tr>
 *   <tr><td>{@code lounge-countdown-seconds}</td><td>10</td><td>in-lounge countdown after teleport, before the fight starts</td></tr>
 *   <tr><td>{@code accept-timeout-seconds}</td><td>60</td><td>auto-cancel a PENDING challenge after this</td></tr>
 *   <tr><td>{@code staging-timeout-seconds}</td><td>120</td><td>auto-cancel an un-filled STAGING war after this</td></tr>
 *   <tr><td>{@code arena-prefix}</td><td>guildwar</td><td>arenas whose name starts with this are GuildWar (challenge-only)</td></tr>
 *   <tr><td>{@code min-count}</td><td>1</td><td>floor on the per-side player count of a challenge</td></tr>
 *   <tr><td>{@code max-count}</td><td>10</td><td>hard cap on the per-side player count</td></tr>
 *   <tr><td>{@code cooldown-seconds}</td><td>0</td><td>per-guild wait between issuing challenges (0 = none)</td></tr>
 *   <tr><td>{@code accept-roles}</td><td>leader, viceleader, quartermaster, diplomat</td><td>clan roles allowed to accept/deny/cancel</td></tr>
 *   <tr><td>{@code join-roles}</td><td>(empty)</td><td>clan roles allowed to join a roster; empty = any member</td></tr>
 *   <tr><td>{@code role-fallback-allow-any-member}</td><td>true</td><td>when a player's role can't be read, fall back to allowing any guild member</td></tr>
 *   <tr><td>{@code reward-scope}</td><td>PARTICIPANTS</td><td>who reward commands target: {@code PARTICIPANTS} (only the fighters of that side) or {@code ALL_MEMBERS} (every online guild member)</td></tr>
 *   <tr><td>{@code winner-commands}</td><td>(one broadcast)</td><td>EventActions-style {@code prefix<=>command} rewards run for the winning side</td></tr>
 *   <tr><td>{@code loser-commands}</td><td>(empty)</td><td>consolation {@code prefix<=>command} rewards run for the defeated side; empty = none</td></tr>
 * </table>
 */
final class GuildWarConfig {

    private static final String FILE_NAME = "cyan_guildwarchallenge_config.yml";

    private static final int DEF_WARNING = 5;
    private static final int DEF_LOUNGE = 10;
    private static final int DEF_ACCEPT_TIMEOUT = 60;
    private static final int DEF_STAGING_TIMEOUT = 120;
    private static final String DEF_PREFIX = "guildwar";
    private static final int DEF_MIN_COUNT = 1;
    private static final int DEF_MAX_COUNT = 10;
    private static final int DEF_COOLDOWN = 0;
    private static final boolean DEF_ROLE_FALLBACK = true;
    private static final List<String> DEF_ACCEPT_ROLES = Arrays.asList(
            "leader", "viceleader", "quartermaster", "diplomat");
    private static final List<String> DEF_JOIN_ROLES = Collections.emptyList();
    private static final String DEF_REWARD_SCOPE = "PARTICIPANTS";
    private static final List<String> DEF_WINNER_COMMANDS = Collections.singletonList(
            "console<=>broadcast &6Guild &e%guild%&6 defeated &e%enemy%&6 in the Guild War at %arena%!");
    private static final List<String> DEF_LOSER_COMMANDS = Collections.emptyList();

    private static GuildWarConfig instance;

    private int teleportWarningSeconds = DEF_WARNING;
    private int loungeCountdownSeconds = DEF_LOUNGE;
    private int acceptTimeoutSeconds = DEF_ACCEPT_TIMEOUT;
    private int stagingTimeoutSeconds = DEF_STAGING_TIMEOUT;
    private String arenaPrefix = DEF_PREFIX;
    private int minCount = DEF_MIN_COUNT;
    private int maxCount = DEF_MAX_COUNT;
    private int cooldownSeconds = DEF_COOLDOWN;
    private boolean roleFallbackAllowAnyMember = DEF_ROLE_FALLBACK;
    private Set<String> acceptRoles = normalizeRoles(DEF_ACCEPT_ROLES);
    private Set<String> joinRoles = normalizeRoles(DEF_JOIN_ROLES);
    private boolean rewardAllMembers = false;
    private List<String> winnerCommands = DEF_WINNER_COMMANDS;
    private List<String> loserCommands = DEF_LOSER_COMMANDS;

    static GuildWarConfig get() {
        if (instance == null) {
            instance = new GuildWarConfig();
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

        // Write the file on first run AND whenever an upgrade introduces new keys, so existing
        // installs pick up settings like the reward keys without having to delete their config.
        if (ensureDefaults(yaml) || !existed) {
            saveConfig(yaml, file);
        }

        this.teleportWarningSeconds = positive(yaml.getInt("teleport-warning-seconds", DEF_WARNING), DEF_WARNING);
        this.loungeCountdownSeconds = positive(yaml.getInt("lounge-countdown-seconds", DEF_LOUNGE), DEF_LOUNGE);
        this.acceptTimeoutSeconds = positive(yaml.getInt("accept-timeout-seconds", DEF_ACCEPT_TIMEOUT), DEF_ACCEPT_TIMEOUT);
        this.stagingTimeoutSeconds = positive(yaml.getInt("staging-timeout-seconds", DEF_STAGING_TIMEOUT), DEF_STAGING_TIMEOUT);
        this.maxCount = positive(yaml.getInt("max-count", DEF_MAX_COUNT), DEF_MAX_COUNT);
        this.minCount = clamp(yaml.getInt("min-count", DEF_MIN_COUNT), 1, this.maxCount);
        this.cooldownSeconds = Math.max(0, yaml.getInt("cooldown-seconds", DEF_COOLDOWN));
        this.roleFallbackAllowAnyMember = yaml.getBoolean("role-fallback-allow-any-member", DEF_ROLE_FALLBACK);

        this.acceptRoles = normalizeRoles(yaml.getStringList("accept-roles"));
        if (this.acceptRoles.isEmpty()) {
            this.acceptRoles = normalizeRoles(DEF_ACCEPT_ROLES);
        }
        this.joinRoles = normalizeRoles(yaml.getStringList("join-roles"));

        final String prefix = yaml.getString("arena-prefix", DEF_PREFIX);
        this.arenaPrefix = (prefix == null || prefix.trim().isEmpty())
                ? DEF_PREFIX : prefix.trim().toLowerCase(Locale.ROOT);

        this.rewardAllMembers = "ALL_MEMBERS".equalsIgnoreCase(
                yaml.getString("reward-scope", DEF_REWARD_SCOPE));
        this.winnerCommands = yaml.getStringList("winner-commands");
        this.loserCommands = yaml.getStringList("loser-commands");
    }

    int teleportWarningSeconds() {
        return this.teleportWarningSeconds;
    }

    int loungeCountdownSeconds() {
        return this.loungeCountdownSeconds;
    }

    int acceptTimeoutSeconds() {
        return this.acceptTimeoutSeconds;
    }

    int stagingTimeoutSeconds() {
        return this.stagingTimeoutSeconds;
    }

    String arenaPrefix() {
        return this.arenaPrefix;
    }

    int minCount() {
        return this.minCount;
    }

    int maxCount() {
        return this.maxCount;
    }

    int cooldownSeconds() {
        return this.cooldownSeconds;
    }

    boolean roleFallbackAllowAnyMember() {
        return this.roleFallbackAllowAnyMember;
    }

    /** Normalized (lowercase, letters/digits only) clan roles allowed to accept/deny/cancel. */
    Set<String> acceptRoles() {
        return this.acceptRoles;
    }

    /** Normalized clan roles allowed to join a roster; an empty set means "any member may join". */
    Set<String> joinRoles() {
        return this.joinRoles;
    }

    /** {@code true} = reward every online guild member; {@code false} = only that side's fighters. */
    boolean rewardAllMembers() {
        return this.rewardAllMembers;
    }

    /** EventActions-style {@code prefix<=>command} rewards for the winning side (may be empty). */
    List<String> winnerCommands() {
        return this.winnerCommands;
    }

    /** Consolation {@code prefix<=>command} rewards for the defeated side (may be empty). */
    List<String> loserCommands() {
        return this.loserCommands;
    }

    private static int positive(final int value, final int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Lowercase, letters+digits only — matches {@link GuildBridge}'s role normalization. */
    private static Set<String> normalizeRoles(final List<String> roles) {
        final Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (final String role : roles) {
                if (role == null) {
                    continue;
                }
                final String norm = role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (!norm.isEmpty()) {
                    out.add(norm);
                }
            }
        }
        return out;
    }

    /** Set any key that's missing to its default. Returns {@code true} if anything was added. */
    private boolean ensureDefaults(final YamlConfiguration yaml) {
        boolean changed = false;
        changed |= setIfAbsent(yaml, "teleport-warning-seconds", DEF_WARNING);
        changed |= setIfAbsent(yaml, "lounge-countdown-seconds", DEF_LOUNGE);
        changed |= setIfAbsent(yaml, "accept-timeout-seconds", DEF_ACCEPT_TIMEOUT);
        changed |= setIfAbsent(yaml, "staging-timeout-seconds", DEF_STAGING_TIMEOUT);
        changed |= setIfAbsent(yaml, "arena-prefix", DEF_PREFIX);
        changed |= setIfAbsent(yaml, "min-count", DEF_MIN_COUNT);
        changed |= setIfAbsent(yaml, "max-count", DEF_MAX_COUNT);
        changed |= setIfAbsent(yaml, "cooldown-seconds", DEF_COOLDOWN);
        changed |= setIfAbsent(yaml, "accept-roles", DEF_ACCEPT_ROLES);
        changed |= setIfAbsent(yaml, "join-roles", DEF_JOIN_ROLES);
        changed |= setIfAbsent(yaml, "role-fallback-allow-any-member", DEF_ROLE_FALLBACK);
        changed |= setIfAbsent(yaml, "reward-scope", DEF_REWARD_SCOPE);
        changed |= setIfAbsent(yaml, "winner-commands", DEF_WINNER_COMMANDS);
        changed |= setIfAbsent(yaml, "loser-commands", DEF_LOSER_COMMANDS);
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
        yaml.options().header("CyanGuildWarChallenge settings. See plans/guildwar/01-challenge-plan.md.\n"
                + "teleport-warning-seconds: 'teleporting in Ns' warning once both rosters are full,\n"
                + "  shown while players are still free in the world, before they're pulled into the arena.\n"
                + "lounge-countdown-seconds: in-lounge countdown after teleport, before the fight starts.\n"
                + "accept-timeout-seconds: a PENDING challenge auto-cancels after this with no accept.\n"
                + "staging-timeout-seconds: a STAGING war auto-cancels after this if not filled.\n"
                + "arena-prefix: arenas whose name starts with this are GuildWar (challenge-only) arenas.\n"
                + "min-count: floor on the per-side player count a challenger may request (>= 1, <= max-count).\n"
                + "max-count: hard cap on the per-side player count of a challenge.\n"
                + "cooldown-seconds: a guild must wait this long after issuing a challenge before it can\n"
                + "  issue another one (0 = no cooldown).\n"
                + "accept-roles: clan roles allowed to accept / deny / cancel a challenge on a guild's behalf.\n"
                + "join-roles: clan roles allowed to join a war roster. Leave EMPTY to let any member join.\n"
                + "role-fallback-allow-any-member: if a player's clan role can't be read from UltimateClans,\n"
                + "  true = treat any guild member as permitted; false = deny when the role is unknown.\n"
                + "reward-scope: who reward commands target. PARTICIPANTS = only that side's fighters;\n"
                + "  ALL_MEMBERS = every ONLINE member of the guild. Applies to both winner and loser commands.\n"
                + "winner-commands / loser-commands: EventActions-style 'prefix<=>command' entries run when a\n"
                + "  war ends. prefix is 'console' (run by the server) or 'player' (run by each recipient).\n"
                + "  Placeholders: %player% (recipient), %guild% (recipient's tag), %enemy% (opposing tag),\n"
                + "  %arena%. Entries containing %player% run once per recipient; otherwise once. Leave a list\n"
                + "  empty to disable that side. Feed UltimateClans' Rewards Center by running its own command,\n"
                + "  e.g. 'console<=>clan addpoints %guild% 50'.");
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (final IOException e) {
            log().warning("[GuildWarChallenge] Could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static Logger log() {
        final PVPArena plugin = PVPArena.getInstance();
        return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
    }
}
