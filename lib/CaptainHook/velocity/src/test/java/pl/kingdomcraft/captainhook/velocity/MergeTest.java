package pl.kingdomcraft.captainhook.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kingdomcraft.captainhook.velocity.achievement.AchievementStore;
import pl.kingdomcraft.captainhook.velocity.coins.CoinStore;
import pl.kingdomcraft.captainhook.velocity.coins.TransactionType;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.database.Schema;
import pl.kingdomcraft.captainhook.velocity.pass.PassState;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassStore;
import pl.kingdomcraft.captainhook.velocity.pass.Track;
import pl.kingdomcraft.captainhook.velocity.stats.PlayerSnapshot;
import pl.kingdomcraft.captainhook.velocity.stats.StatsStore;

/**
 * Merging an SQLite fallback database into the main one, here with SQLite standing in for MySQL as the target.
 */
class MergeTest {

	@Test
	void fallbackDataIsMergedWithoutPayingTwice(@TempDir final Path directory) throws Exception {
		final UUID alice = UUID.randomUUID();
		final UUID bob = UUID.randomUUID();
		final long now = System.currentTimeMillis() / 1000;
		final Path fallback = directory.resolve("database.db");
		final Database main = Database.openSqlite(directory.resolve("main.db"));

		try {
			// The main database: Alice has 100 coins, 50 XP, old stats and an unlocked achievement
			main.submit(connection -> {
				Schema.create(connection, true);

				CoinStore.load(connection, alice, "Alice", true);
				CoinStore.change(connection, alice, 100, TransactionType.GIVE, "CONSOLE", "hub", null);
				SeasonPassStore.addXp(connection, "s1", alice, 50, true);
				StatsStore.save(connection, "hub", List.of(snapshot(alice, "Alice", 3, now)), true);
				AchievementStore.setProgress(connection, alice, "veteran", 5, true);
				AchievementStore.unlock(connection, alice, "veteran", true);

				return null;
			}).get(10, TimeUnit.SECONDS);

			// The fallback, as written during an outage: it started empty
			Database.withSqlite(fallback, connection -> {
				Schema.create(connection, true);

				CoinStore.load(connection, alice, "Alice", true);
				CoinStore.change(connection, alice, 30, TransactionType.GIVE, "CONSOLE", "hub", null);
				CoinStore.load(connection, bob, "Bob", true);
				CoinStore.change(connection, bob, 7, TransactionType.GIVE, "CONSOLE", "hub", null);

				SeasonPassStore.addXp(connection, "s1", alice, 25, true);
				SeasonPassStore.insertClaim(connection, "s1", alice, 1, Track.FREE, "game", true);

				AchievementStore.setProgress(connection, alice, "builder", 9, true);
				AchievementStore.unlock(connection, alice, "builder", true);
				AchievementStore.setProgress(connection, alice, "veteran", 1, true); // locked there, unlocked in main
				AchievementStore.setProgress(connection, bob, "builder", 4, true); // locked, not merged

				StatsStore.save(connection, "hub", List.of(snapshot(alice, "Alice", 8, now), snapshot(bob, "Bob", 2, now)), true);

				// Make Alice's fallback row the newer one
				try (Statement statement = connection.createStatement()) {
					statement.executeUpdate("UPDATE ch_player_stats SET updated_at = updated_at + 100");
				}

				return null;
			});

			final long[] merged = main.submit(target -> Database.withSqlite(fallback, source -> {
				final long[] coins = CoinStore.merge(source, target, true, "proxy");

				return new long[] { coins[0], coins[1], StatsStore.merge(source, target, true), AchievementStore.mergeUnlocks(source, target, true), SeasonPassStore.merge(source, target, true) };
			})).get(10, TimeUnit.SECONDS);

			assertArrayEquals(new long[] { 2, 37, 2, 1, 1 }, merged, "accounts, coins, stats rows, unlocks, pass players");

			main.submit(connection -> {
				assertEquals(130, CoinStore.getBalance(connection, alice), "Balances are added, not replaced");
				assertEquals(7, CoinStore.getBalance(connection, bob));
				assertEquals(List.of("GIVE", "MERGE", "GIVE"), CoinStore.getHistory(connection, alice, 10, 0).stream().map(entry -> entry.type()).toList(),
						"Newest first: the copied log of the outage, the merge itself, the older history");

				assertEquals(new PassState("s1", 75, false), SeasonPassStore.get(connection, "s1", alice));
				assertFalse(SeasonPassStore.insertClaim(connection, "s1", alice, 1, Track.FREE, "game", true), "A tier claimed during the outage stays claimed");

				final var achievements = AchievementStore.getRows(connection, alice);

				assertTrue(achievements.get("builder").isUnlocked(), "An achievement rewarded during the outage must not pay again");
				assertEquals(9, achievements.get("builder").progress());
				assertNotNull(achievements.get("veteran").unlockedAt());
				assertEquals(5, achievements.get("veteran").progress(), "An unlock in the main database is not touched");
				assertTrue(AchievementStore.getRows(connection, bob).isEmpty(), "Progress of locked achievements is not merged");

				assertEquals(8, StatsStore.getTotals(connection, List.of(alice)).get(alice).get("player_kills"), "Newer stats replace older ones");
				assertEquals(2, StatsStore.getTotals(connection, List.of(bob)).get(bob).get("player_kills"));

				return null;
			}).get(10, TimeUnit.SECONDS);

		} finally {
			main.shutdown();
		}
	}

	private static PlayerSnapshot snapshot(final UUID uuid, final String name, final int playerKills, final long seen) {
		return new PlayerSnapshot(uuid, name, BigDecimal.ZERO, 0, 60, 0, playerKills, 0, 0, null, false, false, seen);
	}
}
