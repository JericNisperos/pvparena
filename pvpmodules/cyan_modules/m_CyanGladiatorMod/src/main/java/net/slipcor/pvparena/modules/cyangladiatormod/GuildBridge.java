package net.slipcor.pvparena.modules.cyangladiatormod;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection bridge to the UltimateClans (UClans) API — so this module never references UClans types
 * directly and PVP Arena needs <b>no</b> {@code plugin.yml} softdepend (keeps core pristine for clean
 * re-forks).
 *
 * <p>Bound against the verified UClans-API (package {@code me.ulrich.clans.api} / {@code .data}):
 * {@code PlayerAPIManager.hasClan(UUID)}, {@code getClanID(UUID)->Optional<UUID>},
 * {@code isSameClan(UUID,UUID)}; {@code ClanAPIManager.getClan(UUID)->Optional<ClanData>};
 * {@code ClanData.getTag()/getTagNoColor()->String}, {@code getMembers()->List<UUID>}.</p>
 *
 * <p>If UClans is absent or a required method can't be resolved, {@link #isAvailable()} is
 * {@code false} and the module degrades safely (joins rejected, rewards no-op).</p>
 */
final class GuildBridge {

    private static final String PLUGIN_NAME = "UltimateClans";

    private static GuildBridge instance;

    private boolean available;
    private Object playerAPI;
    private Object clanAPI;
    private Method mHasClan;
    private Method mGetClanID;
    private Method mIsSameClan;
    private Method mGetClan;
    private Method mClanGetTag;
    private Method mClanGetTagNoColor;
    private Method mClanGetMembers;

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
                log().warning("[Gladiator] UltimateClans not found/enabled — Gladiator will reject joins until it is.");
                return;
            }

            this.clanAPI = uc.getClass().getMethod("getClanAPI").invoke(uc);
            this.playerAPI = uc.getClass().getMethod("getPlayerAPI").invoke(uc);

            final ClassLoader cl = uc.getClass().getClassLoader();
            final Class<?> clanData = cl.loadClass("me.ulrich.clans.data.ClanData");

            this.mHasClan = this.playerAPI.getClass().getMethod("hasClan", UUID.class);
            this.mGetClanID = this.playerAPI.getClass().getMethod("getClanID", UUID.class);
            this.mIsSameClan = this.playerAPI.getClass().getMethod("isSameClan", UUID.class, UUID.class);
            this.mGetClan = this.clanAPI.getClass().getMethod("getClan", UUID.class);
            this.mClanGetTag = clanData.getMethod("getTag");
            this.mClanGetTagNoColor = firstMethod(clanData, "getTagNoColor");
            this.mClanGetMembers = clanData.getMethod("getMembers");

            this.available = true;
            log().info("[Gladiator] UltimateClans (Guild) API bridge ready.");
        } catch (final Throwable t) {
            this.available = false;
            log().warning("[Gladiator] Could not bind the UltimateClans API ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + "). Gladiator will reject joins.");
        }
    }

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

    boolean sameGuild(final Player a, final Player b) {
        if (!this.available || a == null || b == null) {
            return false;
        }
        try {
            return (Boolean) this.mIsSameClan.invoke(this.playerAPI, a.getUniqueId(), b.getUniqueId());
        } catch (final Throwable t) {
            return false;
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

    private static String invokeString(final Object target, final Method method) {
        if (target == null || method == null) {
            return null;
        }
        try {
            final Object v = method.invoke(target);
            return v instanceof String ? (String) v : null;
        } catch (final Throwable t) {
            return null;
        }
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

    private static Method firstMethod(final Class<?> type, final String... names) {
        for (final String name : names) {
            try {
                return type.getMethod(name);
            } catch (final NoSuchMethodException ignored) {
                // try next
            }
        }
        return null;
    }

    private static Logger log() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
