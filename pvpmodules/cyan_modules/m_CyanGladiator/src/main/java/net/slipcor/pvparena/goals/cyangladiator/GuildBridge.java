package net.slipcor.pvparena.goals.cyangladiator;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection bridge to the UltimateClans ("Guild") API — used so the Gladiator goal never references
 * UltimateClans types directly and PVP Arena needs <b>no</b> {@code plugin.yml} softdepend (keeps
 * core pristine for clean upstream re-forks).
 *
 * <p>We reach the API through UltimateClans' <i>own</i> plugin classloader and cache the {@link Method}
 * handles once. If UltimateClans is absent or the API changed (a method can't be resolved), the bridge
 * reports {@link #isAvailable()} {@code false} and the goal rejects all joins — a safe degrade.</p>
 *
 * <p>Wrapped API (package {@code me.ulrich.clans.api} / {@code .data}):
 * {@code PlayerAPIManager.hasClan(UUID)}, {@code getClanID(UUID)->Optional<UUID>},
 * {@code isSameClan(UUID,UUID)}; {@code ClanAPIManager.getClan(UUID)->Optional<ClanData>};
 * {@code ClanData.getTag()->String}, {@code getMembers()->List<UUID>}.</p>
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
            this.mClanGetMembers = clanData.getMethod("getMembers");

            this.available = true;
            log().info("[Gladiator] UltimateClans (Guild) API bridge ready.");
        } catch (final Throwable t) {
            this.available = false;
            log().warning("[Gladiator] Could not bind the UltimateClans API ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "). Gladiator will reject joins.");
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
            final Object result = this.mGetClanID.invoke(this.playerAPI, player.getUniqueId());
            if (result instanceof Optional<?> opt) {
                return (UUID) opt.orElse(null);
            }
            return null;
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

    String guildTag(final UUID guildId) {
        final Object clan = clanData(guildId);
        if (clan == null) {
            return null;
        }
        try {
            return (String) this.mClanGetTag.invoke(clan);
        } catch (final Throwable t) {
            return null;
        }
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

    private Object clanData(final UUID guildId) {
        if (!this.available || guildId == null) {
            return null;
        }
        try {
            final Object result = this.mGetClan.invoke(this.clanAPI, guildId);
            if (result instanceof Optional<?> opt) {
                return opt.orElse(null);
            }
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    private static Logger log() {
        final PVPArena instance = PVPArena.getInstance();
        return instance != null ? instance.getLogger() : Bukkit.getLogger();
    }
}
