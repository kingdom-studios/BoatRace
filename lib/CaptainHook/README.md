# Captain Hook

The core plugin of the KingdomCraft network, in two parts that are installed together:

| Module | Runs on | Jar | Role |
|---|---|---|---|
| `velocity/` | the Velocity proxy | `CaptainHook-Velocity-<version>.jar` | **Master.** Owns the only database connection and everything network-wide: player stats, KingdomCoins, the minigame API, achievements and the season pass. |
| `bukkit/` | every Paper server | `CaptainHook-Bukkit-<version>.jar` | **Client** (built on MineAcademy Foundation). Collects stats, runs `/link` and `/kc`, serves placeholders and offers `CaptainHookAPI` to other plugins. It has no database. |

The two talk over the `captainhook:main` plugin messaging channel. The protocol is specified in
`docs/NETWORK_PROTOCOL.md` of the website repository. The website integration (API, tables, queries) is
described in `docs/brief-dla-strony.md`.

```
 Paper servers (Bukkit module)                     Velocity proxy (Velocity module)
 ┌──────────────────────────────┐  plugin messages  ┌───────────────────────────────┐          ┌──────────────┐
 │ stats snapshots, /kc, /link  │ ───────────────▶  │ stats · KingdomCoins          │  JDBC    │ MySQL        │
 │ CaptainHookAPI for plugins   │  captainhook:main │ minigames · achievements      │ ───────▶ │ (ch_* tables)│ ◀── website reads
 │ placeholders, player messages│ ◀───────────────  │ season pass                   │          └──────────────┘
 └──────────────────────────────┘                   └───────────────────────────────┘
```

## Build and install

1. `mvn package` in this folder builds both jars (`velocity/target/` and `bukkit/target/`) and runs the tests.
2. Put `CaptainHook-Velocity-<version>.jar` into the proxy's `plugins/` and `CaptainHook-Bukkit-<version>.jar`
   into `plugins/` of every Paper server (SuperVanish, PremiumVanish, Vault and PlaceholderAPI are optional; any vanish plugin that sets the "vanished" metadata, e.g. EssentialsX or CMI, hides players on the website).
   **Always deploy the same version to both**: they must speak the same protocol version.
3. Proxy: set `database.mysql.*` in `plugins/captainhook/config.yml`, then restart the proxy.
4. Paper servers: set `api.server-id` and `api.secret` in `plugins/CaptainHook/config.yml`. Use the name the
   server has in `velocity.toml` as `api.server-id`. Keep the clock NTP-synced, because the website rejects
   timestamps that are off by more than 60 s.
5. Paper servers must only be reachable through the proxy (modern forwarding and a firewall).

Requirements: Velocity 3.4 or newer (tested on 3.4.0 and 4.2.0) with Java 17+.

### Upgrading from 1.x (single plugin with its own database)

- The Paper servers no longer connect to the database. The `database:` section and `kingdomcoins.refresh-interval-seconds`
  left in their existing `config.yml` files are ignored; **delete the `database:` section there**, so the
  credentials are no longer stored on every server.
- `/captainhook merge` moved to the proxy (`/captainhookproxy merge`). To merge a `database.db` left on a Paper
  server by 1.x, copy it into the proxy's `plugins/captainhook/` folder first (one file at a time).
- The tables and their columns are unchanged, so the website keeps working. New tables are created by the proxy
  on its first start.

## Commands

On the Paper servers:

| Command | Permission | |
|---|---|---|
| `/link [kod]` | – | Link status / link the account with a 6-digit code |
| `/kc` · `/kc balance [gracz]` | – · `captainhook.kingdomcoins.balance.others` | Own / another player's balance |
| `/kc pay <gracz> <ilość>` | – | Send coins (works across servers) |
| `/kc top` | – | Top 10 (cached) |
| `/kc give\|take\|set <gracz> <ilość> [powód]` | `captainhook.kingdomcoins.admin` | Change a balance (logged) |
| `/kc history <gracz> [strona]` | `captainhook.kingdomcoins.admin` | Transaction log |
| `/captainhook status\|reload` | `captainhook.admin` | State of the link to the proxy, stats queue, reload |

`/kc` aliases are configurable (`kingdomcoins.command-aliases`). Commands that need the proxy do not work from
the console of an **empty** server: a plugin message needs a player to travel with.

On the proxy:

| Command | Permission | |
|---|---|---|
| `/captainhookproxy status` (alias `/chproxy`) | `captainhook.admin` | Database mode, season, servers heard from |
| `/captainhookproxy reload` | `captainhook.admin` | Re-read `achievements.yml`, `seasonpass.yml` and the minigame settings, publish them for the website |
| `/captainhookproxy merge [confirm]` | `captainhook.admin` | Merge the SQLite fallback file into MySQL |

