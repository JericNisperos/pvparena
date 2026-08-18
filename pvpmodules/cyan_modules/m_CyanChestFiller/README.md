# CyanChestFiller

## About

Same idea as [ChestFiller](chestfiller.md) — randomly fills battlefield containers — with two differences:

- **Battlefield containers are detected automatically.** On the first game start the BATTLE region(s) are scanned, the
  result is saved to the arena config, and `autoDetect` switches itself off — so the scan happens once, not every game.
- **Multiple source containers** can be set. Their contents are merged into one pool to draw random items from.
- **Periodic refill** — set `refillSeconds` and the containers refill on that interval for the whole match, with an
  optional broadcast message each time.

Run either module, not both on the same arena, unless you want containers filled twice.

## Setup

You need a BATTLE region. It's a requirement.

Set what goes in the chests: either point at one or more source containers with `sourceLocation` (see below), or save
your own inventory with `/pa <arena> set modules.cyanchestfiller.items inventory`.

Containers to fill are found automatically in the BATTLE region(s). Use `addContainer` if you want to add one outside
the region.

## Config settings

- **modules.cyanchestfiller.items**: List of items to put in chests to fill. Uses [items syntax](../items.md).
  (default: 1 stone block)
- **modules.cyanchestfiller.maxItems**: maximum number of items to put in a chest. Clamped to 0–256. (default: 5)
- **modules.cyanchestfiller.minItems**: minimum number of items to put in a chest. Clamped to 0–maxItems. (default: 0)
- **modules.cyanchestfiller.sourceLocations**: List of container locations to read the items from. Contents of all of
  them are merged. Overrides `modules.cyanchestfiller.items`. (default: empty)
- **modules.cyanchestfiller.autoDetect**: if true, the next game start scans the BATTLE region(s) for containers, saves
  them to `containerList`, and sets this back to false. Set it to true again after changing the region or building new
  containers. (default: true)
- **modules.cyanchestfiller.chunksPerTick**: how many chunks the detection scan processes per tick. Lower = slower scan,
  smaller impact on TPS. Clamped to 1–64. (default: 4)
- **modules.cyanchestfiller.refillSeconds**: refill the containers every N seconds while the match runs. `0` disables
  it (fill on start only). (default: 0)
- **modules.cyanchestfiller.refillPerTick**: how many containers are filled per tick during a fill/refill, so a large
  arena doesn't populate all at once. Clamped to 1–128. (default: 8)
- **modules.cyanchestfiller.refillMessage**: message broadcast to the arena on each refill. Supports `&` color codes.
  Leave blank to refill silently. (default: `&aThe chests have been refilled!`)
- **modules.cyanchestfiller.clear**: if true, clears all chests to fill before filling them
- **modules.cyanchestfiller.containerList**: List of coordinates of containers to fill

## Commands

- `/pa <arena> !ccf sourceLocation` \- add the container you're looking at to the source list. Works with double chests.
- `/pa <arena> !ccf sourceLocation none` \- clear the source list, back to `modules.cyanchestfiller.items`
- `/pa <arena> !ccf addContainer` \- add the container you're looking at to the list of chests to be filled
- `/pa <arena> !ccf detect` \- scan the BATTLE region(s) now and add every container found to the list
- `/pa <arena> !ccf clear` \- clear the list of chests to be filled (and re-arm auto-detection)

<br>

> 🚩 **Tips on multiple sources:**
> * The pool is a flat merge of every source's items, so a source holding 20 stacks is drawn from 20x more often than a
>   source holding 1. Balance the stack counts, don't assume one source = one equal share.
> * Keep source containers **outside** the BATTLE region. A source inside it is reachable by players, and anything they
>   drop in becomes loot for every chest next game. Sources are never filled by this module, but nothing stops a player
>   from opening one.
> * Pointing at both halves of the same double chest only counts once — the duplicate is ignored.

<br>

> ⚙️ **Technical precision:**
> Detection walks the **tile entities** of the chunks the BATTLE region covers, not every block in it. Containers are
> always tile entities, so a chunk yields a handful of candidates instead of ~100k blocks. Ungenerated chunks are
> skipped. Enable PVPArena debug to see `scanned N chunks in Nms`.
>
> The scan is **batched** at `chunksPerTick` chunks per tick rather than run in one go. Spigot 1.18 has no async chunk
> loading, so a chunk that isn't already in memory costs a blocking disk read; budgeting them per tick turns one long
> freeze into a brief load phase. Chests fill when the scan finishes, so the very first game on a fresh arena may see
> its containers populate a second or two in. Every later game reads the saved list and fills immediately.
>
> The scan runs on the main thread by design — Bukkit world and block access is not thread-safe, so moving it to an
> async task would risk corrupting chunk data rather than fixing lag.
>
> **Refill cost:** both the initial fill and each refill are **batched** at `refillPerTick` containers per tick, so a
> large arena never populates in one burst. The refill message broadcasts once the whole batch finishes. If a refill
> cycle comes due while the previous batch is still draining (very short `refillSeconds` + many containers), that cycle
> is skipped rather than stacking work. Per-container fill counts are also bounded (`maxItems` ≤ 256), so no single
> container can freeze the server.
