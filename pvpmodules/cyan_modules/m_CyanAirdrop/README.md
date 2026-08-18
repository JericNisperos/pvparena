# CyanAirdrop

Supply drops at fixed coordinates, at fixed times, announced in advance.

At configured points into a match ("10 minutes in") a set of real items spawns at an admin-marked
spot. Countdown warnings go out beforehand, so both teams know when and where to be. Players pick
the items up into their inventory and use them normally.

Not a powerup — `CyanPowerups` cancels the pickup and grants a potion effect instead. Here the item
*is* the reward, so the pickup is left alone.

## Install

```
plugins/PVPArena/mods/pa_m_cyanairdrop.jar
/pa <arena> !tm Airdrop
```

## Setting up a drop

Drop points are captured in-game — a serialized ItemStack is not something anyone should be editing
in YAML.

| command | effect |
|---|---|
| `/pa <arena> !cad set <name> <seconds>` | the block you're standing on becomes the drop point for `<name>`, at `<seconds>` into the match |
| `/pa <arena> !cad item <name>` | append the item in your hand to that drop (repeat for more) |
| `/pa <arena> !cad remove <name>` | delete the drop |

`/pa <arena> !tm` lists every configured drop with its time, coordinates and item count.

Example — a diamond sword at the middle of the map, ten minutes in:

```
/pa myarena !cad set supply1 600
(hold a diamond sword)
/pa myarena !cad item supply1
```

## Config

Written into the arena's own `config.yml` on first load.

```yaml
modules:
  cyanairdrop:
    announce: true                  # master switch for all three messages below
    announceSeconds:                # "<seconds before the drop>:<message>"
      - "60:&fThe &eCrown of Dominion &fwill drop in the center in 60 seconds!"
      - "30:&fThe &eCrown of Dominion &fwill drop in the center in 30 seconds! Brace yourselves!"
      - "10:&fThe &eCrown of Dominion &fwill drop in the center in 10 seconds!"
    announceDrop: "&fThe &e[drop] &fhas dropped at &e[coords]&f!"
    announcePickup: "&fThe &e[drop] &fhas been picked up by &a[playername]&f!"
    drops:
      supply1:
        atSeconds: 600              # 10 minutes into the match
        location: world,100,64,200
        name: "&6Supply Drop"       # nameplate on the item + used in announcements
        items:                      # serialized ItemStacks — use '!cad item'
          - ==: org.bukkit.inventory.ItemStack
            v: 3337
            type: DIAMOND_SWORD
```

`atSeconds` is counted from the moment the fight starts. For a repeating drop, add a second entry —
there is no repeat interval.

### Messages

Each `announceSeconds` entry is `<seconds before the drop>:<message>`, so every warning says its own
thing. Placeholders, usable in all three messages:

| placeholder | becomes |
|---|---|
| `[drop]` | the drop's `name` |
| `[coords]` | `100, 64, 200` |
| `[seconds]` | time until the drop (`1m30s`, `30s`) |
| `[playername]` | who picked it up — `announcePickup` only |

`&` colour codes work everywhere. A message that contains a colon **followed by a space** must be
quoted, or YAML reads the entry as a mapping instead of a string:

```yaml
- "10:&fReady: go!"     # quoted — fine
- 10:&fReady: go!       # silently becomes a map; logged and skipped
```

A bare `- 60` (the pre-message format) still works and uses a built-in default line, so older arena
configs keep running untouched.

A drop with no items, no location or an unreadable location is skipped with a warning in the console;
the rest still run.

## Behaviour

- The dropped item glows (outline through walls), carries the drop's name as a floating label, and is
  invulnerable — a drop point in lava is still a drop point.
- Vanilla despawns loose items after 5 minutes; these are kept alive until the match ends, since an
  uncontested drop is exactly the one still worth fighting over.
- Anything not picked up is removed when the match ends or is aborted.

## Skipped deliberately

- **Particle beam** — coordinates are announced and the item glows through walls. Copy the beam from
  `CyanPowerups` if playtesting says drops are still hard to find.
- **Repeat interval** — add another drop entry.
- **Falling-from-the-sky animation** — the admin stands where they want it.
