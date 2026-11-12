# Brief: kingdomcraft.pl ↔ Captain Hook (wersja 2: statystyki z bazy danych)

> Dokument dla agenta lub programisty, który rozwija stronę kingdomcraft.pl (backend i frontend).
> **Zastępuje poprzedni brief.** Najważniejsza zmiana: statystyki graczy **nie są już wysyłane na API**.
> Plugin Minecraft „Captain Hook” zapisuje je w czasie zbliżonym do rzeczywistego do **wspólnej bazy MySQL sieci**,
> a strona czyta je bezpośrednio z tej bazy. Łączenie kont nadal działa przez API.

> **Captain Hook 2.0 (architektura proxy):** do bazy pisze już tylko plugin na **proxy Velocity**. Podserwery Paper
> wysyłają mu statystyki, a same nie mają połączenia z bazą. **Dla strony nic się nie zmienia** w tabelach z sekcji 3
> (te same kolumny, znaczenie i świeżość danych). Doszły nowe tabele: minigry, osiągnięcia i karnet sezonowy (sekcja 3.6).

---

## 0. Co musisz zmienić (TL;DR)

| Obszar | Zmiana |
|---|---|
| `POST /stats` | **Do usunięcia.** Plugin już go nie wywołuje. Usuń endpoint i logikę zapisu snapshotów z requestów. |
| `POST /link/verify`, `GET /link/{uuid}` | **Bez zmian.** Dalej działają przez API z podpisem HMAC (sekcja 2). |
| Źródło statystyk | Odczyt z tabel pluginu w MySQL: `ch_player_stats_total` (sumy sieci), `ch_player_stats` (per serwer), `ch_kingdomcoins` (waluta). |
| Dostęp do bazy | Backend strony potrzebuje połączenia z bazą MySQL sieci przez **osobnego użytkownika z uprawnieniem tylko do odczytu** (sekcja 4). |
| „Na żywo” | Backend sprawdza zmiany co ~5 s jednym tanim zapytaniem i wypycha nowe dane do przeglądarek (SSE/WebSocket) albo frontend odpytuje backend (sekcja 7). |

---

## 1. Architektura

```
 [survival-1] ─┐                                   ┌──────────────── MySQL sieci ─────────────────┐
 [skyblock-1] ─┼── zapis co ~5 s (przez proxy) ──▶ │ ch_player_stats        (per serwer)          │
 [lobby-1]   ─┘                                    │ ch_player_stats_total  (sumy sieci, indeksy) │ ◀── odczyt ── backend strony ──▶ frontend
       │                                           │ ch_kingdomcoins (+ _log)  (waluta premium)   │                (SSE / polling)
       └── /link/verify, /link/{uuid} (HTTPS + HMAC) ───────────────────────────────────────────────────────▶ API strony
```

- Sieć ma wiele podserwerów Paper. Każdy ma własne `server_id` (np. `survival-1`), które jest też jego ID w API (`X-KC-Server`).
- Gracz jest identyfikowany przez **UUID w trybie online**: małe litery z myślnikami, `CHAR(36)`,
  np. `069a79f4-44e9-4726-a5be-fca90e38aaf5`. Nick może się zmienić, a UUID nie.
- Wszystkie znaczniki czasu to **czas Unix w sekundach** (`BIGINT`, UTC).
- Tabele tworzy i aktualizuje **plugin** (`CREATE TABLE IF NOT EXISTS`). Strona ich nie tworzy, nie zmienia kolumn i nie usuwa.

---

## 2. Łączenie kont (API, bez zmian)

Komunikacja idzie tylko w kierunku plugin → strona. Bazowy URL to `https://kingdomcraft.pl/api/integrations/minecraft`.

### Autoryzacja każdego żądania

| Nagłówek | Wartość |
|---|---|
| `X-KC-Server` | ID podserwera, np. `survival-1` |
| `X-KC-Timestamp` | czas Unix w sekundach |
| `X-KC-Signature` | `hex(HMAC_SHA256(secret_serwera, timestamp + "\n" + rawBody))`, małe litery |
| `Content-Type` | `application/json; charset=utf-8` (tylko POST, **akceptuj wariant z `charset`**) |

