# CyanVillageDefense — co-op PvE goal for PVP Arena

A Village Defense-style game mode (inspired by [Plugily's Village Defense](https://github.com/Plugily-Projects/Village_Defense)):
players defend villagers against waves of mobs. **Nobody wins** — the match ends ("game over") when
**all villagers are dead** or **all defenders are dead**. No respawns (one life per player by default).

**One jar** — `pa_m_cyanvillagedefense.jar` goes into `plugins/pvparena/goals/`. It carries the goal,
the per-arena config and the auto-start timer. Nothing to attach to an arena; a `VillageDefense`-goal
arena is all it takes.

> The old companion module (`m_CyanVillageDefenseMod`, `/vdefense` command + shared YAML) was folded
> in once goals became hot-reloadable. It is parked at `../m_CyanVillageDefenseMod-legacy`, out of the
> Maven reactor. Delete `plugins/PVPArena/cyan_villagedefense_config.yml` and remove
> `pa_m_cyanvillagedefensemod.jar` from `/mods` when migrating.

## Build

```
mvn -f pvpmodules/cyan_modules/pom.xml clean package
```

## Arena setup

```
/pa create vd1 VillageDefense
/pa vd1 set ready.minPlayers 1            # allow solo matches (the countdown refuses below minPlayers)
/pa vd1 set ready.enforceCountdown true   # later joins must NOT cancel the running auto-start countdown

/pa vd1 spawn set lounge                  # where players wait before the start
/pa vd1 spawn set exit                    # where players are dropped after game over
/pa vd1 spawn set spectator               # optional
/pa vd1 spawn set fight1                  # defender start points (fight2, fight3, ... as desired)
/pa vd1 spawn set villager1               # villagers spawn here (villager2, ... — at least 1 required)
/pa vd1 spawn set mob1                    # wave mobs spawn here (mob2, ... — at least 1 required)

/pa vd1 region create battlefield        # standard battlefield region
/pa vd1 enable
```

## Playing

- Join with the standard `/pa vd1 join` (or route your own alias to it).
- The match **auto-starts 60 seconds** (`autostart-seconds`) after the first player joins.
- Villagers spawn at the `villager*` spawns; mob waves spawn at the `mob*` spawns every
  `waves.interval-seconds` and grow linearly each wave. Mobs prefer to hunt villagers.
- Wave mobs have a 40% chance to drop emeralds, scaling up with the wave number.
- Villagers are weaponsmiths by default and sell gear for emeralds — spend your wave drops between waves.
- Wave mobs never chase dead players or spectators; they repoint at a villager or a living defender.
- Game over when every villager **or** every defender is dead; the arena then resets.

## Config — arena config, `goal.villagedefense.*`

Defaults are written into each arena's own config, so every arena tunes independently. Edit with
`/pa <arena> set goal.villagedefense.<key> <value>`:

- `autostart-seconds` — join → start delay (default 60)
- `player-lives` — lives per defender (default 1 = no respawn)
- `villagers.per-spawn`, `villagers.protect-from-players`
- `villagers.profession` — vanilla `Villager.Profession` (default `WEAPONSMITH`)
- `villagers.trades` — one line per trade, `<cost material> [amount] > <result material> [amount]`.
  The cost **must** be `EMERALD` or `EMERALD_BLOCK`; any other currency is logged and skipped.
- `villagers.trade-max-uses` — uses per trade before it locks until restock (default 9999)
- `waves.first-delay-seconds`, `waves.interval-seconds`
- `waves.base-mobs`, `waves.mobs-per-wave`, `waves.mobs-per-player`, `waves.max-mobs-alive`
- `waves.mob-type` (vanilla EntityType; default ZOMBIE), `waves.fire-resistant-mobs`
- `drops.emerald-chance` (0.0–1.0, default 0.4), `drops.emerald-base`, `drops.emerald-waves-per-extra`
  — amount = `emerald-base + (wave - 1) / emerald-waves-per-extra`
- `announce.wave-start`, `announce.wave-cleared`, `announce.villager-death`

The vanilla wave spawner is intentionally a placeholder — MythicMobs integration and the rest of the
feature set are planned in `plans/villagedefense-phases.md`.
