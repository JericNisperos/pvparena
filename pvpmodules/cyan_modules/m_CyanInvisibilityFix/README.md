# InvisFix

Reads and clears a stuck `Bukkit.invisible` flag on an online player.

## The bug this papers over

Paper persists `Entity#setInvisible` as a root-level `Bukkit.invisible` byte in
`playerdata/<uuid>.dat`, alongside `Paper.SpawnReason` and friends:

```
01  00 10  "Bukkit.invisible"  01
^   ^      ^                   ^
|   |      name (16 chars)     value
|   name length
TAG_Byte
```

Some plugin sets it and never clears it, so the player stays invisible across relogs and
restarts. Nothing in PVPArena does this — core and every other cyan module use per-viewer
`hidePlayer`/`showPlayer` or an INVISIBILITY potion, none of which touch this tag. The
real fix is finding the plugin responsible; this module just unsticks the player.

`isInvisible()`/`setInvisible()` read and write exactly that tag — they're backed by
`Entity.persistentInvisibility`, which is what Paper serializes under that name.

## Commands

Both require `invisibility.fix.command`. Omit the player to target yourself; console must
name someone. Online players only.

```
/pa <arena> !invischeck [player]     Steve Bukkit.invisible = true
/pa <arena> !invisfix   [player]     Cleared Bukkit.invisible on Steve.
```

Long forms `!invisibilitycheck` / `!invisibilityfix` also work. On a single-arena server
the arena name can be omitted. Clearing is logged to console with who ran it.

The permission node isn't declared in a `plugin.yml` (modules have none), so it's
unregistered — which defaults it to op-only, and LuckPerms can still grant it explicitly.

## Install — no server restart

That's the whole reason this is an arena module rather than a standalone plugin:
`/pa modules install` hot-swaps the classloader.

```bash
mvn -f pvpmodules/cyan_modules/pom.xml -pl m_CyanInvisibilityFix clean package
# copy target/pa_m_cyaninvisibilityfix.jar -> plugins/PVPArena/files/
```

```
/pa modules install cyaninvisibilityfix    # install name = jar name minus pa_m_ / .jar
/pa <arena> !tm InvisFix                   # toggle name  = module.yml name
```

It touches no arena state, so attach it to any one arena and forget it. When the culprit
plugin is fixed, `/pa modules uninstall cyaninvisibilityfix` — also without a restart.

## Notes

- **The `.dat` won't change immediately.** Minecraft writes playerdata on logout or
  autosave. In-game the player is visible at once; don't read the file back to confirm.
- **Vanish plugins use this same flag.** `!invisfix` on vanished staff un-vanishes them
  without their vanish plugin knowing, and `!invischeck` is a vanish detector. Keep the
  permission at op level.
- Compiles against spigot-api **1.19.4**, not the pack's 1.18.2 — `Entity#isInvisible` and
  `setInvisible` only entered the Spigot API in 1.19.4. Both are interface methods in every
  version, so this links fine on 1.21 (`javap` shows `invokeinterface`).