## Placeholders (PlaceholderAPI, on the Paper servers)

All are served from memory, so they are safe for scoreboards.

| Placeholder | Value |
|---|---|
| `%captainhook_kingdomcoins%` | Viewer's balance, e.g. `1250000` |
| `%captainhook_kingdomcoins_formatted%` | Viewer's balance, e.g. `1 250 000` |
| `%captainhook_kingdomcoins_top_<1-10>_name%` | Player name at that top position |
| `%captainhook_kingdomcoins_top_<1-10>%` | Formatted balance at that top position |
| `%captainhook_pass_level%` | Viewer's season pass level |
| `%captainhook_pass_xp%` | Viewer's XP towards the next level |
| `%captainhook_pass_season%` | Name of the current season |

## API for other plugins (Paper servers)

Add `depend: [CaptainHook]` to your `plugin.yml` and use `pl.kingdomcraft.captainhook.CaptainHookAPI`
(KingdomCoins: `pl.kingdomcraft.captainhook.coins.KingdomCoins`). Every call is forwarded to the proxy and
returns a `CompletableFuture` that completes on the main thread; it fails with `ProxyException` when the proxy
cannot be reached or refuses the request.

```java
// Season pass and achievements
CaptainHookAPI.addPassXP(player.getUniqueId(), 50);
CaptainHookAPI.addAchievementProgress(player.getUniqueId(), "collector", 1);

// Minigame stats: report a finished game in one call
CaptainHookAPI.recordMinigameStats("house_of_terror", Arrays.asList(
        MinigameStat.add(winner, "wins", 1),
        MinigameStat.add(winner, "games_played", 1),
        MinigameStat.min(winner, "best_time", seconds)));

// Cross-server join: send the player from the hub to the minigame server...
CaptainHookAPI.queueMinigameJoin(player.getUniqueId(), "house_of_terror", "terror-1", "mansion", null);

// ...where the minigame plugin picks them up
@EventHandler
public void onMinigameJoin(MinigameJoinEvent event) {
    if (event.getGame().equals("house_of_terror"))
        arenas.join(event.getPlayer(), event.getArena());
}
```

Events (package `event`): `PassLevelUpEvent`, `AchievementUnlockEvent`, `MinigameJoinEvent`.

Velocity plugins can use the services directly through the plugin instance: `getMinigameApi()`,
`getSeasonPass()`, `getAchievements()`, `getKingdomCoins()`.

## Stats

Stats live in `ch_player_stats` (one row per server and player) and `ch_player_stats_total` (network-wide sums, one indexed
column per leaderboard). Players whose stats changed (kills, deaths, placed blocks, join/quit) are sent to the proxy in one batch every
`stats.realtime-interval-seconds` (default 5 s), and everyone online every `stats.sync-interval-minutes` (playtime and the online heartbeat).
Values come from vanilla statistics, so they include history from before the plugin was installed.

Whether a player is online is decided by the proxy, which sees every server switch and disconnect. A Paper server
that stops, crashes or loses its last player can no longer report anything, and does not need to: its players are
marked offline by the proxy, and the numbers reach the database with each player's next snapshot.

## Achievements and season pass

Both are configured on the proxy and apply to the whole network:

- `achievements.yml`: each achievement has a goal, a source of progress (`manual` = through the API,
  `network-stat` = follows a network total such as `player_kills`, `minigame-stat` = follows a minigame stat)
  and rewards. Table `ch_achievements`. A `minigame-stat` source with `at-most: N` is a threshold for stats where
  lower is better (best times kept with `min`): it unlocks once the player has the stat and it is at most N, e.g.
  BoatRace's `fastest_race_<track>` in ms ("finish track1 under 1:05" = `stat: fastest_race_track1`, `at-most: 65000`).
- `missions.yml`: a `MINIGAME_STAT` mission with `filter.min-value: N` counts 1 for every report of the stat (any operation)
  whose value is at least N, instead of adding up a counter: "finish a race averaging 5 blocks/s" =
  `stat: top_avg_speed`, `min-value: 500`; "3 races in a row without being last" = `stat: best_streak`, `min-value: 3`.
- `seasonpass.yml`: one season, XP per level, and a free and a premium reward per tier. Tables `ch_seasonpass`
  (XP and the `premium` flag) and `ch_seasonpass_claims`.

A reward's commands can be bound to one server with `require-server: "survival"` (its name in `velocity.toml`), e.g. a
game-mode item: the reward then waits in the queue until the player is on that server (KingdomCoins and XP are
network-wide and granted at once).

