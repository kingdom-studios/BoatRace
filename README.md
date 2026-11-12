# BoatRace

F1-style boat racing: start lights, false starts, invisible checkpoints, deltas to personal/track records, MariaDB leaderboards, ranking (ELO), casual and competitive team races, CaptainHook integration, PlaceholderAPI support.

## Building

```bash
mvn clean package

```

Output: `boatrace/target/BoatRace-1.0.0.jar` (Foundation, HikariCP and the MariaDB driver are shaded and relocated to `pl.kingdomcraft.boatrace.lib`).

* Foundation is built from **local source code** in `lib/foundation` (Maven module) — it is not downloaded as a pre-built dependency.
* Maven still downloads the **Paper API** and plugin APIs referenced by Foundation's code (repositories specified in pom). Requires JDK 25+ (Paper 26.x API classes are compiled for Java 25).
* If `paper.version` (parent pom) fails to resolve, enter the exact version from repo.papermc.io. If the Foundation module fails to compile on API 26.2, set the version from the original pom (26.3) there.

## Commands

Main command: `/boatrace`, alias **`/bre`** (not `/br`, which collides with WorldEdit).

Player: `/bre gui` (map menu), `/bre join random`, `/bre join <arena>`, `/bre leave` (= HUB item), `/bre reset` (= RESET item), `/bre list`

Sending other players (`boatrace.join.others`, also from the console / NPCs): `/bre join random <player>`, `/bre join <arena> <player>`.

Joining is silent - the player just appears in a boat on the grid. RESET / HUB work only through the hotbar items or the commands (no clickable chat buttons).

Admin (`boatrace.admin`):

| Command | Description |
| --- | --- |
| `wand` | Gives the selection wand (golden axe). Left click a block = pos1, right click = pos2. |
| `pos1` / `pos2` | Sets a corner to the block you stand in (handy on water / in the air). |
| `create <arena>` | Creates an arena. |
| `setspawn <arena> [index]` | Grid position #index (1, 2, 3 ... unlimited, default 1) = your position + facing. Must be a few blocks **before** the start line. Solo races always start on #1, event racers get the next free position. |
| `delspawn <arena> <index\|list>` | Removes / lists grid positions. |
| `setstart <arena>` | Replaces the whole start line with the current selection (also the finish line unless `setfinish` is used). |
| `addstart <arena>` | Adds the current selection as the next segment of the start/finish line - curved, slanted or arc-shaped lines are built from several segments; entering ANY segment counts as crossing the line. |
| `removestart <arena> <index\|all\|list>` | Removes a start/finish line segment by index (the following ones move up), all of them, or lists them. |
| `setfinish <arena> [clear]` | Separate finish line = current selection; `clear` = use the start line again. |
| `addcheckpoint <arena>` | Adds the selection as the next invisible checkpoint zone (order matters). |
| `delcheckpoint <arena> <number\|all>` | Removes checkpoints. |
| `lights <arena> add\|set\|clear` | F1 lamps. `add` = selection (or looked-at block) becomes the next lamp; `set` = splits the selection into `start-lights.lamps` lamps, lamp #1 at pos1. |
| `laps <arena> <count>` | Laps of a race on the arena (default 1). |
| `trackblocks <arena> add\|remove [material]`, `trackblocks <arena> list\|clear` | Blocks the boat must stay on (no material = the block you look at). |
| `event <arena> open [laps]\|start [laps]\|close\|status`, `event start <arena> [laps]` | Event mode (multiplayer start). |
| `start <arena> [laps]` | Starts the lights of the event lobby, optionally with another lap count. |
| `reward <arena> <once\|always\|none> [command]` | Finish reward (see below). Also `reward <arena> clear\|info`. |
| `info <arena>` | Shows the arena setup. |
| `admin time add\|remove <player> <time> [arena]`, `admin time reset <player> [arena]`, `admin blacklist <player>` | Time / ban management. |
| `gui <player>` | Opens the map menu for another player (NPCs, hub signs, console). `/bre gui` without a name is for everybody. |
| `spectate <player\|arena\|off>` | Spectator mode attached to a racer, or to a racer on that map; `off` returns you. |
| `maintenance <arena> <true\|false>` | Closes / opens a map for players: it stays in the menu but disabled (`gui.yml` `maintenance:`), is never picked by random, a join shows `race.maintenance`, its queue is cancelled. Races already running finish; admins (`boatrace.admin`) can still join with `/bre join <arena>`. Also shown in `list` / `info`. |
| `tracks` | Track ids for the website stats (`fastest_<track>`) -> arena; warns about duplicates. |
| `ranked <arena\|random> [player]`, `ranked leave\|list` | Competitive lobby (everybody; `[player]` needs `boatrace.join.others`). Aliases `competitive`, `comp`. |
| `compset <arena> on\|off\|min <n>\|max <n>\|reset\|info` | Competitive settings of an arena (min = countdown starts, max = start at once, never above the grid positions). |
| `reload` | Reloads config.yml, messages.yml, gui.yml and arenas.yml. |

