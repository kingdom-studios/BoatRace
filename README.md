# BoatRace

F1-style boat racing: start lights, false starts, invisible checkpoints, deltas to personal/track records, SQLite leaderboards, PlaceholderAPI support.

## Building

```bash
mvn clean package

```

Output: `boatrace/target/BoatRace-1.0.0.jar` (Foundation compiled and relocated to `pl.kingdomcraft.boatrace.lib`).

* Foundation is built from **local source code** in `lib/foundation` (Maven module) — it is not downloaded as a pre-built dependency.
* Maven still downloads the **Paper API** and plugin APIs referenced by Foundation's code (repositories specified in pom). Requires JDK 21+.
* If `paper.version` (parent pom) fails to resolve, enter the exact version from repo.papermc.io. If the Foundation module fails to compile on API 26.2, set the version from the original pom (26.3) there.

## Commands

Player: `/br join [arena]`, `/br leave` (=[HUB]), `/br reset`, `/br list`

Admin (`boatrace.admin`): `create`, `setspawn`, `setstartblock`, `addcheckpoint`, `setfinish` `<arena>`,

`admin time add|remove <player> <time> [arena]`, `admin time reset <player> [arena]`, `admin blacklist <player>` (toggles), `reload`.

Time format: `12.5` / `12.5s` = seconds, `1500ms` = milliseconds.

* `setstartblock`: Look at the starting line block; a row of lamps will appear above it (config: `start-lights.height`), perpendicular to your viewing direction. Lamps are client-side only (the world state does not change).
* `addcheckpoint` / `setfinish`: Uses the block you are looking at (up to 30 blocks); if looking into the air, it uses your current position instead. Zone = a sphere with the radius specified in the config.

## Placeholders

`%boatrace_besttime_<arena>%`, `%boatrace_top_player_<arena>_<pos>%`, `%boatrace_top_time_<arena>_<pos>%`

Foundation hooks into PAPI and only returns values when the request includes an **online player** (standard holograms/scoreboard plugins do; `/papi parse --null` does not).

## Data Files

`config.yml` (settings), `arenas.yml` (arenas), `database.db` (SQLite: records + splits + blacklist), `snapshots/` (player inventory backups taken during races, restored even after a server crash).

## License

Apache License 2.0, see LICENSE.