Rewards are **claimed by the players on the website** (dashboard → Season Pass & Achievements). An achievement
can instead pay out on unlock: `reward-mode: automatic` (top of `achievements.yml` or per achievement).

Whoever grants a reward does it in one transaction with the unlock or claim: KingdomCoins and pass XP directly,
`commands` as a row in `ch_reward_queue`. The proxy delivers that queue: every few seconds it sends the rows of
online players to their server (`reward.dispatch`), which runs the commands as the console, tells the player and
confirms (`reward.ack`); the row then gets `delivered_at`. A reward waits as long as the player is offline.

The proxy copies both files into the database on every start and reload (`ch_seasonpass_seasons`,
`ch_seasonpass_rewards`, `ch_achievement_definitions`); the website reads them from there. A queued row is only
delivered if its commands match what the proxy published for that reward, so the website (which may write to the
queue) cannot make the servers run anything else. Details: `docs/NETWORK_PROTOCOL.md` section 10.

## Database

- Only the proxy connects. MySQL is used when `database.mysql.enabled` is true and the connection succeeds. Otherwise the proxy runs on
  `plugins/captainhook/database.db` (SQLite) and logs a warning. The mode is chosen on startup, so a restart is needed to switch.
- All balance changes are atomic relative SQL updates (`balance = balance + ?`), so the website can write to the same database safely.
  Every change is written to `ch_kingdomcoins_log`.
- When a proxy that ran on SQLite connects to MySQL again, `/captainhookproxy merge` shows what will be merged and
  `/captainhookproxy merge confirm` **adds** the SQLite balances and season pass XP to MySQL, copies the coin log, season pass claims
  and achievement unlocks, and copies stats rows that are newer than in MySQL, all in one transaction. Then it renames the file
  to `database.db.merged-<date>` so it cannot be merged twice. Minigame stats and progress of locked achievements recorded
  on SQLite are **not** merged.
- The MySQL and SQLite JDBC drivers are bundled in the Velocity jar (Velocity ships none).

| Table | Content |
|---|---|
| `ch_player_stats`, `ch_player_stats_total` | Stats per server and network totals (unchanged) |
| `ch_kingdomcoins`, `ch_kingdomcoins_log` | Balances and the transaction log (unchanged; new log type `REWARD`) |
| `ch_minigame_stats` | `game`, `uuid`, `stat`, `value`, `updated_at`; primary key (`game`, `uuid`, `stat`), index (`game`, `stat`, `value`) for leaderboards. Player names are in `ch_kingdomcoins.name` |
| `ch_minigame_pending_joins` | One pending cross-server join per player: `uuid`, `game`, `server`, `arena`, `data`, `created_at`, `expires_at` |
| `ch_achievements` | `uuid`, `achievement`, `progress`, `unlocked_at` (NULL while locked), `claimed_at` (NULL until the reward was granted), `updated_at` |
| `ch_seasonpass` | `season`, `uuid`, `xp`, `premium`, `updated_at`; index (`season`, `xp`) |
| `ch_seasonpass_claims` | `season`, `uuid`, `tier`, `track` (`free`/`premium`), `source`, `claimed_at`; the primary key over the first four makes a double claim impossible |
| `ch_reward_queue` | Command rewards waiting to be executed on a Paper server: `id`, `uuid`, `source`, `commands` (JSON array), `message`, `created_at`, `delivered_at`, `delivered_server` |
| `ch_seasonpass_seasons`, `ch_seasonpass_rewards`, `ch_achievement_definitions` | The configuration files as data, written by the proxy for the website (and to verify the reward queue) |

## Development

- `velocity/` is plain Java 17 against the Velocity API; `bukkit/` is Java 8 with Foundation.
- The wire protocol classes (`pl.kingdomcraft.captainhook.protocol`) exist in both modules and must stay
  identical: `ProtocolParityTest` fails the build otherwise.
- The tests of the Velocity module run the services against SQLite, or against MySQL when
  `CAPTAINHOOK_TEST_MYSQL` holds a JDBC URL, e.g.
  `CAPTAINHOOK_TEST_MYSQL="jdbc:mysql://127.0.0.1:3306/test?user=root&sslMode=DISABLED&allowPublicKeyRetrieval=true" mvn test`
  (every `ch_` table of that database is dropped). The Bukkit module has no automated tests.
- `backend/tests/fixtures/captainhook-schema.sql` in the website repository is `SHOW CREATE TABLE` of the
  proxy's MySQL schema; regenerate it when `Schema.java` changes.
