# Prompt for the website agent: rankings per map

> Hand this whole file to the agent that works on the website repository. It is self-contained.
> The BoatRace plugin (Minecraft side) already provides the data described here; the agent must NOT change the plugin or write to any `ch_*` / `boatrace_*` table.

---

## Task: BoatRace rankings on the website must be per map, with map names

### The problem

The BoatRace ranking of best times on the website mixes results from ALL maps. The maps differ a lot in length and difficulty (one is driven in 30 seconds, another needs 5 minutes or more), so a mixed ranking is meaningless: the shortest map always wins.

Required: **every time-based ranking is calculated separately for each map, and a map is always shown by its readable name - never by its technical id.** Statistics of two different maps must never be merged, compared or sorted together.

### Data you can use (same MariaDB as the rest of the website - READ ONLY)

#### 1. `ch_minigame_stats` (CaptainHook) - game `formula-boat-racing`

Columns: `game`, `uuid`, `stat`, `value`, `updated_at`. Primary key (`game`, `uuid`, `stat`), index (`game`, `stat`, `value`). Player names: `ch_kingdomcoins.name` (by `uuid`). Every filter must use `game = 'formula-boat-racing'`.

| stat | meaning | allowed in a map ranking? |
|---|---|---|
| `fastest_race_<track_id>` | best time of a WHOLE race on that map, in ms (penalties included; lower = better). Only races with the map's own lap count (`boatrace_tracks.laps`) are counted. | **YES - the main ranking of a map** |
| `fastest_<track_id>` | best single lap on that map, in ms (lower = better) | **YES - secondary ranking ("best lap")** |
| `fastest_lap` | best lap over ALL maps | **NO - this is what mixes the maps. Never use it in a ranking.** |
| `top_avg_speed` | highest average speed over ALL maps (blocks/s x 100) | **NO** - mixes maps. There is no per-map average speed in the data. |
| `races`, `wins`, `podiums`, `laps`, `distance`, `race_time_ms` | player totals over all maps (counters) | OK as plain totals, never as "best times". There are NO per-map win / race counts in this data. |
| `best_streak`, `rating` | races in a row without finishing last; ELO ranking points | OK globally (they do not depend on the length of a map) |

`<track_id>` is the part after the prefix: `fastest_race_monaco_gp` -> `monaco_gp`. It is lower case, only `a-z 0-9 _`, at most 19 characters. It is a technical id and must NOT be shown to users.

#### 2. `boatrace_tracks` - which id is which map (NEW table, written by the plugin)

```sql
CREATE TABLE `boatrace_tracks` (
  `server_name` varchar(64) NOT NULL,       -- the game server that owns the row
  `arena_key` varchar(64) NOT NULL,         -- arena name in lower case (= `arena` column of boatrace_records / _runs / _event_results)
  `arena` varchar(64) NOT NULL,             -- arena name as typed in the game
  `track_id` varchar(32) NOT NULL,          -- the id used in ch_minigame_stats (fastest_race_<track_id>, fastest_<track_id>)
  `display_name` varchar(64) NOT NULL,      -- THE NAME TO SHOW, e.g. "Monaco Grand Prix"
  `laps` int(11) NOT NULL DEFAULT 1,        -- laps of a standard race on this map
  `ready` tinyint(1) NOT NULL DEFAULT 0,    -- the map is fully set up
  `competitive` tinyint(1) NOT NULL DEFAULT 1,
  `maintenance` tinyint(1) NOT NULL DEFAULT 0,  -- closed for players right now
  `updated` bigint(20) NOT NULL,            -- epoch ms of the last write
  PRIMARY KEY (`server_name`,`arena_key`),
  KEY `idx_tracks_track_id` (`track_id`),
  KEY `idx_tracks_arena_key` (`arena_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

* One row per map per game server. The same map can exist on several servers: take one row per `track_id` (the one with the highest `updated`).
* The plugin keeps it current: at server start, after every arena change and after `/bre reload`. Maps that were deleted disappear from it.
* **Add this table to the schema fixture of the tests** (like `backend/tests/fixtures/captainhook-schema.sql`).
* Until the updated plugin has been started once on the game servers the table does not exist / is empty - handle that (see rules).

#### 3. Optional - BoatRace's own tables (same database), always per map via `arena`

* `boatrace_records(arena, laps, uuid, name, best_ms, splits, server_name, updated)` - personal best of the standard SINGLE-lap race, per player and map (`laps` is always 1).
* `boatrace_runs(arena, uuid, laps, name, time_ms, penalty_ms, personal_best, server_name, finished_at ...)` - every finished single-lap race.
* `boatrace_event_results(arena, laps, uuid, name, total_ms, penalty_ms, finish_position, field_size, server_name, finished_at ...)` - the TOTAL time of every multi-lap race. Players can choose 2-5 laps, so group by (`arena`, `laps`) - a 5-lap time is never comparable to a 2-lap time.
* `arena` here = `boatrace_tracks.arena_key`. Prefer section 1 for the ranking; use these only if you need history / more detail.

### Rules (must hold everywhere: SQL, API, frontend, caches)

