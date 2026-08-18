package net.slipcor.pvparena.modules.cyaninvisibilityfix;

import net.slipcor.pvparena.arena.Arena;
import net.slipcor.pvparena.commands.AbstractArenaCommand;
import net.slipcor.pvparena.commands.CommandTree;
import net.slipcor.pvparena.core.Language.MSG;
import net.slipcor.pvparena.loadables.ArenaModule;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * <pre>InvisFix — read and clear a stuck {@code Bukkit.invisible} flag.</pre>
 *
 * <p>Paper persists {@code Entity#setInvisible} as a root-level {@code Bukkit.invisible} byte in
 * {@code playerdata/&lt;uuid&gt;.dat}, so a plugin (or a crash) that sets it and never clears it
 * leaves the player invisible across relogs and restarts. The usual fixes don't reach it:
 * {@code showPlayer} is per-viewer, and this flag is on the entity itself.</p>
 *
 * <p>{@code isInvisible()}/{@code setInvisible()} read and write exactly that tag — they are backed
 * by {@code Entity.persistentInvisibility}, which is what Paper serializes under that name.
 * Clearing it takes effect immediately in-game; the {@code .dat} on disk only catches up on the
 * next player save (logout or autosave), so don't read the file back to confirm.</p>
 *
 * <pre>
 * /pa &lt;arena&gt; !invischeck [player]   show the flag
 * /pa &lt;arena&gt; !invisfix   [player]   clear the flag
 * </pre>
 *
 * <p>ponytail: this is a bandaid for a bug in some other plugin, packaged as an arena module purely
 * because {@code /pa modules install} hot-loads without a server restart. It has nothing to do with
 * arenas and touches no arena state — attach it to any one arena and forget it. When the plugin
 * that sets the flag is found and fixed, {@code /pa modules uninstall cyaninvisibilityfix} and it
 * is gone, again without a restart.</p>
 *
 * <p>ponytail: online players only. The flag lives in the player's .dat file, and editing that
 * safely means the player must not be logged in AND the server must not hold a cached copy — an
 * NBT editor, not a command.</p>
 */
public class CyanInvisibilityFix extends ArenaModule {

    private static final String PERM = "invisibility.fix.command";

    private static final List<String> FIX_CMDS = Arrays.asList("!invisfix", "invisibilityfix");
    private static final List<String> CHECK_CMDS = Arrays.asList("!invischeck", "invisibilitycheck");

    public CyanInvisibilityFix() {
        super("InvisFix");
    }

    @Override
    public String version() {
        return this.getClass().getPackage().getImplementationVersion();
    }

    @Override
    public void displayInfo(final CommandSender sender) {
        sender.sendMessage("clears a stuck Bukkit.invisible flag: !invisfix / !invischeck [player]");
        sender.sendMessage("permission: " + PERM);
    }

    // ---- command wiring -----------------------------------------------------------------------

    @Override
    public boolean checkCommand(final String arg) {
        return FIX_CMDS.contains(arg) || CHECK_CMDS.contains(arg);
    }

    @Override
    public List<String> getMain() {
        return Arrays.asList("invisibilityfix", "invisibilitycheck");
    }

    @Override
    public List<String> getShort() {
        return Arrays.asList("!invisfix", "!invischeck");
    }

    @Override
    public CommandTree<String> getSubs(final Arena arena) {
        final CommandTree<String> result = new CommandTree<>(null);
        result.define(new String[]{"{Player}"});
        return result;
    }

    @Override
    public void commitCommand(final CommandSender sender, final String[] args) {
        // WorkflowManager calls us without checking anything, so the gate lives here. An
        // unregistered node defaults to op-only, and LuckPerms can still grant it explicitly.
        if (!sender.hasPermission(PERM)) {
            this.arena.msg(sender, MSG.ERROR_NOPERM, PERM);
            return;
        }

        // args[0] is the command token itself; args[1], if present, is the target name.
        if (!AbstractArenaCommand.argCountValid(sender, this.arena, args, new Integer[]{1, 2})) {
            return;
        }

        final Player target = resolve(sender, args);
        if (target == null) {
            return;
        }

        final boolean invisible = target.isInvisible();

        // Fail closed: anything that isn't exactly the fix command only reports.
        if (!FIX_CMDS.contains(args[0].toLowerCase())) {
            // Both notations: NBT stores this as a byte, so the .dat shows 1/0, not true/false.
            Arena.pmsg(sender, ChatColor.AQUA + target.getName() + ChatColor.GRAY
                    + " Bukkit.invisible = "
                    + (invisible ? ChatColor.RED + "true (1)" : ChatColor.GREEN + "false (0)"));
            return;
        }

        if (!invisible) {
            Arena.pmsg(sender, ChatColor.GRAY + target.getName()
                    + " is already visible — nothing to fix.");
            return;
        }

        target.setInvisible(false);
        Arena.pmsg(sender, ChatColor.GREEN + "Cleared Bukkit.invisible on " + target.getName() + ".");
        if (!sender.equals(target)) {
            Arena.pmsg(target, ChatColor.GREEN + "You are visible again.");
        }
        // Someone else's visibility changed — leave a trail.
        Bukkit.getLogger().info("[InvisFix] " + sender.getName()
                + " cleared Bukkit.invisible on " + target.getName());
    }

    /** No argument means the sender; console must name someone. Messages the sender on failure. */
    private static Player resolve(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player) {
                return (Player) sender;
            }
            Arena.pmsg(sender, ChatColor.RED + "Console must name a player.");
            return null;
        }

        // getPlayerExact is case-insensitive but does not prefix-match, so "Ste" never hits "Steve".
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Arena.pmsg(sender, ChatColor.RED + args[1]
                    + " is not online. This only works on online players.");
        }
        return target;
    }
}