Weryfikacja: liczysz HMAC na **surowym body przed parsowaniem** (dla GET to `""`), sprawdzasz `|now − timestamp| ≤ 60 s`
i porównujesz w stałym czasie. Nieznany serwer, zły podpis albo nieaktualny czas dają `401 {"error":{"code":"bad_signature"}}`.

```ts
import { createHmac, timingSafeEqual } from "node:crypto";

function verify(serverId: string, timestamp: string, signature: string, rawBody: string, secrets: Record<string, string>): boolean {
  const secret = secrets[serverId];
  if (!secret || !/^\d+$/.test(timestamp) || Math.abs(Date.now() / 1000 - Number(timestamp)) > 60) return false;
  const expected = createHmac("sha256", secret).update(`${timestamp}\n${rawBody}`, "utf8").digest("hex");
  return expected.length === signature.length && timingSafeEqual(Buffer.from(expected), Buffer.from(signature));
}
```

Wektor testowy: sekret `test-secret`, timestamp `1759395600`, body `""` daje
`c1368d89e29d17eefc9778a5559739e782aa89007d4d15e43419a282b6061e4e`.

Format odpowiedzi: sukces to `{ "data": ... }`, błąd to `{ "error": { "code": "...", "message": "..." } }`. Plugin czeka maksymalnie 10 s.

### `POST /link/verify`

Przepływ: gracz zalogowany na stronie podaje nick i dostaje **6-cyfrowy kod** (ważny 10 min, jednorazowy, przypisany do nicku).
W grze wpisuje `/link <kod>`.

```json
{"code":"048213","uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","nickname":"Steve"}
```
- `code` jest **stringiem** (wiodące zera). `nickname` porównuj **bez rozróżniania wielkości liter**.

| Status | Body | Co widzi gracz |
|---|---|---|
| 200 | `{"data":{"linked":true,"username":"kardynal_rys"}}` | sukces z nazwą konta na stronie |
| 400 | `error.code = "invalid_code"` | kod błędny lub wygasł |
| 409 | `error.code = "nickname_mismatch"` | kod dla innego nicku |
| 409 | `error.code = "already_linked"` | konto MC już połączone z innym kontem |
| 429 | `error.code = "rate_limited"` | za dużo prób |
| 401 | `error.code = "bad_signature"` | błąd konfiguracji serwera |
| 5xx | – | strona chwilowo niedostępna |

Inne kody `error.code` gracz zobaczy jako „strona niedostępna”.

### `GET /link/{uuid}`

Wynik to `{"data":{"linked":true,"username":"..."}}` albo `{"data":{"linked":false}}` (`linked` jest typu boolean).
Plugin trzyma wynik w pamięci przez 5 min. Rozłączanie kont odbywa się **tylko na stronie**.

**Połączenie konta strony z UUID to jedyny łącznik między użytkownikiem strony a danymi w bazie sieci.**
Strona przechowuje je u siebie (np. `users.minecraft_uuid CHAR(36) UNIQUE`).

---

## 3. Tabele w bazie sieci

Wszystkie tabele: InnoDB, `utf8mb4_unicode_ci` (porównania nicków nie rozróżniają wielkości liter).

### 3.1 `ch_player_stats_total`: sumy dla całej sieci (główne źródło rankingów)

Jeden wiersz na gracza. Plugin przelicza go przy każdym zapisie statystyk tego gracza.

| Kolumna | Typ | Znaczenie |
|---|---|---|
| `uuid` | CHAR(36) PK | gracz |
| `nickname` | VARCHAR(16) | ostatni znany nick |
| `playtime_seconds` | BIGINT | łączny czas gry we wszystkich trybach |
| `blocks_placed` | BIGINT | postawione bloki |
| `player_kills` | BIGINT | zabici gracze |
| `mob_kills` | BIGINT | zabite moby |
| `deaths` | BIGINT | śmierci |
| `online` | BOOLEAN | 1, gdy gracz jest online na którymkolwiek serwerze (patrz 3.4) |
| `last_seen` | BIGINT | ostatnia aktywność |
| `updated_at` | BIGINT | ostatnia aktualizacja wiersza |

