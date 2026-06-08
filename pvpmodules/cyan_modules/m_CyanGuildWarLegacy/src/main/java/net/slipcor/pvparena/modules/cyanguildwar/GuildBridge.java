package net.slipcor.pvparena.modules.cyanguildwar;

import net.slipcor.pvparena.PVPArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reflection bridge to the UltimateClans ("Guild") API — used so GuildWar never references
 * UltimateClans types directly and PVP Arena needs <b>no</b> {@code plugin.yml} softdepend (keeps
 * core pristine for clean upstream re-forks).
 *
 * <p>This is a deliberate, self-contained <b>copy</b> of the Gladiator module's {@code GuildBridge}
 * (only the methods GuildWar needs). Keeping our own copy means {@code m_CyanGuildWar} has zero
 * dependency on any other Cyan jar — enable/disable either module with no impact on the other.</p>
 *
 * <p>We reach the API through UltimateClans' <i>own</i> plugin classloader and cache the {@link Method}
 * handles once. If UltimateClans is absent or the API changed, the bridge reports
 * {@link #isAvailable()} {@code false} and GuildWar rejects all queueing — a safe degrade.</p>
 */
final class GuildBridge {

    private static final String PLUGIN_NAME = "UltimateClans";
    private static GuildBridge instance;

    private boolean available;
    private Object playerAPI;
    private Object clanAPI;
    private Method mHasClan;
    private Method mGetClanID;
    private Method mGetClan;
    private Method mClanGetTag;

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
                log().warning("[GuildWar] UltimateClans not found/enabled — GuildWar will reject queueing until it is.");
                return;
            }

            this.clanAPI = uc.getClass().getMethod("getClanAPI").invoke(uc);
            this.playerAPI = uc.getClass().getMethod("getPlayerAPI").invoke(uc);

            final ClassLoader cl = uc.getClass().getClassLoader();
            final Class<?> clanData = cl.loadClass("me.ulrich.clans.data.ClanData");

            this.mHasClan = this.playerAPI.getClass().getMethod("hasClan", UUID.class);
            this.mGetClanID = this.playerAPI.getClass().getMethod("getClanID", UUID.class);
            this.mGetClan = this.clanAPI.getClass().getMethod("getClan", UUID.class);
            this.mClanGetTag = clanData.getMethod("getTag");

            this.available = true;
            log().info("[GuildWar] UltimateClans (Guild) API bridge ready.");
        } catch (final Throwable t) {
            this.available = false;
            log().warning("[GuildWar] Could not bind the UltimateClans API ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "). GuildWar will reject queueing.");
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
