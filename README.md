# BoatRace

F1-style boat racing: start lights, false starts, invisible checkpoints, deltas to personal/track records, SQLite leaderboards, PlaceholderAPI support.

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

**Automatyczne uzupełnianie plików (start i `/bre reload`)** – `config.yml`, `messages.yml`, `gui.yml`: brakujące klucze z jara są dopisywane (z wartością domyślną i komentarzem) w swojej sekcji. Twoje wartości nigdy się nie zmieniają, nieużywane klucze zostają. Kopia przed zapisem: `plugins/BoatRace/backups/<plik>.<data>.bak`; log mówi, co dodano. Kolekcje (`fallback.maps`, `ranking.xp-multipliers`, `competitive.colors`, gui `maps`, `items`, `competitive-buttons`) dodawane tylko w całości, gdy ich brak – nigdy nie dosypuje przykładów. Plik z błędem YAML nie jest ruszany. Zapis jest kosmetycznie "znormalizowany" (cudzysłowy, listy `[a, b]` → wypunktowane, wyrównanie komentarzy) – działanie bez zmian. `arenas.yml` i pliki CaptainHooka na proxy nie są ruszane.


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

## Ranking, CaptainHook, strona WWW (skrót)

**Ranking (ELO)** – `config.yml` `ranking:`, działa też bez CaptainHooka.
* Liczą się: event / party, min. `min-racers` (2) graczy przy zgaszeniu świateł. Solo nigdy.
* Kolejność = pojedynki: każdy wygrał z każdym za sobą. DNF = za wszystkimi (kto wyszedł wcześniej, ten niżej).
* Rozliczenie, gdy nikt już nie płynie. Reload / stop serwera = nikt nic nie traci.
* Zero-sum: ustawione wyniki między kolegami nic nie dają (punkty krążą tam i z powrotem). Wygrana ze słabszym ≈ nic, przegrana z nim boli.
* `k-factor` 16 = ile punktów "w grze" na wyścig Casual (Competitive: `competitive.k-factor` 48). Start 1000, minimum 100.
* `party-races: false` = wyścigi party poza rankingiem (gdyby znajomi farmili).

**Seria "nie ostatni"** – rankingowy wyścig ukończony nie na ostatnim miejscu = +1, ostatnie miejsce / DNF = 0. Trzymana w bazie (między serwerami i po restarcie).

**XP karnetu (CaptainHook)** – `captainhook.pass-xp` (finish / win / podium / rekord / co 250 bloków) × mnożnik z rankingu (`ranking.xp-multipliers`: <900 ×0.5 … 1500+ ×2.0). Dzienny limit `daily-limit`. Bez sezonu = brak XP (bez błędów).

**Co wysyłamy do CaptainHooka** (gra `formula-boat-racing`, tylko UKOŃCZONE wyścigi, 1 paczka / wyścig, bez zer):

| stat | op | jednostka |
|---|---|---|
| `races`, `wins`, `podiums`, `laps` | add | szt. (wins/podiums tylko w rankingowych) |
| `distance` | add | bloki |
| `race_time_ms` | add | ms bez kar (średnia = distance / czas) |
| `fastest_lap`, `fastest_<track>` | min | ms, najlepsze okrążenie |
| `fastest_race_<track>` | min | ms, cały wyścig (tylko domyślna liczba okrążeń toru) |
| `top_avg_speed` | max | bloki/s × 100 (5 b/s = 500) |
| `best_streak` | max | wysyłamy AKTUALNĄ serię po każdym rankingowym finiszu |
| `rating` | set | punkty rankingu, osobna wiadomość po rozliczeniu |

* `<track>` = nazwa areny małymi literami, inne znaki niż `a-z0-9_` → `_`, max 19 znaków. Lista: `/bre tracks`.
* Czasy oficjalne (z karami). Prędkość i `race_time_ms` bez kar.
* Odrzucane w całości (ostrzeżenie w logu): okrążenie < `min-lap-seconds` (5) lub średnia > `max-avg-speed` (75 b/s). Powód: `min`/`max` zostaje w bazie na zawsze.
* Nigdy nie zmieniać znaczenia/jednostek kluczy – strona na nich stoi. Nowe znaczenie = nowy klucz.

**Wyzwania w CaptainHooku** (proxy: `achievements.yml`, `missions.yml`, potem `/captainhookproxy reload`):
* Osiągnięcie z licznika: `source: { type: minigame-stat, game: formula-boat-racing, stat: wins }`, `goal: 25`.
* Osiągnięcie z rekordu "co najmniej": `stat: top_avg_speed`, `goal: 500` (pasek rośnie z najlepszym wynikiem).
* Osiągnięcie z czasu "poniżej": `stat: fastest_race_<track>`, `at-most: 65000` (ms), goal 1.
* Misja z licznika: `objective: MINIGAME_STAT`, `filter: { game, stat: races }`, `target: 3`.
* Misja progowa ("raz osiągnij X"): `filter: { game, stat, min-value: N }`, `target: 1` – liczy +1 za każde zgłoszenie z wartością ≥ N.
  * Średnia ≥ 5 b/s: `stat: top_avg_speed`, `min-value: 500`.
  * 3 wyścigi pod rząd nie ostatni: `stat: best_streak`, `min-value: 3` (gotowe: `weekly-br-streak-3`).
  * Uwaga: seria z poprzedniego tygodnia liczy się dalej (2 w niedzielę + 1 w poniedziałek = zaliczone).