Time format: `12.5` / `12.5s` = seconds, `1500ms` = milliseconds.

An arena is ready once it has a spawn, a start line and at least one checkpoint.

### Race rules

* Start, finish and checkpoints are 3D zones (cuboids); the start/finish line may consist of several segments (`start-line.1`, `start-line.2` ... in arenas.yml). Detection is swept between ticks, so fast boats cannot skip a zone; a zone also counts up to 1.5 blocks above the selected blocks.
* A lap only counts when the race was started (lights out), the racer then drove through the start line and passed **100% of the checkpoints in order**. Respawning in front of a shared start/finish line can therefore never finish a race.
* Lamps start black, light up one by one (`lamp-on-block`) and turn black (`lamp-off-block`) at lights out - they stay black afterwards. Lamps are client-side only (the world is never modified).
* Jumpstart (moving or driving through the start line before lights out): dynamic penalty between `jumpstart-penalty.min-seconds` (4.5s, all lamps lit) and `max-seconds` (12.5s, no lamp lit yet), linear by the number of lit lamps. RESET (or leaving the boat) starts a completely fresh attempt without the penalty.
* The action bar is refreshed every tick and shows only the time and the delta to your personal best: `Time: 01:23.456 (+1.23s)`. The delta appears after the first checkpoint and stays until the next one replaces it. Checkpoints themselves show nothing.
* The only chat message a racer receives is the final result. After the finish the result stays on screen for `finish-teleport-delay-ticks` (default 60 = 3 s), then the boat is removed and the player is sent to the hub.

### Events (multiplayer start)

1. `/bre event <arena> open` - opens a lobby on a free instance of the arena.
2. Players join with `/bre join <arena>` (or are sent with `/bre join <arena> <player>`) and take grid positions #1, #2, #3 ... in join order.
3. `/bre start <arena> [laps]` starts the F1 lights for everybody at once - or automatically when every grid position is taken (`event.auto-start-when-full`).
4. In an event RESET / leaving the boat puts the boat back (grid / last on-track position) without restarting the clock. Results include the finishing position (`{event_note}`).

### Arena instances and fallback maps

An arena instance (arena + world) is used by one solo racer or one event at a time. When it is busy, the player is sent to an identical copy of the track in another world (same coordinates), configured in `config.yml` under `fallback.maps.<arena>`. If all copies are busy and `fallback.random-free-arena` is on, another random free arena is used. Records and rewards are shared between copies (same arena name).

### Laps

`/bre laps <arena> <count>` (or `laps:` in arenas.yml); events can override it with `/bre event <arena> open <laps>` / `/bre start <arena> <laps>`. Crossing the start/finish line with 100% of the checkpoints of the lap counts the lap (title `new-lap` / `final-lap`, sound `lap`); the race ends after the last lap. The action bar shows `Lap 2/5 | Time: ...` (`actionbar.lap` / `{lap_text}`).

**Times in multi-lap races (laps > 1):**
* The delta to your personal best exists in the **first lap only**; from the second lap on it is neither calculated nor shown.
* Personal bests (and the leaderboards / placeholders built on them) are those of the standard **single-lap** race. A multi-lap result never becomes a personal best and is not compared with one.
* Only the **total time** of the whole multi-lap race is saved - to the table `boatrace_event_results` (see Database). A race on an arena that is configured for several laps can still be started with 1 lap in the menu (laps selection) or `/bre start <arena> 1`.