Indeksy (każdy ranking działa bez skanowania tabeli): `playtime_seconds`, `blocks_placed`, `player_kills`, `mob_kills`, `deaths`, `updated_at`.

### 3.2 `ch_player_stats`: statystyki per podserwer

Jeden wiersz na `(server_id, uuid)`. To **wartości bezwzględne** z danego serwera (snapshot), a nie przyrosty.

| Kolumna | Typ | Znaczenie |
|---|---|---|
| `server_id` | VARCHAR(64) | podserwer, PK razem z `uuid` |
| `uuid` | CHAR(36) | gracz (indeks) |
| `nickname` | VARCHAR(16) | nick w chwili zapisu |
| `balance` | DECIMAL(20,2) | saldo **zwykłej** ekonomii tego serwera (Vault). Nie sumuj między serwerami |
| `level` | INT | poziom doświadczenia na tym serwerze. Nie sumuj |
| `playtime_seconds` | BIGINT | czas gry na tym serwerze |
| `blocks_placed` | BIGINT | postawione bloki |
| `player_kills` / `mob_kills` / `deaths` | INT | zabici gracze, zabite moby, śmierci |
| `clan_tag` | VARCHAR(16) NULL | na razie **zawsze NULL** (integracja z klanami będzie później) |
| `online` | BOOLEAN | czy gracz jest teraz na tym serwerze |
| `last_seen` | BIGINT | moment wykonania snapshotu (przy wyjściu to moment wyjścia) |
| `updated_at` | BIGINT | moment zapisu w bazie |

### 3.3 `ch_kingdomcoins` i `ch_kingdomcoins_log`: waluta premium

KingdomCoins (skrót **KC**) to waluta **wspólna dla całej sieci**. Każda transakcja w grze od razu zmienia te tabele,
więc saldo jest tu zawsze aktualne.

| Tabela | Kolumny |
|---|---|
| `ch_kingdomcoins` | `uuid` CHAR(36) PK, `name` VARCHAR(16), `balance` BIGINT ≥ 0 (indeks), `last_seen` BIGINT (ostatnie wejście do gry) |
| `ch_kingdomcoins_log` | `id` PK, `uuid`, `type`, `amount` (ze znakiem; przy `SET` nowe saldo), `balance_after`, `actor` (UUID gracza / `CONSOLE` / `SQLITE` / `WEB`), `server`, `reason` (NULL), `created_at`; indeks `(uuid, id)` |

Typy `type`: `GIVE`, `TAKE`, `SET`, `PAY_SENT`, `PAY_RECEIVED`, `PLUGIN`, `MERGE`, `WEB`. Typy mogą przybywać, więc nieznane wyświetlaj jako tekst.

### 3.4 Kiedy plugin zapisuje (świeżość danych)

| Zdarzenie | Opóźnienie w bazie |
|---|---|
| Zabójstwo, śmierć, postawiony blok, wejście na serwer | ≤ ~5 s |
| Wyjście z serwera (`online = 0`) | ≤ ~5 s |
| Czas gry i „heartbeat” wszystkich graczy online (`updated_at`) | co 5 min |
| Transakcja KingdomCoins | natychmiast |
| Wyłączenie serwera | natychmiast (wszyscy dostają `online = 0`) |
| Start serwera po awarii | natychmiast (gracze tego serwera dostają `online = 0`) |

Interwały 5 s i 5 min ustawia administrator w configu pluginu.

**Wykrywanie gracza online:** `online = 1 AND updated_at >= UNIX_TIMESTAMP() - 660`. Margines 11 min
(dwa heartbeaty plus zapas) chroni przed „wiszącym online” po awarii serwera, zanim ten wstanie.

### 3.5 Czego nie zakładać

- **Statystyki zbierane od wdrożenia pluginu:** gracz pojawia się w tabelach dopiero po pierwszym wejściu do gry.
  Czas gry, zabójstwa i śmierci pochodzą ze statystyk Minecrafta, więc obejmują też historię sprzed wdrożenia.