**Hologramy (PlaceholderAPI)** – patrz Placeholders niżej. HolographicDisplays: wpisz placeholder wprost w linii, np. `#1 %boatrace_rating_top_player_1% %boatrace_rating_top_value_1%`.

**Znane ograniczenia** – cache rankingu odświeżany co `records.refresh-seconds` (30 s): po zmianie serwera przez chwilę mogą być widoczne stare punkty (w bazie się nie gubią). Dzienny limit XP jest w pamięci (zeruje się po restarcie).

## Casual i Competitive (skrót)

| | Casual | Competitive |
|---|---|---|
| Co | solo, eventy, wyścigi party (każdy na siebie) | drużyna na drużynę z lobby |
| Ranking | wspólny `rating`, `ranking.k-factor` 16 | ten sam `rating`, `competitive.k-factor` 48 |
| XP | `captainhook.pass-xp` | × `xp-multiplier` (2.0) + win / full-team + bonus składu |
| Zbroja / tag | nie | tak |

**Jeden ranking na gracza** – party są tymczasowe, więc nie ma rankingu drużyn. Każdy gra na swój `rating`; mecz Competitive daje + za wygraną drużyny, − za słabszy wynik.

**Włączenie** – `config.yml` `competitive.enabled: true` (domyślnie off). Przycisk GUI: `gui.yml` `competitive-buttons` (`enabled: true`, wolny slot).

**Limity na arenę** – `/bre compset <arena> min 4` / `max 8` / `on|off` / `reset` / `info` (albo `arenas.yml` `competitive: { enabled, min-players, max-players }`). Brak = `competitive.min-players` (4) i max = liczba spawnów. Spawnów nie trzeba zmieniać.

**Dołączanie** – `/bre ranked <arena|random>` / przycisk GUI (drugi klik = wyjście). Lider party = cała drużyna. Solo = drużyna 1-osobowa. Max drużyna: `max-team-size` (4).
* Lobby bierze drużyny po kolei, póki się mieszczą (max graczy, max drużyn = liczba kolorów). Reszta czeka na następny mecz.
* ≥ min graczy i ≥ 2 drużyny → odliczanie `countdown-seconds` (30). Pełne lobby → start od razu. Spadek poniżej min → odliczanie anulowane.
* Lobby `random` = min najniższy, max najwyższy ze wszystkich aren; mecz trafia na wolną arenę, która pasuje liczbą graczy.
* Wypada z lobby: wyjście z serwera, zmiana party, start innego wyścigu, ban.

**Mecz**
* Siatka: pierwsi zawodnicy każdej drużyny, potem drudzy … Kolory `competitive.colors` (8 domyślnych), pełna skórzana zbroja, tytuł + czat "Twoja drużyna", tag LuckPerms (transient meta `boatrace_team`, w czacie/TAB `%luckperms_meta_boatrace_team%`, bez LP `%boatrace_team_tag%`).
* Wynik: punkty F1 za miejsce (`competitive.points`, DNF = 0), drużyna = ŚREDNIA członków (różne rozmiary porównywalne, najsłabszy też się liczy). Remis → najlepsze pojedyncze miejsce.
* Pasek akcji: wynik drużyny na żywo przed czasem (`actionbar.team`).
* Rating: drużyny w kolejności wyniku = jeden wyścig ELO (siła drużyny = średni rating członków), każdy członek dostaje zmianę swojej drużyny. Nikt nie dojechał → brak zmian.

**Kary** – wyjście z trwającego meczu (też w trakcie świateł) = DNF + `-15` ratingu + `5 min` bez Competitive. Bypass: `boatrace.competitive.bypass` (domyślnie op). Reload / stop serwera nie karze.

**Bonus stałego składu** – ta sama drużyna (dokładnie ci sami gracze) w kolejnym meczu: +10% XP za mecz z rzędu, max +30%, przerwa > 30 min = od nowa. Tylko w pamięci: 1 mały wpis na gracza (odcisk składu, licznik, czas), brak statystyk drużyn; restart zeruje.

**CaptainHook** – statystyki indywidualne jak w każdym wyścigu + `comp_races`, `comp_wins`, `comp_full_team` (add, tylko ci, co dojechali), `rating` (set). Nowe klucze → zgłosić zespołowi strony.

**Placeholdery** – `%boatrace_team%`, `%boatrace_team_tag%`, `%boatrace_comp_games%`, `%boatrace_comp_wins%`; ranking jak w Casual (`%boatrace_rating...%`).

## Lista TAB na osobnym serwerze (skrót)

`config.yml` `tablist.session-only: true` – gracz widzi w TAB tylko swoją grupę: **lobby** albo **swoją sesję** (instancja areny: solo, event, party, Competitive – też na siatce i po mecie). Widz (`/bre spectate`) widzi sesję, którą ogląda.
* Tylko lista TAB (Paper `unlistPlayer`) – w świecie wszyscy dalej widoczni. Niczego nie zapisuje (crash = nic nie zostaje), wyłączenie / stop pluginu przywraca wszystkich.
* Odświeżanie co 0,5 s + przy wejściu gracza. Vanish innych pluginów nie jest ruszany.
* `boatrace.tablist.bypass` = widzi wszystkich (domyślnie nikt, także op).
* Plugin TAB (NEZNAMY): działa razem z nim (formatowanie / sortowanie bez zmian). Warunek: `layout.enabled: false` i `per-world-playerlist.enabled: false` w TAB.

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