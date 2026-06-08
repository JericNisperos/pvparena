# CyanGuildWar

Global guild-vs-guild 1v1 matchmaking. Players queue with `/cyangpa guildwar`; two queued players
from **different** guilds are auto-joined into a random free `guildwar*` arena. Winner's guild gets
`+1 win`, loser's guild `+1 loss` and is **locked out until 00:00 (GMT+8)**.

## Requirements
- `UltimateClans` installed and enabled (the guild system).
- `pa_m_cyanguildwar.jar` in `plugins/PVPArena/modules/`.
- At least one arena whose name starts with `guildwar` (e.g. `guildwar1`, `guildwar2`).

## Setup
1. Drop the jar in `plugins/PVPArena/modules/` and restart (or `/pa reloadall`).
   - Do **not** add the module to any arena — it runs globally on its own.
2. Build each `guildwar*` arena as a normal joinable arena:
   - a **2-team goal**, 1 slot per team (`TeamLives` or `TeamDeathMatch` recommended);
   - spawns for **both** teams;
   - a join module enabled (the standard one any arena already has).
3. (Optional) Edit `plugins/PVPArena/cyan_guildwar_config.yml` (auto-created):
   - `queue-timeout-seconds` (default 360)
   - `arena-prefix` (default `guildwar`)
   - `timezone` (default `Asia/Singapore`)
   - `announce-globally` (default false)

## Commands
- `/cyangpa guildwar` — join the queue (run again to leave).
- `/cyangpa guildwar top [n]` — guild leaderboard.
- Alias: `gw` (e.g. `/cyangpa gw`).
- Permission: `pvparena.cmds.guildwar`.

## DO
- Make `guildwar*` arenas **queue-only** — let players in via the command, not by walking in.
- Use one team-slot per side so the 1v1 winner is unambiguous.
- Keep multiple `guildwar*` arenas if you expect concurrent matches.
- Treat the daily reset as **00:00 in the configured timezone**.

## DON'T
- Don't add `CyanGuildWar` as an arena module — it's global.
- Don't use FFA / 1-team goals for `guildwar*` arenas (no clear winner side).
- Don't `/pa <guildwarX> join` manually — walk-up joins to `guildwar*` are blocked by design.
- Don't expect the queue to survive a restart (it's in-memory; scores/lockouts persist).

## Good to know
- **Mid-fight quit / leave / disconnect = a loss** for that player's guild.
- A **genuine draw** (timed end, both alive, no winner) = no-contest (no score, no lockout).
- A guild is locked **only after a loss**; wins are unlimited that day.
- Lockout is **per guild** — leaving the guild to dodge it is a known edge case.