1. A ranking of times contains the results of **exactly one map**. No `MIN(value)` over several `fastest_%` stats, no `stat = 'fastest_lap'`, no `top_avg_speed` ranking, no query that ignores the map.
2. A map is shown as `display_name` (plus e.g. "3 laps" from `laps`). **Never show `track_id`, `arena_key` or a stat key to the user** (the id may appear in URLs / API paths). A `track_id` without a row in `boatrace_tracks` is NOT shown (log a warning), and never replaced by a prettified id.
3. A `track_id` that belongs to more than one different `arena_key` in `boatrace_tracks` is ambiguous (its stats are mixed): do not show that map, log an error. Also never show a map whose id is `lap` or starts with `race_` (it would collide with `fastest_lap` / `fastest_race_*`).
4. Never put user input into SQL. A `track_id` from the URL must match `^[a-z0-9_]{1,19}$` AND exist in `boatrace_tracks`, then use it as a bound parameter.
5. Ranking order: `value` ascending, ties by earlier `updated_at`, then by name. Times are milliseconds; show them as `m:ss.mmm`.
6. Use the language / i18n mechanism the website already has; do not hard-code texts.
7. The database access of this task is read only. Do not touch the plugin or other tables.

### What to do

**A. Audit first (before changing anything).** Find every place that builds a BoatRace ranking: backend queries, API routes, caches, frontend pages. List which of them mix maps (anything using `fastest_lap`, `top_avg_speed`, or `fastest_%` without a map). Put the list at the top of your report.

**B. Backend**
* `GET /boatrace/tracks` -> the maps that have at least one result, each `{ trackId, name, laps, players, bestMs }`, sorted by `name`.
* `GET /boatrace/tracks/{trackId}/leaderboard?type=race|lap&limit=&offset=` -> that map only: `type=race` uses `fastest_race_<trackId>`, `type=lap` uses `fastest_<trackId>`. Each row: rank, player name, time.
* Remove / replace every mixed ranking from the audit. Total counters (`races`, `wins`, ...) and `rating` may stay as global rankings, clearly not labelled as "best time".

**B-SQL (reference, adapt to the project's DB layer)**

```sql
-- maps that have results, with their names (one row per track_id)
SELECT t.track_id, t.display_name, t.laps, st.players, st.best_ms
FROM (
  SELECT track_id, display_name, laps,
         ROW_NUMBER() OVER (PARTITION BY track_id ORDER BY updated DESC) AS rn
  FROM boatrace_tracks
) t
JOIN (
  SELECT SUBSTRING(stat, 14) AS track_id, COUNT(*) AS players, MIN(value) AS best_ms
  FROM ch_minigame_stats
  WHERE game = 'formula-boat-racing' AND stat LIKE 'fastest\_race\_%'
  GROUP BY SUBSTRING(stat, 14)
) st ON st.track_id = t.track_id
WHERE t.rn = 1
ORDER BY t.display_name;

-- leaderboard of ONE map (race time); for best laps use CONCAT('fastest_', ?)
SELECT k.name, s.value AS time_ms, s.updated_at
FROM ch_minigame_stats s
JOIN ch_kingdomcoins k ON k.uuid = s.uuid
WHERE s.game = 'formula-boat-racing' AND s.stat = CONCAT('fastest_race_', ?)   -- ? = validated track_id
ORDER BY s.value ASC, s.updated_at ASC, k.name ASC
LIMIT ? OFFSET ?;

-- ambiguous ids (rule 3)
SELECT track_id FROM boatrace_tracks GROUP BY track_id HAVING COUNT(DISTINCT arena_key) > 1;
```
(`'fastest_race_'` is 13 characters, hence `SUBSTRING(stat, 14)`. Check the real type of `updated_at` before using it.)

**C. Frontend**
* The BoatRace ranking page gets a map selector (tabs or a dropdown) listing `display_name` (+ laps). Remember the selected map in the URL (`trackId` is fine there). A sensible default is the map with the most players.
* Show the main ranking (race time) and a second tab / section "best lap". Show clearly which map and how many laps the table is about.
* Empty states: no maps yet; a map without results.

**D. Tests**
* Fixtures: add `boatrace_tracks`; seed two maps - one with times around 30 s, one around 5 min - with different players and some players on both.
* Assert: the ranking of the 30 s map contains no 5 min time and vice versa; a player on both maps appears in both with the right time for each; the map list shows names, not ids; a `track_id` without a `boatrace_tracks` row is not listed; an ambiguous id is not listed; `fastest_lap` / `top_avg_speed` are never used by a ranking endpoint; invalid `trackId` (`x' OR 1=1`, too long, unknown) -> 404/400, no SQL error.

**E. Report back** (short): the audit list, what you changed, how to verify on the live data, and anything the data cannot do. Known limits to mention to the owner, not to work around: per-map win counts and per-map average speed do not exist in the data.

### Notes for the owner (do not implement)
* The game servers must run the updated plugin once, so `boatrace_tracks` exists and is filled. Check with `SELECT * FROM boatrace_tracks;`.
* Readable names are set in the game: `/bre displayname <arena> <name>`; without it the name is the arena name with `_` / `-` shown as spaces.
* If an admin changes the lap count of a map that already has times (`/bre laps`), the old times stay in the stat under the old definition - reset them or create a new map.