> Existing `messages.yml` files keep their own action bar lines - add `{lap_text}` to them (or delete the file to get the new defaults).

### Start signal and off-track detection

* The start signal is the F1 lamp structure itself: the lamp blocks light up red during the countdown, turn into `start-signal.go-block` at lights out and after `duration-seconds` (default 5) into `off-block` (empty = the real block, e.g. AIR or BLACK_CONCRETE). `mode: CLIENT` only shows it to the racers; `mode: WORLD` changes the real blocks (always restored on reset / shutdown). `enabled: false` = the lamps just go dark.
* After the start line, the block under the boat is checked every `off-track.check-interval-ticks`. Arenas use their own `trackblocks`, or `off-track.default-track-blocks` (empty = disabled). After `grace-ticks` off the track the `action` is applied: `PENALTY` (+`penalty-seconds`), `TELEPORT_BACK` (last on-track position + penalty) or `RESET` (restart; in events same as TELEPORT_BACK). Warning texts: `title.off-track*` / `race.off-track*` in messages.yml.

### Rewards

`/bre reward <arena> once eco give {player} 500` - runs the command from the console after the **first** finished race of each player on that arena (tracked in the database). `always` = after every finish, `none` = disabled. Every call with a command adds one more command. Placeholders: `{player}`, `{uuid}`, `{arena}`, `{time}`. Banned players get no rewards.

### Upgrading from older versions

**Automatic file update (on start and `/bre reload`)** – `config.yml`, `messages.yml`, `gui.yml`: keys of the jar that your file is missing are added (with their default value and comment) inside their own section. Your values are never changed, unused keys stay. A copy is saved first: `plugins/BoatRace/backups/<file>.<date>.bak`; the log lists what was added. Collections (`fallback.maps`, `ranking.xp-multipliers`, `competitive.colors`, gui `maps`, `items`, `competitive-buttons`) are only added as a whole when missing - never filled up with examples. A file with a YAML error is not touched. Saving normalizes the formatting (quotes, `[a, b]` lists → bullet lists, comment alignment) - behavior unchanged. `arenas.yml` and the CaptainHook files on the proxy are never touched.

Old point-based checkpoints and finish are migrated to 7x3x7 zones. The old `start-block` lamp row is not migrated: set the lamps again with `/bre lights`, and set the start line with `/bre setstart`.

## Database (MariaDB)

All data lives in the shared MariaDB database configured under `Server` / `Database` in `config.yml` (HikariCP pool: `Pool_Size`, `Connection_Timeout_Millis`, `Max_Lifetime_Millis`, `Keepalive_Millis`). Changes need a restart.

| Table | Content |
| --- | --- |
| `boatrace_records` | Personal best per (arena, player) of the standard **single-lap** race, with splits and `server_name` (= `Server.Name` of the server it was driven on). A record is only ever replaced by a faster time, decided by the database, so several servers can write at once. Multi-lap races never touch it. |
| `boatrace_runs` | Every finished single-lap race: time, penalty, splits, `personal_best`, `server_name`, `server_group`, `finished_at` (epoch ms) - for a web panel. |
| `boatrace_event_results` | The total time of every finished **multi-lap** race (laps > 1): `arena`, `laps`, `uuid`, `name`, `total_ms`, `penalty_ms`, `splits`, `finish_position` / `field_size` (event or party, 0 / 1 for solo), `server_name`, `server_group`, `finished_at`. The table for event leaderboards. |
| `boatrace_blacklist` | Banned players (network wide). |
| `boatrace_rewards` | Claimed one-time rewards (network wide - a reward can never be given twice). |
| `boatrace_ratings` | Ranking per player (one for casual and competitive): `rating`, `games`, `wins`, `streak` (current "not last" run), `best_streak`, `comp_games`, `comp_wins`. Relative updates, safe across servers. |

The leaderboard cache is reloaded every `records.refresh-seconds` (default 30) so times from other servers appear. An old SQLite `database.db` is imported once on the first start and renamed to `database.db.migrated`. Leaderboards are shared per arena name across all servers. (Multi-lap rows that an earlier development version wrote to `boatrace_records` with `laps` > 1 are simply ignored.)

