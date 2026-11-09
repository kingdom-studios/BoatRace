# BoatRace

F1-style boat racing: start lights, false starts, invisible checkpoints, deltas to personal/track records, SQLite leaderboards, PlaceholderAPI support.

## Building

```bash
mvn clean package

```

Output: `boatrace/target/BoatRace-1.0.0.jar` (Foundation compiled and relocated to `pl.kingdomcraft.boatrace.lib`).

* Foundation is built from **local source code** in `lib/foundation` (Maven module) — it is not downloaded as a pre-built dependency.
* Maven still downloads the **Paper API** and plugin APIs referenced by Foundation's code (repositories specified in pom). Requires JDK 25+ (Paper 26.x API classes are compiled for Java 25).
* If `paper.version` (parent pom) fails to resolve, enter the exact version from repo.papermc.io. If the Foundation module fails to compile on API 26.2, set the version from the original pom (26.3) there.

## Commands

Main command: `/boatrace`, alias **`/bre`** (not `/br`, which collides with WorldEdit).

Player: `/bre join random`, `/bre join <arena>`, `/bre leave` (= HUB item), `/bre reset` (= RESET item), `/bre list`

Sending other players (`boatrace.join.others`, also from the console / NPCs): `/bre join random <player>`, `/bre join <arena> <player>`.

Joining is silent - the player just appears in a boat on the grid. RESET / HUB work only through the hotbar items or the commands (no clickable chat buttons).

Admin (`boatrace.admin`):

| Command | Description |
| --- | --- |
| `wand` | Gives the selection wand (golden axe). Left click a block = pos1, right click = pos2. |
| `pos1` / `pos2` | Sets a corner to the block you stand in (handy on water / in the air). |
| `create <arena>` | Creates an arena. |
| `setspawn <arena>` | Boat spawn (your position + facing). Must be a few blocks **before** the start line. |
| `setstart <arena>` | Start line = current selection (also the finish line unless `setfinish` is used). |
| `setfinish <arena> [clear]` | Separate finish line = current selection; `clear` = use the start line again. |
| `addcheckpoint <arena>` | Adds the selection as the next invisible checkpoint zone (order matters). |
| `delcheckpoint <arena> <number\|all>` | Removes checkpoints. |
| `lights <arena> add\|set\|clear` | F1 lamps. `add` = selection (or looked-at block) becomes the next lamp; `set` = splits the selection into `start-lights.lamps` lamps, lamp #1 at pos1. |
| `reward <arena> <once\|always\|none> [command]` | Finish reward (see below). Also `reward <arena> clear\|info`. |
| `info <arena>` | Shows the arena setup. |
| `admin time add\|remove <player> <time> [arena]`, `admin time reset <player> [arena]`, `admin blacklist <player>` | Time / ban management. |
| `reload` | Reloads config.yml and arenas.yml. |

Time format: `12.5` / `12.5s` = seconds, `1500ms` = milliseconds.

An arena is ready once it has a spawn, a start line and at least one checkpoint.

### Race rules

* Start, finish and checkpoints are 3D zones (cuboids). Detection is swept between ticks, so fast boats cannot skip a zone; a zone also counts up to 1.5 blocks above the selected blocks.
* A lap only counts when the race was started (lights out), the racer then drove through the start line and passed **100% of the checkpoints in order**. Respawning in front of a shared start/finish line can therefore never finish a race.
* Lamps start black, light up one by one (`lamp-on-block`) and turn black (`lamp-off-block`) at lights out - they stay black afterwards. Lamps are client-side only (the world is never modified).
* Jumpstart (moving or driving through the start line before lights out): dynamic penalty between `jumpstart-penalty.min-seconds` (4.5s, all lamps lit) and `max-seconds` (12.5s, no lamp lit yet), linear by the number of lit lamps. RESET (or leaving the boat) starts a completely fresh attempt without the penalty.
* The action bar is refreshed every tick and shows only the time and the delta to your personal best: `Time: 01:23.456 (+1.23s)`. The delta appears after the first checkpoint and stays until the next one replaces it. Checkpoints themselves show nothing.
* The only chat message a racer receives is the final result. After the finish the result stays on screen for `finish-teleport-delay-ticks` (default 60 = 3 s), then the boat is removed and the player is sent to the hub.

### Rewards

`/bre reward <arena> once eco give {player} 500` - runs the command from the console after the **first** finished race of each player on that arena (tracked in the database). `always` = after every finish, `none` = disabled. Every call with a command adds one more command. Placeholders: `{player}`, `{uuid}`, `{arena}`, `{time}`. Banned players get no rewards.

### Upgrading from older versions

Old point-based checkpoints and finish are migrated to 7x3x7 zones. The old `start-block` lamp row is not migrated: set the lamps again with `/bre lights`, and set the start line with `/bre setstart`.

## Messages

Every text (chat, action bar, titles, item names) is in `plugins/BoatRace/messages.yml`, including the chat `prefix`, which is added to chat messages automatically. Missing keys fall back to the built-in defaults. Use `{delta_padded}` instead of `{delta}` in `delta.faster` / `delta.slower` for a padded delta (`+01.23s`). Command help/usage lines are generated by Foundation and are not part of messages.yml.

## Placeholders

`%boatrace_besttime_<arena>%`, `%boatrace_top_player_<arena>_<pos>%`, `%boatrace_top_time_<arena>_<pos>%`

Foundation hooks into PAPI and only returns values when the request includes an **online player** (standard holograms/scoreboard plugins do; `/papi parse --null` does not).

## Data Files

`config.yml` (settings), `messages.yml` (all texts), `arenas.yml` (arenas), `database.db` (SQLite: records + splits + blacklist + claimed one-time rewards), `snapshots/` (player inventory backups taken during races, restored even after a server crash).

## License

Apache License 2.0, see LICENSE.