- **Wartości mogą spaść** po resecie świata na podserwerze. To poprawne dane.
- **Tryb awaryjny:** proxy, które nie połączy się z MySQL, zapisuje lokalnie (SQLite) i dane w MySQL przestają się
  odświeżać (widać to po starym `updated_at`). Po naprawie administrator scala dane (`/captainhookproxy merge`): nowsze statystyki
  zastępują starsze, a KC są dopisywane z typem `MERGE` w logu.

### 3.6 Nowe tabele w wersji 2.0: minigry, osiągnięcia, karnet sezonowy

Tworzy je plugin na proxy. Tak jak pozostałe: InnoDB, `utf8mb4_unicode_ci`, czas w sekundach Unix.

| Tabela | Kolumny | Uwagi |
|---|---|---|
| `ch_minigame_stats` | `game` VARCHAR(32), `uuid` CHAR(36), `stat` VARCHAR(32), `value` BIGINT, `updated_at`; PK (`game`, `uuid`, `stat`) | Jeden wiersz na statystykę gracza w minigrze, np. `house_of_terror` / `wins`. Indeks (`game`, `stat`, `value`) obsługuje rankingi. Nick gracza: `JOIN ch_kingdomcoins USING (uuid)` (kolumna `name`). Nazwy gier i statystyk to `[a-z0-9_]`, nowe pojawiają się bez zmian w schemacie |
| `ch_achievements` | `uuid`, `achievement` VARCHAR(64), `progress` BIGINT, `unlocked_at` BIGINT NULL, `claimed_at` BIGINT NULL, `updated_at`; PK (`uuid`, `achievement`) | `unlocked_at IS NULL` = jeszcze nie zdobyte, `claimed_at IS NULL` = nagroda nieodebrana. Nazwy, opisy, cele i nagrody: `ch_achievement_definitions` |
| `ch_seasonpass` | `season` VARCHAR(32), `uuid`, `xp` BIGINT, `premium` BOOLEAN, `updated_at`; PK (`season`, `uuid`) | Poziom = min(maks. poziom, `xp` / XP na poziom); obie wartości są w `seasonpass.yml` na proxy. Indeks (`season`, `xp`) |
| `ch_seasonpass_claims` | `season`, `uuid`, `tier` INT, `track` (`free` / `premium`), `source`, `claimed_at`; PK (`season`, `uuid`, `tier`, `track`) | Odebrane nagrody. Klucz główny uniemożliwia odebranie dwa razy |
| `ch_reward_queue` | `id`, `uuid`, `source`, `commands` (tablica JSON), `message`, `created_at`, `delivered_at` NULL, `delivered_server` | Nagrody-komendy czekające na wykonanie na serwerze gry |
| `ch_minigame_pending_joins` | (wewnętrzna) | Strona jej nie używa |
| `ch_seasonpass_seasons`, `ch_seasonpass_rewards`, `ch_achievement_definitions` | kopia `seasonpass.yml` i `achievements.yml` | Proxy zapisuje je przy każdym starcie i przeładowaniu. Strona je **tylko czyta** (pokazuje karnet i osiągnięcia, a przy odbiorze przyznaje dokładnie te nagrody) |

Odbiór nagród na stronie i uprawnienia osobnego użytkownika bazy do zapisu: `docs/NETWORK_PROTOCOL.md`, sekcja 10.

W `ch_kingdomcoins_log` doszedł typ `REWARD` (KC z osiągnięcia lub karnetu, `actor = 'REWARD'`, `server = 'proxy'`,
`reason` = źródło, np. `achievement:first_blood`).

---

## 4. Dostęp backendu do bazy

Utwórz dla strony osobnego użytkownika MySQL z minimalnymi uprawnieniami. **Nigdy nie wystawiaj bazy na frontend.**