## Sounds

`Sounds:` in `config.yml`: `start-countdown` (each lamp, with `pitch-step`), `start-go`, `checkpoint`, `lap`, `finish`, `off-track`, `jumpstart-penalty`, `missed-checkpoint`. Each has `sound` (Bukkit Sound name or a sound key, `NONE` = silent), `volume`, `pitch`.

## Map menu (`/bre gui`)

Fully configured in `gui.yml`: title, rows, item materials / names / lore / slots, extra decoration items that do nothing (`items:`), a filler, the random-map and close buttons. Map items show the arena status (free / busy / event open), players racing / grid positions, the chosen laps, the queue length and whether you may play it.

* Every map needs the permission `boatrace.map.<arena name in lower case>` (`boatrace.map.*` = all maps). Without it a click does nothing but send `gui.no-permission` (messages.yml). `/bre join <arena>` ignores map permissions unless `map-permissions.enforce-on-join-command: true`.
* Left click a map: laps +1 (max 5), right click: laps -1 (min 1) - limits in `gui.yml` `laps:`. The map item shows the selection and the race is started with it. **Shift-click (or middle click) joins** - the clicks are configurable under `clicks:`.
* The random button joins a random map the player has the permission for (available to everybody).

## Party (`/party`)

Built-in, in-memory, independent of other plugins: `/party create`, `invite <player>` (**creates the party automatically** if you are in none - you become the leader), `join [player]` (**without a name** you join the party that invited you, as long as only one did; the leader's name is only needed when several players invited you at the same time), `leave` (the leader disbands it), `list`, `kick <player>`, `limit <number>` (leader; default / maximum in config.yml `party:`; invitations expire after `invite-timeout-seconds`).

When the **leader** joins a map (command or menu) the whole party goes along: if a member is already racing or banned, the leader is told who and nobody starts; otherwise everybody is seated on the grid positions 1, 2, 3 ... of a private event that starts its lights right away. The party needs at least as many grid positions as it has members. Members cannot pick a map themselves.

* **Random map** (`/bre join random`, the menu button) never proposes a map with fewer grid positions than the party has members; if no map fits, the leader is told (`party.no-map-fits`).
* **Finishing:** a party member who finishes is not teleported - they stay on the map (free to move around, the action bar shows how many are done) until the LAST member has finished. Then everybody gets the party leaderboard in chat (places 1, 2, 3 ... by time, penalties included, `finish.party-*` in messages.yml) and after `finish-teleport-delay-ticks` (default 3 s) the whole party goes to the hub at the same moment. A member who leaves early does not hold the others up.

## Queue

A busy map (no free instance, no fallback copy, no free alternative arena) puts the player - or the party when its leader joins - in the queue of that map (`queue.enabled`, texts in `queue.*`). The first in line starts automatically when the race on the map is over; `/bre leave` leaves the queue. A player who is still racing elsewhere keeps their place until they are free.

## Ranking, CaptainHook, website (summary)

**Ranking (ELO)** – `config.yml` `ranking:`, works without CaptainHook too.
* Counts: events / parties with at least `min-racers` (2) racers at lights out. Solo runs never.
* Order = duels: everybody beat everybody behind them. DNF = behind all finishers (who left first is last).
* Settled once nobody is racing any more. Reload / server stop = nobody loses anything.
* Zero-sum: arranged results between friends gain nothing (the points just go back and forth). Beating a weaker player ≈ nothing, losing to them hurts.
* `k-factor` 16 = points at stake per casual race (competitive: `competitive.k-factor` 48). Start 1000, minimum 100.
* `party-races: false` = party races do not count (if friends farm).

**"Not last" streak** – a ranked race finished not in last place = +1, last place / DNF = 0. Stored in the database (across servers and restarts).

**Season pass XP (CaptainHook)** – `captainhook.pass-xp` (finish / win / podium / personal best / per 250 blocks) × the rating multiplier (`ranking.xp-multipliers`: <900 ×0.5 … 1500+ ×2.0). Daily cap `daily-limit`. No active season = no XP (no errors).

**What is sent to CaptainHook** (game `formula-boat-racing`, FINISHED races only, 1 message per race, no zeros):

| stat | op | unit |
|---|---|---|
| `races`, `wins`, `podiums`, `laps` | add | count (wins / podiums only in ranked races) |
| `distance` | add | blocks |
| `race_time_ms` | add | ms without penalties (average speed = distance / time) |
| `fastest_lap`, `fastest_<track>` | min | ms, best lap |
| `fastest_race_<track>` | min | ms, whole race (only with the arena's own lap count) |
| `top_avg_speed` | max | blocks/s × 100 (5 b/s = 500) |
| `best_streak` | max | the CURRENT streak, sent after every ranked finish |
| `rating` | set | ranking points, separate message once the race is settled |

* `<track>` = arena name in lower case, characters other than `a-z0-9_` → `_`, max 19 characters. List: `/bre tracks`.
* Official times (penalties included). Speed and `race_time_ms` without penalties.
* Rejected as a whole (warning in the log): a lap < `min-lap-seconds` (5) or an average > `max-avg-speed` (75 b/s). Reason: a `min` / `max` stays in the database forever.
* Never change the meaning / unit of a key - the website relies on them. New meaning = new key.

**Challenges in CaptainHook** (proxy: `achievements.yml`, `missions.yml`, then `/captainhookproxy reload`):
* Achievement from a counter: `source: { type: minigame-stat, game: formula-boat-racing, stat: wins }`, `goal: 25`.
* Achievement from an "at least" record: `stat: top_avg_speed`, `goal: 500` (the bar grows with the best result).
* Achievement from an "under" time: `stat: fastest_race_<track>`, `at-most: 65000` (ms), goal 1.
* Mission from a counter: `objective: MINIGAME_STAT`, `filter: { game, stat: races }`, `target: 3`.
* Threshold mission ("reach X once"): `filter: { game, stat, min-value: N }`, `target: 1` – counts +1 for every report with a value ≥ N.
  * Average ≥ 5 b/s: `stat: top_avg_speed`, `min-value: 500`.
  * 3 races in a row without being last: `stat: best_streak`, `min-value: 3` (ready: `weekly-br-streak-3`).
  * Note: a streak from the previous week keeps counting (2 on Sunday + 1 on Monday = done).

**Holograms (PlaceholderAPI)** – see Placeholders below. HolographicDisplays: put the placeholder straight into the line, e.g. `#1 %boatrace_rating_top_player_1% %boatrace_rating_top_value_1%`.

**Known limits** – the ranking cache is refreshed every `records.refresh-seconds` (30 s): right after switching servers a player may briefly see old points (nothing is lost in the database). The daily XP cap is in memory (reset by a restart).

## Casual and Competitive (summary)

| | Casual | Competitive |
|---|---|---|
| What | solo, events, party races (everybody for themselves) | team against team, from a lobby |
| Ranking | shared `rating`, `ranking.k-factor` 16 | the same `rating`, `competitive.k-factor` 48 |
| XP | `captainhook.pass-xp` | × `xp-multiplier` (2.0) + win / full-team + lineup bonus |
| Armor / tag | no | yes |

**One rating per player** – parties are temporary, so there is no team ranking. Everybody plays for their own `rating`; a competitive match gives + for a team win, − for a weaker result.

**Enable** – `config.yml` `competitive.enabled: true` (off by default). GUI button: `gui.yml` `competitive-buttons` (`enabled: true`, a free slot).

**Limits per arena** – `/bre compset <arena> min 4` / `max 8` / `on|off` / `reset` / `info` (or `arenas.yml` `competitive: { enabled, min-players, max-players }`). Not set = `competitive.min-players` (4) and max = number of spawns. The spawns do not need to change.

**Joining** – `/bre ranked <arena|random>` / GUI button (second click = leave). Party leader = the whole team. Solo = a team of 1. Biggest team: `max-team-size` (4).
* The lobby takes teams in order while they fit (max racers, max teams = number of colors). The rest waits for the next match.
* ≥ min racers and ≥ 2 teams → countdown `countdown-seconds` (30). Full lobby → starts at once. Dropping below min → countdown cancelled.
* `random` lobby = the lowest min and the highest max of all arenas; the match goes to a free arena that fits the number of racers.
* Dropped from the lobby: leaving the server, party change, starting another race, ban.

**Match**
* Grid: the first racers of every team, then the second ones … Colors `competitive.colors` (8 by default), full leather armor, title + chat "Your team", LuckPerms tag (transient meta `boatrace_team`, in chat / TAB `%luckperms_meta_boatrace_team%`, without LP `%boatrace_team_tag%` - empty outside a match).
* Result: F1 points by place (`competitive.points`, DNF = 0), team = AVERAGE of its members (different sizes are comparable, the weakest member counts too). Tie → the best single place.
* Action bar: the live team score in front of the time (`actionbar.team`).
* Rating: the teams in standing order = one ELO race (team strength = average rating of its members), every member gets their team's change. Nobody finished → no change.

**Penalties** – leaving a running match (also during the start lights) = DNF + `-15` rating + `5 min` without competitive. Bypass: `boatrace.competitive.bypass` (op by default). Reload / server stop never punishes.

**Same lineup bonus** – the same team (exactly the same players) in the next match: +10% XP per match in a row, max +30%, a break > 30 min = starts again. In memory only: 1 small entry per player (lineup fingerprint, counter, time), no team stats; a restart resets it.

**CaptainHook** – the individual stats of every race + `comp_races`, `comp_wins`, `comp_full_team` (add, only those who finished), `rating` (set). New keys → tell the website team.

**Placeholders** – `%boatrace_team%`, `%boatrace_team_tag%`, `%boatrace_comp_games%`, `%boatrace_comp_wins%`; ranking as in casual (`%boatrace_rating...%`).

## Tab list on a dedicated server (summary)

`config.yml` `tablist.session-only: true` – a player only sees their own group in the tab list: the **lobby** or **their session** (arena instance: solo, event, party, competitive – also on the grid and after the finish). A spectator (`/bre spectate`) sees the session they watch.
* Tab list only (Paper `unlistPlayer`) – everybody stays visible in the world. Nothing is saved (crash = nothing left behind), switching it off / stopping the plugin lists everybody again.
* Refreshed every 0.5 s + when a player joins. Vanish of other plugins is left alone.
* `boatrace.tablist.bypass` = sees everybody (nobody by default, not even ops).
* TAB plugin (NEZNAMY): works together with it (formatting / sorting unchanged). Requirement: `layout.enabled: false` and `per-world-playerlist.enabled: false` in TAB.

## Messages

Every text (chat, action bar, titles, item names) is in `plugins/BoatRace/messages.yml`, including the chat `prefix`, which is added to chat messages automatically. Missing keys fall back to the built-in defaults. Use `{delta_padded}` instead of `{delta}` in `delta.faster` / `delta.slower` for a padded delta (`+01.23s`). Command help/usage lines are generated by Foundation and are not part of messages.yml.

## Placeholders

`%boatrace_besttime_<arena>%`, `%boatrace_top_player_<arena>_<pos>%`, `%boatrace_top_time_<arena>_<pos>%`, `%boatrace_top_server_<arena>_<pos>%`, `%boatrace_playing%` (players racing on all arenas), `%boatrace_playing_<arena>%` (players racing on that arena)

Ranking: `%boatrace_rating_top_player_<pos>%`, `%boatrace_rating_top_value_<pos>%` (leaderboard, needs `ranking.leaderboard-min-games` ranked races), and for the viewer `%boatrace_rating%`, `%boatrace_rating_rank%`, `%boatrace_rating_games%`, `%boatrace_rating_wins%`, `%boatrace_rating_streak%`, `%boatrace_rating_best_streak%`, `%boatrace_rating_multiplier%`.

Foundation hooks into PAPI and only returns values when the request includes an **online player** (standard holograms/scoreboard plugins do; `/papi parse --null` does not).

## Data Files

`config.yml` (settings), `messages.yml` (all texts), `gui.yml` (map menu), `arenas.yml` (arenas), MariaDB (records, runs history, blacklist, claimed one-time rewards), `snapshots/` (player inventory backups taken during races, restored even after a server crash).

## License

Apache License 2.0, see LICENSE.