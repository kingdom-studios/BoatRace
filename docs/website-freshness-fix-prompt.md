# Prompt for the website agent: leaderboard does not update / shows "No maps with results yet…"

Context: the Formula Boat Racing tab shows "No maps with results yet…" together with "Live · updated 5 min ago", and does not refresh after new races. Please find and fix the cause on the website side. The game plugin (BoatRace) and CaptainHook write data to the shared MariaDB; do not change their schema.

## Data the tab depends on
- `ch_minigame_stats` (game = `formula-boat-racing`; columns `uuid`, `stat`, `value`, `updated_at`). **`updated_at` is Unix SECONDS** (not ms, not DATETIME) – check the conversion.
  - Per map: `fastest_race_<track_id>` (best standard-lap race time, ms) and `fastest_<track_id>` (best single lap, ms).
  - Global stats that must NEVER be used for a per-map ranking: `fastest_lap`, `top_avg_speed`.
- `boatrace_tracks` (per game server and arena): `arena_key`, `track_id`, `display_name`, `laps`, `ready`, `competitive`, `maintenance`, `updated`. It maps `track_id` -> readable name. Always show `display_name`, never the id.

## Problems to check and fix
1. **Freshness watcher.** It currently watches only `MAX(updated_at)` of `ch_player_stats_total`, so it never fires for per-map stats. Make it watch:
   - `MAX(updated_at)` of `ch_minigame_stats` WHERE `game = 'formula-boat-racing'`,
   - `MAX(updated)` of `boatrace_tracks`.
   Invalidate caches / push a refresh when either changes. Cache TTL for this tab should be short (<= 30–60 s).
2. **"Updated X ago" label** must be derived from the real latest data timestamp (the max above), not from the time of the last page poll.
3. **Empty state is too silent.** The rule "hide maps without a `boatrace_tracks` row" currently hides everything when that table is empty, unreadable or not yet synced. Instead:
   - verify the DB user has `SELECT` on `boatrace_tracks` and that the query does not fail silently (log errors, do not swallow them);
   - if `boatrace_tracks` is empty/unreadable, fall back to listing tracks found in `fastest_race_*` stat keys with a humanised id (`Monaco_GP` -> `Monaco GP`) and log a warning;
   - if a map has a row but no results, show it with "no results yet" rather than hiding the whole tab;
   - when the list is empty, show the reason (e.g. "tracks table not synced yet") in logs and, for admins, in the UI.
4. **Server scoping.** `boatrace_tracks` has one row set per game server; if several servers exist, deduplicate by `track_id` (prefer the row with the newest `updated`).
5. **Stat key parsing.** Make sure `track_id` is extracted correctly when it contains `_` or `-` (strip only the known prefix `fastest_race_` / `fastest_`).

## Acceptance
- After a finished race (a player with a CaptainHook carrier online; reports can take up to ~5 min), the map appears/updates in the tab within one cache TTL without a manual restart.
- "Updated … ago" matches the newest row's timestamp.
- Per-map ranking never mixes maps; names are readable.
- Provide a short note on what the root cause was and how to verify with SQL, e.g.:
  `SELECT MAX(updated_at), COUNT(*) FROM ch_minigame_stats WHERE game='formula-boat-racing';`
  `SELECT track_id, display_name, updated FROM boatrace_tracks;`
