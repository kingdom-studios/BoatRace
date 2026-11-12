package pl.kingdomcraft.captainhook.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements.ClaimStatus;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.database.Tables;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.reward.RewardDispatcher;
import pl.kingdomcraft.captainhook.velocity.website.DefinitionPublisher;

/**
 * Claimed achievement rewards, publishing the configuration for the website and delivering the reward queue.
 */
class RewardsTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(RewardsTest.class);

	private static final String SEASON_YAML = """
			season:
			  id: "s2"
			  name: "Season two"
			xp-per-level: 100
			tiers:
			  1:
			    free: { kingdomcoins: 5 }
			    premium: { commands: ["give {player} diamond 1"], message: "A diamond" }
			  2:
			    free: { commands: ["give {player} netherite_sword 1"], require-server: "survival" }
			    premium: { kingdomcoins: 9 }
			""";

	private static final String ACHIEVEMENTS_YAML = """
			reward-mode: claim
			achievements:
			  collector:
			    name: "Collector"
			    goal: 2
			    rewards: { kingdomcoins: 20, commands: ["say {player} collected", "give {player} apple 1"] }
			  instant:
			    name: "Instant"
			    reward-mode: automatic
			    rewards: { kingdomcoins: 3 }
			  nothing:
			    name: "Nothing to win"
			""";

	@TempDir
	Path directory;

	private Database database;
	private FakeNetwork network;
	private KingdomCoins coins;
	private SeasonPassSettings season;
	private Achievements achievements;
	private RewardDispatcher dispatcher;

	@BeforeEach
	void start() throws Exception {
		this.database = TestDatabases.open(this.directory);
		this.network = new FakeNetwork();
		this.coins = new KingdomCoins(this.database, this.network, LOGGER);
		this.season = SeasonPassSettings.parse(YamlFile.parse("seasonpass.yml", new StringReader(SEASON_YAML)));

		final SeasonPass seasonPass = new SeasonPass(this.database, this.coins, this.network, this.season);

		this.achievements = new Achievements(this.database, seasonPass, this.network, definitions(ACHIEVEMENTS_YAML));
		this.dispatcher = new RewardDispatcher(this.database, this.network, LOGGER, 60);
	}

	@AfterEach
	void stop() {
		this.database.shutdown();
	}

	@Test
	void claimModeWaitsForTheClaimAndPaysOnce() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		final Achievements.Progress unlocked = await(this.achievements.addProgress(alice, "collector", 2));

		assertTrue(unlocked.newly());
		assertTrue(unlocked.isClaimable());
		assertEquals(0, await(this.coins.find("Alice")).balance(), "Nothing is granted before the claim");

		final JsonObject announced = this.network.sent(alice, Protocol.ACHIEVEMENT_UNLOCKED).get(0);

		assertTrue(Json.getBoolean(announced, "claimable", false));
		assertEquals(0, Json.getLong(announced, "coins", -1));

		assertEquals(ClaimStatus.SUCCESS, await(this.achievements.claim(alice, "collector")));
		assertEquals(ClaimStatus.ALREADY_CLAIMED, await(this.achievements.claim(alice, "collector")));
		assertEquals(20, await(this.coins.find("Alice")).balance());
		assertEquals(1, this.count("SELECT COUNT(*) FROM " + Tables.REWARD_QUEUE + " WHERE uuid = ?", alice), "The commands wait in the queue");

		assertEquals(ClaimStatus.NOT_UNLOCKED, await(this.achievements.claim(this.player("Bob", "hub"), "collector")));
		assertEquals(ClaimStatus.UNKNOWN_ACHIEVEMENT, await(this.achievements.claim(alice, "nope")));
		assertEquals(ClaimStatus.AUTOMATIC, await(this.achievements.claim(alice, "instant")));

		// Automatic: paid on unlock and recorded as claimed
		final Achievements.Progress instant = await(this.achievements.addProgress(alice, "instant", 1));

		assertFalse(instant.isClaimable());
		assertEquals(23, await(this.coins.find("Alice")).balance());

		final Map<String, Achievements.Progress> list = byId(await(this.achievements.list(alice)));

		assertTrue(list.get("collector").claimedAt() != null);
		assertTrue(list.get("instant").claimedAt() != null);
		assertFalse(list.get("nothing").isClaimable(), "Without a reward there is nothing to claim");
	}

	@Test
	void definitionsArePublishedForTheWebsite() throws Exception {
		this.publish(this.season, definitions(ACHIEVEMENTS_YAML));

		assertEquals("s2|1|100|2", this.text("SELECT CONCAT(season, '|', is_current, '|', xp_per_level, '|', max_level) FROM " + Tables.SEASON_PASS_SEASONS));
		assertEquals("1|free|5|[]|-,1|premium|0|[\"give {player} diamond 1\"]|-,2|free|0|[\"give {player} netherite_sword 1\"]|survival,2|premium|9|[]|-",
				this.text("SELECT CONCAT(tier, '|', track, '|', kingdomcoins, '|', commands, '|', COALESCE(require_server, '-')) FROM " + Tables.SEASON_PASS_REWARDS + " ORDER BY tier, track"));
		assertEquals("collector|0|20|1,instant|1|3|1,nothing|0|0|1",
				this.text("SELECT CONCAT(id, '|', auto_reward, '|', kingdomcoins, '|', active) FROM " + Tables.ACHIEVEMENT_DEFINITIONS + " ORDER BY sort_order"));

		// A new season and an achievement removed from the file
		final SeasonPassSettings next = SeasonPassSettings.parse(YamlFile.parse("seasonpass.yml", new StringReader(SEASON_YAML.replace("\"s2\"", "\"s3\""))));

		this.publish(next, definitions(ACHIEVEMENTS_YAML.replace("  nothing:\n    name: \"Nothing to win\"\n", "")));

		assertEquals("s2|0,s3|1", this.text("SELECT CONCAT(season, '|', is_current) FROM " + Tables.SEASON_PASS_SEASONS + " ORDER BY season"));
		assertEquals("collector|1,instant|1,nothing|0", this.text("SELECT CONCAT(id, '|', active) FROM " + Tables.ACHIEVEMENT_DEFINITIONS + " ORDER BY id"));
	}

	@Test
	void queueIsDeliveredToTheServerOfThePlayerAndConfirmed() throws Exception {
		final UUID alice = this.player("Alice", "hub");
		final UUID offline = this.player("Gone", "hub");

		this.publish(this.season, definitions(ACHIEVEMENTS_YAML));
		this.queue(alice, "seasonpass:s2:1:premium", "[\"give {player} diamond 1\"]");
		this.queue(offline, "achievement:collector", "[\"say {player} collected\", \"give {player} apple 1\"]");
		this.network.locations.remove(offline);

		this.dispatcher.tick();
		this.flush();

		final List<JsonObject> sent = this.network.sent(alice, Protocol.REWARD_DISPATCH);

		assertEquals(1, sent.size());
		assertEquals("give {player} diamond 1", Json.getArray(sent.get(0), "commands").get(0).getAsString());
		assertEquals(1, this.dispatcher.getInFlightCount(), "Only players on a server get their rewards");

		final long id = Json.getLong(sent.get(0), "id", 0);

		this.dispatcher.tick();
		this.flush();
		assertEquals(1, this.network.sent(alice, Protocol.REWARD_DISPATCH).size(), "Not sent again while waiting for the ack");

		assertFalse(await(this.dispatcher.acknowledge("survival", "survival-1", ack(id, true))), "An ack from another server is ignored");

		// The player left before it ran: forgotten, so the next round sends it again
		assertFalse(await(this.dispatcher.acknowledge("hub", "hub", ack(id, false))));
		this.dispatcher.tick();
		this.flush();
		assertEquals(2, this.network.sent(alice, Protocol.REWARD_DISPATCH).size());

		assertTrue(await(this.dispatcher.acknowledge("hub", "hub-1", ack(id, true))));
		assertEquals("hub-1", this.text("SELECT delivered_server FROM " + Tables.REWARD_QUEUE + " WHERE id = " + id));
		assertEquals(0, this.dispatcher.getInFlightCount());

		this.dispatcher.tick();
		this.flush();
		assertEquals(2, this.network.sent(alice, Protocol.REWARD_DISPATCH).size(), "Delivered rows are done");
	}

	@Test
	void serverBoundRewardWaitsForThatServer() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		this.network.servers.add("survival");
		this.publish(this.season, definitions(ACHIEVEMENTS_YAML));
		this.queue(alice, "seasonpass:s2:2:free", "[\"give {player} netherite_sword 1\"]", "survival");
		// A forged copy without the server restriction must not deliver the item in the hub
		this.queue(alice, "seasonpass:s2:2:free", "[\"give {player} netherite_sword 1\"]", null);

		this.dispatcher.tick();
		this.flush();
		assertTrue(this.network.sent(alice, Protocol.REWARD_DISPATCH).isEmpty(), "Stays queued while the player is in the hub");

		this.network.locations.put(alice, "survival");
		this.dispatcher.tick();
		this.flush();

		final List<JsonObject> sent = this.network.sent(alice, Protocol.REWARD_DISPATCH);

		assertEquals(1, sent.size(), "Sent on the required server, the forged copy never");
		assertEquals("seasonpass:s2:2:free", Json.getString(sent.get(0), "source", ""));
	}

	@Test
	void serverBoundRewardIsQueuedWithItsServer() throws Exception {
		final UUID alice = this.player("Alice", "hub");
		final SeasonPass seasonPass = new SeasonPass(this.database, this.coins, this.network, this.season);

		await(seasonPass.addXp(alice, 250));
		assertEquals(SeasonPass.ClaimStatus.SUCCESS, await(seasonPass.claim(alice, 2, pl.kingdomcraft.captainhook.velocity.pass.Track.FREE, "game")).status());
		assertEquals("survival", this.text("SELECT require_server FROM " + Tables.REWARD_QUEUE + " WHERE source = 'seasonpass:s2:2:free'"));
	}

	@Test
	void brokenAndForgedRowsAreNotSent() throws Exception {
		final UUID alice = this.player("Alice", "hub");

		this.publish(this.season, definitions(ACHIEVEMENTS_YAML));
		this.queue(alice, "seasonpass:s2:1:premium", "not json");
		// What a compromised website could write: real sources, other commands
		this.queue(alice, "seasonpass:s2:1:premium", "[\"op {player}\"]");
		this.queue(alice, "achievement:collector", "[\"say {player} collected\"]");
		this.queue(alice, "achievement:nope", "[\"say hi\"]");
		this.queue(alice, "admin:gift", "[\"say hi\"]");
		this.dispatcher.tick();
		this.flush();

		assertTrue(this.network.sent(alice, Protocol.REWARD_DISPATCH).isEmpty());

		// The genuine row goes through
		this.queue(alice, "achievement:collector", "[\"say {player} collected\",\"give {player} apple 1\"]");
		this.dispatcher.tick();
		this.flush();

		assertEquals(1, this.network.sent(alice, Protocol.REWARD_DISPATCH).size());
	}

	/* ------------------------------------------------------------------------------- */

	private static Map<String, AchievementDefinition> definitions(final String yaml) throws Exception {
		return AchievementDefinition.parseAll(YamlFile.parse("achievements.yml", new StringReader(yaml)));
	}

	private static Map<String, Achievements.Progress> byId(final List<Achievements.Progress> list) {
		final Map<String, Achievements.Progress> map = new java.util.HashMap<>();

		list.forEach(progress -> map.put(progress.definition().id(), progress));
		return map;
	}

	private static JsonObject ack(final long id, final boolean executed) {
		final JsonObject ack = new JsonObject();

		ack.addProperty("id", id);
		ack.addProperty("executed", executed);
		return ack;
	}

	private void publish(final SeasonPassSettings settings, final Map<String, AchievementDefinition> definitions) throws Exception {
		final boolean sqlite = this.database.isSqlite();

		this.database.submit(connection -> {
			DefinitionPublisher.publish(connection, sqlite, settings, definitions);
			return null;
		}).get(10, TimeUnit.SECONDS);
	}

	private void queue(final UUID uuid, final String source, final String commands) throws Exception {
		this.queue(uuid, source, commands, null);
	}

	private void queue(final UUID uuid, final String source, final String commands, final String requireServer) throws Exception {
		this.database.submit(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + Tables.REWARD_QUEUE
					+ " (uuid, source, commands, created_at, require_server) VALUES (?, ?, ?, 0, ?)")) {
				statement.setString(1, uuid.toString());
				statement.setString(2, source);
				statement.setString(3, commands);
				statement.setString(4, requireServer);
				return statement.executeUpdate();
			}
		}).get(10, TimeUnit.SECONDS);
	}

	private UUID player(final String name, final String server) throws Exception {
		final UUID uuid = this.network.join(server);

		await(this.coins.load(uuid, name));
		return uuid;
	}

	private void flush() throws Exception {
		this.database.submit(connection -> null).get(10, TimeUnit.SECONDS);
	}

	private static <T> T await(final CompletableFuture<T> future) throws Exception {
		return future.get(10, TimeUnit.SECONDS);
	}

	private long count(final String sql, final UUID uuid) throws Exception {
		return Long.parseLong(this.text(sql.replace("?", "'" + uuid + "'")));
	}

	/**
	 * @return the first column of all rows, joined with commas
	 */
	private String text(final String sql) throws Exception {
		return this.database.submit(connection -> {
			final List<String> rows = new ArrayList<>();

			try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
				while (result.next())
					rows.add(result.getString(1));
			}

			return String.join(",", rows);
		}).get(10, TimeUnit.SECONDS);
	}
}