```sql
CREATE USER 'kc_web'@'<ip-backendu>' IDENTIFIED BY '<silne-haslo>';
GRANT SELECT ON <baza>.ch_player_stats       TO 'kc_web'@'<ip-backendu>';
GRANT SELECT ON <baza>.ch_player_stats_total TO 'kc_web'@'<ip-backendu>';
GRANT SELECT ON <baza>.ch_kingdomcoins       TO 'kc_web'@'<ip-backendu>';
GRANT SELECT ON <baza>.ch_kingdomcoins_log   TO 'kc_web'@'<ip-backendu>';
-- tylko jeśli strona doładowuje KC (sekcja 6):
-- GRANT INSERT, UPDATE ON <baza>.ch_kingdomcoins     TO 'kc_web'@'<ip-backendu>';
-- GRANT INSERT         ON <baza>.ch_kingdomcoins_log TO 'kc_web'@'<ip-backendu>';
```

Połączenie przez pulę z TLS, jeśli baza stoi na innej maszynie. Przed implementacją sprawdź rzeczywisty schemat
(`SHOW CREATE TABLE ...`) na bazie, na której plugin już się uruchomił.

---

## 5. Zapytania

```sql
-- Ranking (np. zabici gracze); analogicznie playtime_seconds, blocks_placed, mob_kills, deaths
SELECT uuid, nickname, player_kills AS value
FROM ch_player_stats_total
ORDER BY player_kills DESC
LIMIT 100;

-- Ranking KingdomCoins
SELECT uuid, name AS nickname, balance AS value
FROM ch_kingdomcoins
WHERE balance > 0
ORDER BY balance DESC
LIMIT 100;

-- Pozycja gracza w rankingu (np. playtime)
SELECT COUNT(*) + 1 AS position
FROM ch_player_stats_total
WHERE playtime_seconds > (SELECT playtime_seconds FROM ch_player_stats_total WHERE uuid = ?);

-- Ranking jednego podserwera (bez dedykowanego indeksu; przy dużych tabelach dodaj własny indeks (server_id, <kolumna>))
SELECT uuid, nickname, playtime_seconds AS value
FROM ch_player_stats
WHERE server_id = ?
ORDER BY playtime_seconds DESC
LIMIT 100;

-- Dashboard gracza: sumy, saldo KC, rozbicie per serwer
SELECT t.*, c.balance AS kingdom_coins
FROM ch_player_stats_total t
LEFT JOIN ch_kingdomcoins c ON c.uuid = t.uuid
WHERE t.uuid = ?;

SELECT server_id, playtime_seconds, blocks_placed, player_kills, mob_kills, deaths, balance, level, online, last_seen, updated_at
FROM ch_player_stats
WHERE uuid = ?
ORDER BY server_id;

-- Historia KC gracza (stronicowanie)
SELECT type, amount, balance_after, server, reason, created_at
FROM ch_kingdomcoins_log
WHERE uuid = ?
ORDER BY id DESC
LIMIT 20 OFFSET ?;

-- Gracze online w sieci
SELECT uuid, nickname
FROM ch_player_stats_total
WHERE online = 1 AND updated_at >= UNIX_TIMESTAMP() - 660;

-- Tani test „czy coś się zmieniło” (indeks na updated_at)
SELECT MAX(updated_at) FROM ch_player_stats_total;
```

Wartości wyświetlane:
- **Przegrane godziny:** `playtime_seconds / 3600` (np. z jednym miejscem po przecinku).
- **K/D:** `player_kills / NULLIF(deaths, 0)`.
- **KC:** liczby całkowite ze spacjami co 3 cyfry (`1 250 000 KC`), tak jak w grze.
- **Wiele serwerów:** pokaż filtr „Cała sieć” (`ch_player_stats_total`) albo „Serwer X” (`ch_player_stats`).
  `balance` i `level` pokazuj tylko per serwer.

---

## 6. Doładowanie KingdomCoins ze strony (opcjonalnie, np. sklep)

