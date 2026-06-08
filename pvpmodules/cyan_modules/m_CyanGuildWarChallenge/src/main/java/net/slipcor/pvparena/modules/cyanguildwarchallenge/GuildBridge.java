package net.slipcor.pvparena.modules.cyanguildwarchallenge;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection bridge to the UltimateClans (UClans) API — so this module never references UClans types
 * directly and PVP Arena needs <b>no</b> {@code plugin.yml} softdepend (keeps core pristine for clean
 * re-forks).
 *
 * <p>Bound against the <b>verified</b> UClans-API (<a href="https://github.com/UlrichBR/UClans-API">
 * UlrichBR/UClans-API</a>, package {@code me.ulrich.clans.api} / {@code .data}):</p>
 * <ul>
 *   <li>{@code PlayerAPIManager}: {@code hasClan(UUID)}, {@code getClanID(UUID)->Optional<UUID>},
 *       {@code isSameClan(UUID,UUID)}, {@code getPlayerData(UUID)->PlayerData}.</li>
 *   <li>{@code ClanAPIManager}: {@code getClan(UUID)->Optional<ClanData>},
 *       {@code getClanDataByTag(String)->Optional<ClanData>}, {@code getAllClansData()->List<ClanData>}.</li>
 *   <li>{@code ClanData}: {@code getId()->UUID}, {@code getTag()->String},
 *       {@code getTagNoColor()->String}, {@code getMembers()->List<UUID>}.</li>
 *   <li>{@code PlayerData}: {@code getRole()->String} (the accept/deny role gate).</li>
 * </ul>
 *
 * <p>If UClans is absent or a required method can't be resolved, {@link #isAvailable()} is
 * {@code false} and the module degrades safely.</p>
 */
final class GuildBridge {

    private static final String PLUGIN_NAME = "UltimateClans";

    private static GuildBridge instance;

    private boolean available;
    private Object playerAPI;
    private Object clanAPI;

    // PlayerAPIManager
    private Method mHasClan;
    private Method mGetClanID;
    private Method mGetPlayerData;     // getPlayerData(UUID) -> PlayerData (or Optional<PlayerData>)

    // ClanAPIManager
    private Method mGetClan;           // getClan(UUID) -> Optional<ClanData>
    private Method mGetClanDataByTag;  // getClanDataByTag(String) -> Optional<ClanData>
    private Method mGetAllClansData;   // getAllClansData() -> List<ClanData>

    // ClanData
    private Method mClanGetId;
    private Method mClanGetTag;
    private Method mClanGetTagNoColor;
    private Method mClanGetMembers;

    // PlayerData
    private Method mPlayerGetRole;     // getRole() -> String

    static GuildBridge get() {
        if (instance == null) {
            instance = new GuildBridge();
            instance.init();
        }
        return instance;
    }

    /** Force a re-bind on next {@link #get()} (e.g. after a reload). */
    static void invalidate() {
        instance = null;
    }

    boolean isAvailable() {
        return this.available;
    }

    private void init() {
        try {
            final Plugin uc = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (uc == null || !uc.isEnabled()) {
                this.available = false;
                log().warning("[GuildWarChallenge] UltimateClans not found/enabled — challenges disabled until it is.");
                return;
            }

            this.clanAPI = uc.getClass().getMethod("getClanAPI").invoke(uc);
            this.playerAPI = uc.getClass().getMethod("getPlayerAPI").invoke(uc);

            final ClassLoader cl = uc.getClass().getClassLoader();
            final Class<?> clanData = cl.loadClass("me.ulrich.clans.data.ClanData");
            final Class<?> playerData = cl.loadClass("me.ulrich.clans.data.PlayerData");

            this.mHasClan = this.playerAPI.getClass().getMethod("hasClan", UUID.class);
            this.mGetClanID = this.playerAPI.getClass().getMethod("getClanID", UUID.class);
            this.mGetPlayerData = firstMethod(this.playerAPI.getClass(), new Class<?>[]{UUID.class},
                    "getPlayerData");

            this.mGetClan = this.clanAPI.getClass().getMethod("getClan", UUID.class);
            this.mGetClanDataByTag = firstMethod(this.clanAPI.getClass(), new Class<?>[]{String.class},
                    "getClanDataByTag");
            this.mGetAllClansData = firstMethod(this.clanAPI.getClass(), new Class<?>[0],
                    "getAllClansData");

            this.mClanGetId = clanData.getMethod("getId");
            this.mClanGetTag = clanData.getMethod("getTag");
            this.mClanGetTagNoColor = firstMethod(clanData, new Class<?>[0], "getTagNoColor");
            this.mClanGetMembers = clanData.getMethod("getMembers");

            this.mPlayerGetRole = firstMethod(playerData, new Class<?>[0], "getRole");

            this.available = true;
            log().info("[GuildWarChallenge] UClans API bridge ready"
                    + (this.mPlayerGetRole == null ? " (role getter missing — accept/deny falls back to any member)." : "."));
        } catch (final Throwable t) {
            this.available = false;
            log().warning("[GuildWarChallenge] Could not bind the UClans API ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + "). Challenges disabled.");
        }
    }

