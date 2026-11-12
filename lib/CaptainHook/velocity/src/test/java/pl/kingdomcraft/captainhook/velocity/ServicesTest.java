package pl.kingdomcraft.captainhook.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition;
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements;
import pl.kingdomcraft.captainhook.velocity.coins.CoinResult;
import pl.kingdomcraft.captainhook.velocity.coins.CoinTransaction;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.coins.TransactionType;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.database.Tables;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi.StatChange;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameStore;
import pl.kingdomcraft.captainhook.velocity.minigame.PendingJoin;
import pl.kingdomcraft.captainhook.velocity.minigame.StatOperation;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass.ClaimStatus;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassStore;
import pl.kingdomcraft.captainhook.velocity.pass.Track;
import pl.kingdomcraft.captainhook.velocity.stats.PlayerSnapshot;
import pl.kingdomcraft.captainhook.velocity.stats.StatsService;

/**
 * The services against a real database and a fake network: SQLite by default, MySQL when the environment
 * variable CAPTAINHOOK_TEST_MYSQL holds a JDBC URL (user and password in the URL). The MySQL database is wiped.
 */
class ServicesTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(ServicesTest.class);

	private static final String SEASON_YAML = """
			enabled: true
			season:
			  id: "test"
			  name: "Test season"
			xp-per-level: 100
			tiers:
			  1:
			    free: { kingdomcoins: 5 }
			    premium: { kingdomcoins: 50, commands: ["give {player} diamond 1"], message: "A diamond" }
			  3:
			    free: { kingdomcoins: 7 }
			""";

	private static final String ACHIEVEMENTS_YAML = """
			achievements:
			  collector:
			    name: "Collector"
			    description: "Collect 3 things."
			    goal: 3
			    rewards: { kingdomcoins: 20, pass-xp: 150 }
			  first_blood:
			    name: "First blood"
			    goal: 1
			    source: { type: network-stat, stat: player_kills }
			    rewards: { kingdomcoins: 10 }
			  survivor:
			    name: "Survivor"
			    goal: 2
			    source: { type: minigame-stat, game: house_of_terror, stat: wins }
			  speedster:
			    name: "Speedster"
			    source: { type: minigame-stat, game: boatrace, stat: time_track1, at-most: 60000 }
			""";

	@TempDir
	Path directory;

	private Database database;
	private FakeNetwork network;
	private KingdomCoins coins;
	private SeasonPass seasonPass;
	private Achievements achievements;
	private StatsService stats;
	private MinigameApi minigames;

	@BeforeEach
	void start() throws Exception {
		this.database = TestDatabases.open(this.directory);

		this.network = new FakeNetwork();
		this.coins = new KingdomCoins(this.database, this.network, LOGGER);
		this.seasonPass = new SeasonPass(this.database, this.coins, this.network, SeasonPassSettings.parse(YamlFile.parse("seasonpass.yml", new StringReader(SEASON_YAML))));
		this.achievements = new Achievements(this.database, this.seasonPass, this.network,
				AchievementDefinition.parseAll(YamlFile.parse("achievements.yml", new StringReader(ACHIEVEMENTS_YAML))));
		this.stats = new StatsService(this.database, this.achievements, this.network, LOGGER);
		this.minigames = new MinigameApi(this.database, this.achievements, this.network, LOGGER, 60);
	}

	@AfterEach
	void stop() {
		this.database.shutdown();
	}

	/* ------------------------------------------------------------------------------- */
	/* KingdomCoins */
	/* ------------------------------------------------------------------------------- */

	@Test
	void coinsAreChangedAtomicallyAndLogged() throws Exception {
		final UUID alice = this.player("Alice", "hub");
		final UUID bob = this.player("Bob", "survival");

		assertEquals(100, await(this.coins.change(alice, 100, TransactionType.GIVE, "CONSOLE", "hub", "welcome")).balance());
		assertEquals(CoinResult.Status.INSUFFICIENT_FUNDS, await(this.coins.change(alice, -101, TransactionType.TAKE, "CONSOLE", "hub", null)).status());
		assertEquals(CoinResult.Status.NOT_FOUND, await(this.coins.change(UUID.randomUUID(), 1, TransactionType.GIVE, "CONSOLE", "hub", null)).status());

		assertEquals(60, await(this.coins.pay(alice, bob, 40, "hub")).balance());
		assertEquals(CoinResult.Status.INSUFFICIENT_FUNDS, await(this.coins.pay(alice, bob, 61, "hub")).status());
		assertEquals(40, await(this.coins.find("bob")).balance(), "Names are looked up case-insensitively");
		assertNull(await(this.coins.find("Nobody")));

		final List<CoinTransaction> history = await(this.coins.getHistory(alice, 10, 0));

		assertEquals(List.of("PAY_SENT", "GIVE"), history.stream().map(CoinTransaction::type).toList(), "Refused changes leave no log entry");
		assertEquals(-40, history.get(0).amount());
		assertEquals("hub", history.get(0).server());

		// Both players are told their new balance on the server they are on
		assertEquals(60, Json.getLong(last(this.network.sent(alice, Protocol.COINS_BALANCE)), "balance", -1));
		assertEquals(40, Json.getLong(last(this.network.sent(bob, Protocol.COINS_BALANCE)), "balance", -1));

		assertThrows(IllegalArgumentException.class, () -> this.coins.pay(alice, alice, 1, "hub"));
		assertThrows(IllegalArgumentException.class, () -> this.coins.change(alice, 0, TransactionType.GIVE, "CONSOLE", "hub", null));
		assertThrows(IllegalArgumentException.class, () -> this.coins.set(alice, -1, "CONSOLE", "hub", null));
	}

	@Test
	void refreshPicksUpChangesMadeOutsideThePlugin() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		// The website credits coins directly in the database
		this.database.submit(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.COINS + " SET balance = balance + 500 WHERE uuid = ?")) {
				statement.setString(1, alice.toString());
				return statement.executeUpdate();
			}
		}).get();

		this.coins.refresh();
		this.flush();

		assertEquals(500, Json.getLong(last(this.network.sent(alice, Protocol.COINS_BALANCE)), "balance", -1));
		assertEquals(1, this.network.broadcasts.size(), "The top list changed, so every server gets it");
		assertEquals("Alice", this.coins.getTop().get(0).name());

		this.coins.refresh();
		this.flush();

		assertEquals(1, this.network.sent(alice, Protocol.COINS_BALANCE).size(), "Nothing changed, nothing is sent");
		assertEquals(1, this.network.broadcasts.size());
	}

	/* ------------------------------------------------------------------------------- */
	/* Season pass */
	/* ------------------------------------------------------------------------------- */

	@Test
	void passXpBecomesLevelsAndIsAnnounced() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		assertEquals(0, await(this.seasonPass.addXp(alice, 99)).level());

		final SeasonPass.XpChange change = await(this.seasonPass.addXp(alice, 250));

		assertEquals(349, change.state().xp());
		assertEquals(0, change.levelBefore());
		assertEquals(3, change.level());
		assertEquals(3, await(this.seasonPass.addXp(alice, 10_000)).level(), "The level stops at the highest tier, XP keeps counting");

		final JsonObject update = this.network.sent(alice, Protocol.PASS_UPDATE).get(1);

		assertEquals(250, Json.getLong(update, "gained", 0));
		assertEquals(0, Json.getInt(update, "levelBefore", -1));
		assertEquals(3, Json.getInt(update, "level", -1));
		assertEquals("Test season", Json.getString(update, "name", ""));

		assertThrows(IllegalArgumentException.class, () -> this.seasonPass.addXp(alice, 0));
	}

	@Test
	void tierIsClaimedOnceAndPaysInTheSameTransaction() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		assertEquals(ClaimStatus.LEVEL_TOO_LOW, await(this.seasonPass.claim(alice, 1, Track.FREE, "game")).status());

		await(this.seasonPass.addXp(alice, 100));

		assertEquals(ClaimStatus.UNKNOWN_TIER, await(this.seasonPass.claim(alice, 2, Track.FREE, "game")).status());
		assertEquals(ClaimStatus.PREMIUM_REQUIRED, await(this.seasonPass.claim(alice, 1, Track.PREMIUM, "game")).status());
		assertEquals(ClaimStatus.SUCCESS, await(this.seasonPass.claim(alice, 1, Track.FREE, "game")).status());
		assertEquals(ClaimStatus.ALREADY_CLAIMED, await(this.seasonPass.claim(alice, 1, Track.FREE, "web")).status());
		assertEquals(5, await(this.coins.find("Alice")).balance());

		this.database.submit(connection -> {
			SeasonPassStore.setPremium(connection, "test", alice, true, this.database.isSqlite());
			return null;
		}).get();

		assertEquals(ClaimStatus.SUCCESS, await(this.seasonPass.claim(alice, 1, Track.PREMIUM, "web")).status());
		assertEquals(55, await(this.coins.find("Alice")).balance());
		assertEquals("REWARD", await(this.coins.getHistory(alice, 1, 0)).get(0).type());

		// The command of the premium reward waits in the queue for a Paper server
		assertEquals(List.of("seasonpass:test:1:premium|[\"give {player} diamond 1\"]|A diamond"), this.queuedRewards(alice));

		final SeasonPass.Progress progress = await(this.seasonPass.getProgress(alice));

		assertTrue(progress.state().premium());
		assertEquals(List.of("1:free", "1:premium"), progress.claims().stream().map(claim -> claim.tier() + ":" + claim.track()).toList());
	}

	@Test
	void noXpOutsideOfASeason() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		this.seasonPass.reload(SeasonPassSettings.parse(YamlFile.parse("seasonpass.yml", new StringReader(SEASON_YAML.replace("id: \"test\"", "id: \"test\"\n  ends: \"2020-01-01\"")))));

		final ServiceException refused = assertThrows(ServiceException.class, () -> this.seasonPass.addXp(alice, 10));

		assertEquals(Protocol.ERROR_NO_SEASON, refused.getCode());
		assertEquals(ClaimStatus.NO_SEASON, await(this.seasonPass.claim(alice, 1, Track.FREE, "game")).status());
	}

	/* ------------------------------------------------------------------------------- */
	/* Achievements */
	/* ------------------------------------------------------------------------------- */

	@Test
	void manualAchievementUnlocksOnceWithItsRewards() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		assertFalse(await(this.achievements.addProgress(alice, "collector", 2)).newly());

		final Achievements.Progress unlocked = await(this.achievements.addProgress(alice, "collector", 5));

		assertTrue(unlocked.newly());
		assertEquals(3, unlocked.progress(), "Progress never passes the goal");
		assertNotNull(unlocked.unlockedAt());

		final Achievements.Progress again = await(this.achievements.addProgress(alice, "collector", 1));

		assertFalse(again.newly());
		assertNotNull(again.unlockedAt());
		assertEquals(20, await(this.coins.find("Alice")).balance(), "The reward is granted exactly once");
		assertEquals(150, await(this.seasonPass.getProgress(alice)).state().xp());

		assertEquals(1, this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).size());
		assertEquals("Collector", Json.getString(this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).get(0), "name", ""));
		assertEquals(1, Json.getInt(last(this.network.sent(alice, Protocol.PASS_UPDATE)), "level", -1), "Pass XP of the reward is announced too");

		assertEquals(Protocol.ERROR_UNKNOWN_ACHIEVEMENT, assertThrows(ServiceException.class, () -> this.achievements.addProgress(alice, "nope", 1)).getCode());
		assertEquals(Protocol.ERROR_BAD_REQUEST, assertThrows(ServiceException.class, () -> this.achievements.addProgress(alice, "first_blood", 1)).getCode());

		final List<Achievements.Progress> list = await(this.achievements.list(alice));

		assertEquals(List.of("collector", "first_blood", "survivor", "speedster"), list.stream().map(progress -> progress.definition().id()).toList());
		assertEquals(0, list.get(1).progress());
	}

	@Test
	void achievementsFollowNetworkAndMinigameStats() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		await(this.stats.save("hub", "hub", List.of(snapshot(alice, "Alice", 0, true))));
		this.flush();
		assertTrue(this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).isEmpty());

		await(this.stats.save("hub", "hub", List.of(snapshot(alice, "Alice", 1, true))));
		await(this.stats.save("hub", "hub", List.of(snapshot(alice, "Alice", 2, true))));
		this.flush();

		assertEquals(1, this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).size());
		assertEquals(10, await(this.coins.find("Alice")).balance());

		await(this.minigames.recordStats("house_of_terror", List.of(new StatChange(alice, "wins", StatOperation.ADD, 1))));
		this.flush();
		assertEquals(1, await(this.achievements.list(alice)).get(2).progress());

		await(this.minigames.recordStats("house_of_terror", List.of(new StatChange(alice, "wins", StatOperation.ADD, 1))));
		this.flush();

		assertEquals(List.of("first_blood", "survivor"), this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).stream().map(data -> Json.getString(data, "id", "")).toList());
	}

	@Test
	void atMostAchievementUnlocksOnceTheBestTimeIsLowEnough() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		// Another stat of the game must not count as a time of 0
		await(this.minigames.recordStats("boatrace", List.of(new StatChange(alice, "wins", StatOperation.ADD, 1))));
		this.flush();
		assertEquals(0, await(this.achievements.list(alice)).get(3).progress());

		await(this.minigames.recordStats("boatrace", List.of(new StatChange(alice, "time_track1", StatOperation.MIN, 75000))));
		this.flush();
		assertEquals(0, await(this.achievements.list(alice)).get(3).progress());
		assertTrue(this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).isEmpty());

		await(this.minigames.recordStats("boatrace", List.of(new StatChange(alice, "time_track1", StatOperation.MIN, 58000))));
		this.flush();

		assertEquals(1, await(this.achievements.list(alice)).get(3).progress());
		assertEquals(List.of("speedster"), this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).stream().map(data -> Json.getString(data, "id", "")).toList());
	}

	/* ------------------------------------------------------------------------------- */
	/* Stats */
	/* ------------------------------------------------------------------------------- */

	@Test
	void totalsSumAllServersAndTheProxyDecidesWhoIsOnline() throws Exception {
		final UUID alice = this.player("Alice", "survival");

		// "hub" still believes Alice is online there, but the proxy knows she moved to survival
		await(this.stats.save("hub", "hub", List.of(snapshot(alice, "Alice", 2, true))));
		await(this.stats.save("survival-1", "survival", List.of(snapshot(alice, "Alice", 5, true))));

		assertEquals("0|hub,1|survival-1", this.onlineFlags(alice));
		assertEquals("7|1", this.total(alice));

		this.network.locations.remove(alice);
		this.stats.playerLeft("survival-1", alice);
		this.flush();

		assertEquals("0|hub,0|survival-1", this.onlineFlags(alice));
		assertEquals("7|0", this.total(alice));

		// After a proxy crash the flags are still set
		this.network.locations.put(alice, "survival");
		await(this.stats.save("survival-1", "survival", List.of(snapshot(alice, "Alice", 6, true))));
		assertEquals("8|1", this.total(alice));
		assertEquals(1, await(this.stats.markAllOffline()));
		assertEquals("8|0", this.total(alice));
		assertEquals(0, await(this.stats.markAllOffline()));
	}

	@Test
	void snapshotsFromTheWireAreValidated() {
		final JsonObject json = new JsonObject();

		json.addProperty("uuid", UUID.randomUUID().toString());
		json.addProperty("name", "Alice");
		json.addProperty("balance", "12.345");
		json.addProperty("pkills", -5);
		json.addProperty("vanished", true);

		final PlayerSnapshot snapshot = PlayerSnapshot.fromJson(json);

		assertEquals(new BigDecimal("12.35"), snapshot.balance());
		assertEquals(0, snapshot.playerKills());
		assertFalse(snapshot.vanished(), "Only an online player can be vanished");

		json.addProperty("balance", "NaN");
		assertEquals(0, PlayerSnapshot.fromJson(json).balance().signum());

		json.addProperty("name", "");
		assertNull(PlayerSnapshot.fromJson(json));
	}

	/* ------------------------------------------------------------------------------- */
	/* Minigames */
	/* ------------------------------------------------------------------------------- */

	@Test
	void minigameStatsSupportCountersAndRecords() throws Exception {
		final UUID alice = this.player("Alice", "terror");
		final UUID bob = this.player("Bob", "terror");

		await(this.minigames.recordStats("house_of_terror", List.of(
				new StatChange(alice, "wins", StatOperation.ADD, 2),
				new StatChange(alice, "wins", StatOperation.ADD, 3),
				new StatChange(alice, "best_time", StatOperation.MIN, 90),
				new StatChange(alice, "best_time", StatOperation.MIN, 75),
				new StatChange(alice, "best_time", StatOperation.MIN, 80),
				new StatChange(alice, "best_score", StatOperation.MAX, 10),
				new StatChange(alice, "best_score", StatOperation.MAX, 4),
				new StatChange(alice, "rank", StatOperation.SET, 9),
				new StatChange(alice, "rank", StatOperation.SET, 2),
				new StatChange(bob, "wins", StatOperation.ADD, 1),
				new StatChange(bob, "best_time", StatOperation.MIN, 60))));

		assertEquals(Map.of("wins", 5L, "best_time", 75L, "best_score", 10L, "rank", 2L), await(this.minigames.getStats("house_of_terror", alice)));
		assertTrue(await(this.minigames.getStats("other_game", alice)).isEmpty());

		assertEquals(List.of("Alice=5", "Bob=1"), top(await(this.minigames.getTop("house_of_terror", "wins", 10, false))));
		assertEquals(List.of("Bob=60", "Alice=75"), top(await(this.minigames.getTop("house_of_terror", "best_time", 10, true))));
		assertEquals(1, await(this.minigames.getTop("house_of_terror", "wins", 1, false)).size());

		assertEquals(Protocol.ERROR_BAD_REQUEST, assertThrows(ServiceException.class, () -> this.minigames.recordStats("House Of Terror", List.of())).getCode());
		assertEquals(Protocol.ERROR_BAD_REQUEST,
				assertThrows(ServiceException.class, () -> this.minigames.recordStats("house_of_terror", List.of(new StatChange(alice, "bad stat", StatOperation.ADD, 1)))).getCode());
	}

	@Test
	void pendingJoinFollowsThePlayerToTheMinigameServer() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		this.network.servers.add("terror");

		final MinigameApi.JoinResult queued = await(this.minigames.queueJoin(alice, "house_of_terror", "terror", "mansion", "{\"party\":1}", 0, true));

		assertTrue(queued.connecting());
		assertFalse(queued.delivered());
		assertEquals(List.of(alice + ">terror"), this.network.connections);

		assertNull(await(this.minigames.takeJoin(alice, "hub")), "The join is for another server and stays");

		final PendingJoin join = await(this.minigames.takeJoin(alice, "terror"));

		assertEquals("house_of_terror", join.game());
		assertEquals("mansion", join.arena());
		assertEquals("{\"party\":1}", join.data());
		assertNull(await(this.minigames.takeJoin(alice, "terror")), "A join is handed over once");

		// Already on the target server: nobody will report the player ready, so the join is delivered right away
		this.network.locations.put(alice, "terror");

		assertTrue(await(this.minigames.queueJoin(alice, "house_of_terror", "terror", null, null, 0, true)).delivered());
		assertEquals("house_of_terror", Json.getString(last(this.network.sent(alice, Protocol.MINIGAME_JOIN)), "game", ""));
		assertNull(await(this.minigames.takeJoin(alice, "terror")));

		assertEquals(Protocol.ERROR_UNKNOWN_SERVER, assertThrows(ServiceException.class, () -> this.minigames.queueJoin(alice, "house_of_terror", "nowhere", null, null, 0, true)).getCode());
		assertEquals(Protocol.ERROR_PLAYER_OFFLINE,
				assertThrows(ServiceException.class, () -> this.minigames.queueJoin(UUID.randomUUID(), "house_of_terror", "terror", null, null, 0, true)).getCode());
	}

	@Test
	void expiredAndCancelledJoinsAreNotDelivered() throws Exception {
		final UUID alice = this.player("Alice", "hub");
		final UUID bob = this.player("Bob", "hub");
		final long now = System.currentTimeMillis() / 1000;

		this.network.servers.add("terror");

		this.database.submit(connection -> {
			MinigameStore.putJoin(connection, new PendingJoin(alice, "house_of_terror", "terror", null, null, now - 120, now - 60), this.database.isSqlite());
			return null;
		}).get();

		assertNull(await(this.minigames.takeJoin(alice, "terror")));

		await(this.minigames.queueJoin(bob, "house_of_terror", "terror", null, null, 30, false));
		assertTrue(this.network.connections.isEmpty(), "connect = false only stores the join");
		assertTrue(await(this.minigames.cancelJoin(bob)));
		assertFalse(await(this.minigames.cancelJoin(bob)));
		assertNull(await(this.minigames.takeJoin(bob, "terror")));
	}

	/* ------------------------------------------------------------------------------- */
	/* Helpers */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Puts a player on a server and creates their KingdomCoins account, like joining the network does.
	 */
	private UUID player(final String name, final String server) throws Exception {
		final UUID uuid = this.network.join(server);

		await(this.coins.load(uuid, name));
		return uuid;
	}

	private static PlayerSnapshot snapshot(final UUID uuid, final String name, final int playerKills, final boolean online) {
		return new PlayerSnapshot(uuid, name, BigDecimal.ZERO, 0, 60, 0, playerKills, 0, 0, null, online, false, System.currentTimeMillis() / 1000);
	}

	private static <T> T await(final CompletableFuture<T> future) throws Exception {
		try {
			return future.get(10, TimeUnit.SECONDS);

		} catch (final ExecutionException ex) {
			throw assertInstanceOf(Exception.class, ex.getCause());
		}
	}

	/**
	 * Waits until everything queued on the database thread so far has run, including follow-up transactions
	 * and their callbacks.
	 */
	private void flush() throws Exception {
		this.database.submit(connection -> null).get(10, TimeUnit.SECONDS);
	}

	private static <T> T last(final List<T> list) {
		assertFalse(list.isEmpty(), "Nothing was sent");

		return list.get(list.size() - 1);
	}

	private static List<String> top(final List<MinigameStore.TopEntry> entries) {
		return entries.stream().map(entry -> entry.name() + "=" + entry.value()).toList();
	}

	private String onlineFlags(final UUID uuid) throws Exception {
		return this.query("SELECT CONCAT(online, '|', server_id) FROM " + Tables.STATS + " WHERE uuid = ? ORDER BY server_id", uuid);
	}

	private String total(final UUID uuid) throws Exception {
		return this.query("SELECT CONCAT(player_kills, '|', online) FROM " + Tables.STATS_TOTAL + " WHERE uuid = ?", uuid);
	}

	private List<String> queuedRewards(final UUID uuid) throws Exception {
		return List.of(this.query("SELECT CONCAT(source, '|', commands, '|', message) FROM " + Tables.REWARD_QUEUE + " WHERE uuid = ? AND delivered_at IS NULL ORDER BY id", uuid).split(","));
	}

	/**
	 * @return the first column of all rows, joined with commas
	 */
	private String query(final String sql, final UUID uuid) throws Exception {
		return this.database.submit(connection -> {
			final StringBuilder rows = new StringBuilder();

			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setString(1, uuid.toString());

				try (ResultSet result = statement.executeQuery()) {
					while (result.next())
						rows.append(rows.length() > 0 ? "," : "").append(result.getString(1));
				}
			}

			return rows.toString();
		}).get(10, TimeUnit.SECONDS);
	}
}
