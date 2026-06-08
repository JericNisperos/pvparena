# CyanGuildWarChallenge

Challenge-style guild wars. A guild member challenges another **online** guild to an NvN with
`/guildwar <enemyGuild> <count>`; an officer of the challenged guild accepts; both sides fill their
roster; a 10-second countdown runs once both are full; then the fight starts in a `guildwar*` arena.
Winner's guild gets `+1 win`, loser's `+1 loss`. **No daily lockout.**

This is separate from and independent of `CyanGuildWar` (the queue module). You can run either, both,
or neither.

## Requirements
- `UltimateClans` installed and enabled (the guild system).
- `pa_m_cyanguildwarchallenge.jar` in `plugins/PVPArena/modules/`.
- At least one arena whose name starts with `guildwar` (e.g. `guildwar1`, `guildwar2`).

## Setup
1. Drop the jar in `plugins/PVPArena/modules/` and restart (or `/pa reloadall`).
   - Do **not** add the module to any arena — it runs globally on its own.
2. Build each `guildwar*` arena as a normal joinable arena:
   - a **2-team goal** (`TeamLives` or `TeamDeathMatch` recommended);
   - spawns for **both** teams, with **enough slots per team for your largest `count`**;
   - a join module enabled (the standard one any arena already has).
3. (Optional) Edit `plugins/PVPArena/cyan_guildwarchallenge_config.yml` (auto-created):
   - `teleport-warning-seconds` (default 5) — the "teleporting in Ns" warning before players are pulled in
   - `lounge-countdown-seconds` (default 10) — the in-lounge countdown after teleport, before the fight
   - `accept-timeout-seconds` (default 60)
   - `staging-timeout-seconds` (default 120)
   - `arena-prefix` (default `guildwar`)
   - `min-count` (default 1) — floor on the per-side count a challenger may request (≥ 1, ≤ `max-count`)
   - `max-count` (default 10)
   - `cooldown-seconds` (default 0) — a guild must wait this long after issuing a challenge before
     issuing another (0 = no cooldown)
   - `accept-roles` (default `leader, viceleader, quartermaster, diplomat`) — clan roles allowed to
     accept / deny / cancel a challenge
   - `join-roles` (default empty) — clan roles allowed to join a roster; **empty = any member may join**
   - `role-fallback-allow-any-member` (default `true`) — if a player's clan role can't be read from
     UltimateClans, `true` treats any guild member as permitted; `false` denies when the role is unknown

## Commands
- `/guildwar <guild> <count>` — challenge an online guild (tab-complete guild + count).
- `/guildwar accept` / `deny` — respond to a challenge against your guild (**officers only**).
- `/guildwar join` / `leave` — join / leave your guild's roster during staging.
- `/guildwar cancel` — challenger (or an officer) cancels a pending/staging war.
- `/guildwar top [n]` — guild leaderboard.
- Short aliases (for players who can't click the chat buttons):
  `/gwaccept`, `/gwdeny`, `/gwjoin`, `/gwleave`.
- Permission: `pvparena.cmds.guildwar`.

The clickable `[Accept] [Deny]` / `[Join]` prompts run the short aliases above; a plain
"Can't click? Type …" hint is always shown beneath them.

## Roles
- **Accept / deny / cancel** require a clan role listed in `accept-roles` (default `leader`,
  `viceleader`, `quartermaster`, `diplomat`).
- **Join** is gated by `join-roles`. By default that list is empty, so any member may join once the
  challenge is accepted; set roles there to restrict who can fill a roster.
- If a player's role can't be read from UltimateClans, `role-fallback-allow-any-member` decides
  whether they're treated as a permitted member (default `true`) or denied.

## DO
- Size each `guildwar*` arena's team slots ≥ your intended max `count`.
- Keep multiple `guildwar*` arenas if you expect concurrent wars (each war claims one).
- Let players in via `/guildwar` only — walk-up `/pa <arena> join` is blocked by design.

## DON'T
- Don't add `CyanGuildWarChallenge` as an arena module — it's global.
- Don't use FFA / 1-team goals for `guildwar*` arenas (no clear winning side).
- Don't expect a war to survive a restart (in-memory; scores persist in
  `cyan_guildwarchallenge.yml`).

## Good to know
- **Players stay free in the world the whole time they're waiting** (challenge issued, waiting for
  accept, filling rosters). Nobody is pulled into the arena until **both sides are full** — then a
  short "teleporting in Ns" warning plays (still free), everyone is teleported into the lounge, an
  in-lounge "fight starts in Ns" countdown runs, and then the fight begins. This avoids the old
  "stuck in the lobby at your location" state.
- If the fight **fails to start** (you stay in the lounge), the arena isn't fully set up — it needs
  **both teams' battle spawns** and a **class or autoclass**. The module now detects this, cancels
  cleanly with a message, and logs a warning instead of stranding players.
- `count` must be between `min-count` and `min(online members of each guild, max-count)`;
  tab-completion only offers valid numbers.
- The challenger auto-occupies one slot on their side; the accepting officer occupies one on theirs.
- If anyone drops during the teleport warning it **stops** and that side is asked to refill.
- A win is recorded from the arena's normal result (`PAWinEvent`); an inconclusive end records no
  score. A single player leaving mid-fight does **not** end the war — the arena goal does.
- If a challenged guild has **no online officer**, nobody can accept and the challenge expires.