    // --------------------------------------------------------------------------------------- core

    boolean hasGuild(final Player player) {
        if (!this.available || player == null) {
            return false;
        }
        try {
            return (Boolean) this.mHasClan.invoke(this.playerAPI, player.getUniqueId());
        } catch (final Throwable t) {
            return false;
        }
    }

    UUID guildId(final Player player) {
        if (!this.available || player == null) {
            return null;
        }
        try {
            return asUuid(this.mGetClanID.invoke(this.playerAPI, player.getUniqueId()));
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Raw (possibly colored) tag. */
    String guildTag(final UUID guildId) {
        return invokeString(clanData(guildId), this.mClanGetTag);
    }

    /** A clean display label for a guild: its no-color tag if available, else its tag. */
    String clanName(final UUID guildId) {
        final Object clan = clanData(guildId);
        if (clan == null) {
            return null;
        }
        final String noColor = invokeString(clan, this.mClanGetTagNoColor);
        if (noColor != null && !noColor.trim().isEmpty()) {
            return noColor;
        }
        return invokeString(clan, this.mClanGetTag);
    }

    @SuppressWarnings("unchecked")
    List<UUID> guildMembers(final UUID guildId) {
        final Object clan = clanData(guildId);
        if (clan == null) {
            return Collections.emptyList();
        }
        try {
            final Object members = this.mClanGetMembers.invoke(clan);
            return members instanceof List ? (List<UUID>) members : Collections.emptyList();
        } catch (final Throwable t) {
            return Collections.emptyList();
        }
    }

    /** The currently-online players of a guild. */
    List<Player> onlineMembers(final UUID guildId) {
        final List<Player> out = new ArrayList<>();
        for (final UUID id : guildMembers(guildId)) {
            if (id == null) {
                continue;
            }
            final Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Distinct guild UUIDs that currently have at least one online member. */
    Set<UUID> onlineGuilds() {
        final Set<UUID> out = new HashSet<>();
        if (!this.available) {
            return out;
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final UUID g = guildId(p);
            if (g != null) {
                out.add(g);
            }
        }
        return out;
    }

    boolean isMember(final UUID guildId, final UUID playerId) {
        if (guildId == null || playerId == null) {
            return false;
        }
        for (final UUID id : guildMembers(guildId)) {
            if (playerId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------------------- clan-by-query

    /**
     * Resolve a guild from a command argument (the clan <b>tag</b>), case-insensitively. Tries the
     * direct {@code getClanDataByTag} lookup first, then guilds with online members, then a full
     * enumeration. Returns the guild UUID or {@code null}.
     */
    UUID clanByQuery(final String query) {
        if (!this.available || query == null || query.trim().isEmpty()) {
            return null;
        }
        final String q = query.trim();
        final String norm = normalize(q);
        if (norm.isEmpty()) {
            return null;
        }

        // 1) Direct, confirmed lookup by tag.
        if (this.mGetClanDataByTag != null) {
            try {
                final Object clan = unwrap(this.mGetClanDataByTag.invoke(this.clanAPI, q));
                final UUID id = clanIdOf(clan);
                if (id != null) {
                    return id;
                }
            } catch (final Throwable ignored) {
                // fall through
            }
        }

        // 2) Match among guilds with online members (also tolerant of color/case/punctuation).
        for (final UUID g : onlineGuilds()) {
            if (norm.equals(normalize(stripColor(clanName(g))))
                    || norm.equals(normalize(stripColor(guildTag(g))))) {
                return g;
            }
        }

        // 3) Full enumeration fallback.
        for (final Object clan : allClans()) {
            final String tag = invokeString(clan, this.mClanGetTag);
            final String tagNoColor = invokeString(clan, this.mClanGetTagNoColor);
            if (norm.equals(normalize(stripColor(tag))) || norm.equals(normalize(stripColor(tagNoColor)))) {
                final UUID id = clanIdOf(clan);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    private List<?> allClans() {
        if (this.mGetAllClansData == null) {
            return Collections.emptyList();
        }
        try {
            final Object result = this.mGetAllClansData.invoke(this.clanAPI);
            return result instanceof List ? (List<?>) result : Collections.emptyList();
        } catch (final Throwable t) {
            return Collections.emptyList();
        }
    }

    private UUID clanIdOf(final Object clan) {
        return clan == null ? null : asUuid(invoke(clan, this.mClanGetId));
    }

    // --------------------------------------------------------------------------------- role gate

    /**
     * Whether {@code player} may accept/deny/cancel on behalf of {@code guildId}: true iff their clan
     * role is in the configurable {@code accept-roles} set. If the role can't be read, the
     * {@code role-fallback-allow-any-member} toggle decides whether to degrade to "any guild member".
     */
    boolean canManageWar(final Player player, final UUID guildId) {
        return hasAllowedRole(player, guildId, GuildWarConfig.get().acceptRoles());
    }

    /**
     * Whether {@code player} may join a war roster for {@code guildId}. If {@code join-roles} is empty
     * (the default) any member may join; otherwise their role must be in that set, with the same
     * {@code role-fallback-allow-any-member} behavior as {@link #canManageWar}.
     */
    boolean canJoinWar(final Player player, final UUID guildId) {
        if (!this.available || player == null || guildId == null) {
            return false;
        }
        final Set<String> joinRoles = GuildWarConfig.get().joinRoles();
        if (joinRoles.isEmpty()) {
            return isMember(guildId, player.getUniqueId());
        }
        return hasAllowedRole(player, guildId, joinRoles);
    }

    private boolean hasAllowedRole(final Player player, final UUID guildId, final Set<String> allowedRoles) {
        if (!this.available || player == null || guildId == null) {
            return false;
        }
        final String role = playerRole(player.getUniqueId());
        if (role != null) {
            final String norm = normalize(role);
            if (!norm.isEmpty()) {
                return allowedRoles.contains(norm);
            }
        }
        // Role unreadable — degrade to "any member" only if the operator allows it.
        return GuildWarConfig.get().roleFallbackAllowAnyMember() && isMember(guildId, player.getUniqueId());
    }

    private String playerRole(final UUID playerId) {
        if (this.mGetPlayerData == null || this.mPlayerGetRole == null) {
            return null;
        }
        try {
            final Object pData = unwrap(this.mGetPlayerData.invoke(this.playerAPI, playerId));
            if (pData == null) {
                return null;
            }
            final Object role = this.mPlayerGetRole.invoke(pData);
            return role == null ? null : role.toString();
        } catch (final Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------------------------------- internals

    private Object clanData(final UUID guildId) {
        if (!this.available || guildId == null) {
            return null;
        }
        try {
            return unwrap(this.mGetClan.invoke(this.clanAPI, guildId));
        } catch (final Throwable t) {
            return null;
        }
    }

    private static Object invoke(final Object target, final Method method) {
        if (target == null || method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static String invokeString(final Object target, final Method method) {
        final Object v = invoke(target, method);
        return v instanceof String ? (String) v : null;
    }

    private static Object unwrap(final Object result) {
        return result instanceof Optional<?> ? ((Optional<?>) result).orElse(null) : result;
    }

    private static UUID asUuid(final Object result) {
        final Object value = unwrap(result);
        if (value instanceof UUID) {
            return (UUID) value;
        }
        if (value instanceof String) {
            try {
                return UUID.fromString((String) value);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static Method firstMethod(final Class<?> type, final Class<?>[] params, final String... names) {
        for (final String name : names) {
            try {
                return type.getMethod(name, params);
            } catch (final NoSuchMethodException ignored) {
                // try next
            }
        }
        return null;
    }

    private static String stripColor(final String s) {
        return s == null ? "" : s.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
    }

    /** Lowercase, letters+digits only — for tolerant tag/role matching. */
    private static String normalize(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Logger log() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