Podserwery zmieniają salda równolegle, dlatego obowiązują twarde zasady:
- **Tylko zmiany względne** (`balance = balance + ?`). Nigdy nie ustawiaj `balance` na wartość wyliczoną po stronie aplikacji.
- Przy odejmowaniu: `... SET balance = balance - ? WHERE uuid = ? AND balance >= ?`. Jeśli zmienią się 0 wierszy, środków jest za mało.
- Każda zmiana ma wpis w logu z `type = 'WEB'`, w **tej samej transakcji**.
- Gracz online zobaczy nowe saldo w grze w ciągu ~30 s.

```sql
-- jedna transakcja; ? = uuid, nick, kwota | kwota, powód, uuid
INSERT INTO ch_kingdomcoins (uuid, name, balance, last_seen) VALUES (?, ?, ?, UNIX_TIMESTAMP())
  ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance);

INSERT INTO ch_kingdomcoins_log (uuid, type, amount, balance_after, actor, server, reason, created_at)
  SELECT uuid, 'WEB', ?, balance, 'WEB', 'web', ?, UNIX_TIMESTAMP() FROM ch_kingdomcoins WHERE uuid = ?;
```
Te zapytania nie zostały uruchomione na MySQL. Przetestuj je przed wdrożeniem.

---

## 7. Aktualizacje w czasie rzeczywistym na stronie

Plugin zapisuje dane co ~5 s. Strona ma pokazywać je na bieżąco **bez obciążania bazy przez każdego odwiedzającego**:

1. **Jeden proces backendu** co ~5 s wykonuje `SELECT MAX(updated_at) FROM ch_player_stats_total`
   (i `SELECT MAX(id) FROM ch_kingdomcoins_log` dla KC).
2. Tylko gdy wartość się zmieni, odświeża w cache (pamięć lub Redis strony) widoczne rankingi i liczniki.
   Zapytania z sekcji 5 korzystają z indeksów i trwają milisekundy.
3. Nowe dane trafiają do przeglądarek przez **SSE lub WebSocket**. Prostsza alternatywa to polling frontendu co 5–10 s do endpointu backendu
   serwującego dane z cache.
4. Dashboard zalogowanego gracza może czytać swoje dane bezpośrednio z bazy (zapytania po kluczu głównym `uuid`).

Opóźnienie od zdarzenia w grze do ekranu wynosi ok. 5 s (plugin) + interwał backendu.

---

## 8. Checklista wdrożenia

**Backend**
- [ ] Usunięty `POST /stats` (i ewentualne tabele, które zasilał).
- [ ] `/link/verify` i `/link/{uuid}` działają jak w sekcji 2: HMAC na surowym body, wektor testowy przechodzi, akceptowany `charset` w `Content-Type`.
- [ ] Połączenie konta strony z UUID zapisane po stronie strony (unikalny `minecraft_uuid`), rozłączanie dostępne w panelu.
- [ ] Osobny użytkownik MySQL tylko do odczytu (+ ewentualnie zapis KC wg sekcji 6), z puli połączeń i TLS.
- [ ] Endpointy rankingów (6 kategorii + KC), filtr sieć/serwer, pozycja gracza.
- [ ] Endpoint dashboardu: sumy, saldo KC, rozbicie per serwer, historia KC, status online.
- [ ] Jeden „watcher” zmian (`MAX(updated_at)`, `MAX(id)` z logu KC) i cache, bez zapytań do bazy przy każdym odwiedzającym.
- [ ] Strona nie zmienia schematu tabel `ch_*` (może dodać własne indeksy).

**Frontend**
- [ ] `/leaderboards`: zakładki: przegrane godziny, KingdomCoins, postawione bloki, zabici gracze, zabite moby, śmierci (+ opcjonalnie K/D).
- [ ] Przełącznik „Cała sieć / serwer”.
- [ ] Dashboard gracza z połączonym kontem: statystyki, saldo KC, historia KC, status online, ostatnio widziany.
- [ ] Widok bez połączonego konta: instrukcja `/link <kod>` i generowanie kodu.
- [ ] Aktualizacja na żywo (SSE/WebSocket lub polling 5–10 s) bez przeładowania strony.
- [ ] Formatowanie: godziny z `playtime_seconds`, KC ze spacjami co 3 cyfry, czasy z Unix sekund w strefie Europe/Warsaw.
