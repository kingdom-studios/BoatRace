package pl.kingdomcraft.captainhook.velocity.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

import pl.kingdomcraft.captainhook.velocity.achievement.AchievementStore;
import pl.kingdomcraft.captainhook.velocity.coins.CoinStore;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassStore;
import pl.kingdomcraft.captainhook.velocity.stats.StatsStore;

/**
 * Merges an SQLite fallback database into MySQL once the proxy is connected to MySQL again. The file is the
 * proxy's own database.db, or one copied over from a Paper server that ran the old single-server plugin.
 *
 * - KingdomCoins: the SQLite database starts empty, so it only holds coins earned during the outage and its
 *   balances are ADDED to MySQL. The transaction log is copied.
 * - Stats: rows newer than in MySQL replace them (they are absolute snapshots), network totals are recomputed.
 * - Season pass: XP is added and claims are copied. Achievements: unlocks are copied. Both so that nothing
 *   rewarded during the outage can be rewarded again.
 * - Minigame stats and progress of locked achievements are NOT merged: an absolute value and a difference
 *   cannot be told apart afterwards.
 *
 * Everything runs in one MySQL transaction, then the file is renamed so it can never be merged twice.
 * If the rename fails, the whole merge is rolled back.
 */
public final class SqliteMerge {

	/**
	 * What a merge moved.
	 */
	public record Result(long accounts, long coins, long statsRows, long unlocks, long passPlayers) {
	}

	private SqliteMerge() {
	}

	public static Path getFile(final Path dataDirectory) {
		return dataDirectory.resolve(Database.SQLITE_FILE);
	}

	/**
	 * @return {coin accounts, coins} stored in the SQLite file
	 */
	public static CompletableFuture<long[]> preview(final Database database, final Path dataDirectory) {
		return database.submit(ignored -> Database.withSqlite(getFile(dataDirectory), CoinStore::summarize));
	}

	public static CompletableFuture<Result> merge(final Database database, final Path dataDirectory) {
		if (!database.isMySql())
			throw new IllegalStateException("Merging requires MySQL");

		return database.submit(target -> {
			final Path file = getFile(dataDirectory);
			final Result merged = Database.withSqlite(file, source -> {
				final long[] coins = CoinStore.merge(source, target, false, "proxy");
				final long stats = StatsStore.merge(source, target, false);

				// Files of the old single-server plugin only have the coins and stats tables
				final long unlocks = hasTable(source, Tables.ACHIEVEMENTS) ? AchievementStore.mergeUnlocks(source, target, false) : 0;
				final long pass = hasTable(source, Tables.SEASON_PASS) && hasTable(source, Tables.SEASON_PASS_CLAIMS) ? SeasonPassStore.merge(source, target, false) : 0;

				return new Result(coins[0], coins[1], stats, unlocks, pass);
			});

			final Path renamed = file.resolveSibling(file.getFileName() + ".merged-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));

			try {
				Files.move(file, renamed);

			} catch (final IOException ex) {
				throw new SQLException("Merged, but could not rename " + file.getFileName() + ", rolling back to prevent merging twice", ex);
			}

			return merged;
		});
	}

	private static boolean hasTable(final Connection sqlite, final String table) throws SQLException {
		try (PreparedStatement statement = sqlite.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
			statement.setString(1, table);

			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}
}
