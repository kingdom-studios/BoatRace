# BoatRace (Paper 26.2, Foundation 6.10.2)

Wyścigi łódek w stylu F1: światła startowe, falstart, niewidzialne checkpointy, delty do rekordu, rankingi SQLite, PlaceholderAPI.

## Budowanie
```
mvn clean package
```
Wynik: `boatrace/target/BoatRace-1.0.0.jar` (Foundation wkompilowany i przeniesiony do `pl.kingdomcraft.boatrace.lib`).

* Foundation budowany jest z **lokalnych źródeł** `lib/foundation` (moduł Mavena) - nie jest pobierany jako gotowa zależność.
* Maven nadal pobiera **Paper API** oraz API wtyczek, do których odwołuje się kod Foundation (repozytoria w pom). Wymagany JDK 21+.
* Jeśli `paper.version` (pom nadrzędny) się nie rozwiąże, wpisz dokładną wersję z repo.papermc.io. Gdyby moduł Foundation nie kompilował się na API 26.2, ustaw tam wersję z oryginalnego pom-a (26.3).

## Komendy
Gracz: `/br join [arena]`, `/br leave` (=[HUB]), `/br reset`, `/br list`
Admin (`boatrace.admin`): `create`, `setspawn`, `setstartblock`, `addcheckpoint`, `setfinish` `<arena>`,
`admin time add|remove <gracz> <czas> [arena]`, `admin time reset <gracz> [arena]`, `admin blacklist <gracz>` (przełącza), `reload`.
Czas: `12.5` / `12.5s` = sekundy, `1500ms` = milisekundy.

* `setstartblock` - patrz na blok linii startowej, nad nim (config: `start-lights.height`) pojawi się rząd lamp, prostopadły do kierunku patrzenia. Lampy są tylko po stronie klienta (świat się nie zmienia).
* `addcheckpoint` / `setfinish` - użyty zostaje blok, na który patrzysz (do 30 bloków), a gdy patrzysz w powietrze - Twoja pozycja. Strefa = kula o promieniu z configu.

## Placeholdery
`%boatrace_besttime_<arena>%`, `%boatrace_top_player_<arena>_<poz>%`, `%boatrace_top_time_<arena>_<poz>%`
Foundation podpina je pod PAPI i zwraca wartości tylko gdy zapytanie ma **online gracza** (zwykłe hologramy/scoreboardy je mają; `/papi parse --null` nie).

## Pliki danych
`config.yml` (ustawienia), `arenas.yml` (areny), `database.db` (SQLite: rekordy + splity + blacklist), `snapshots/` (kopia ekwipunku gracza na czas wyścigu, przywracana też po crashu).
