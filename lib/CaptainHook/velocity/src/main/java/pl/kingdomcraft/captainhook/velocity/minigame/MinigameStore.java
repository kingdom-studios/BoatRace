package pl.kingdomcraft.captainhook.velocity.minigame;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import pl.kingdomcraft.captainhook.velocity.database.Tables;

/**
 * SQL of the minigame tables. Every method is blocking and runs inside a transaction on the database thread.
 *
 * - ch_minigame_stats          one row per (game, uuid, stat), read by the website for minigame leaderboards
 * - ch_minigame_pending_joins  one row per player who is being sent to a minigame server
 */
public final class MinigameStore {

	/**
	 * One line of a minigame leaderboard.
	 *
	 * @param name the player's last known name, empty if unknown
	 */
	public record TopEntry(UUID uuid, String name, long value) {
	}

	private MinigameStore() {
	}

	/* ------------------------------------------------------------------------------- */
	/* Stats */
	/* ------------------------------------------------------------------------------- */

	public static void applyStat(final Connection connection, final String game, final UUID uuid, final String stat, final StatOperation operation,
			final long value, final boolean sqlite) throws SQLException {
		final String incoming = sqlite ? "excluded.value" : "VALUES(value)";
		final String merged = switch (operation) {
			case ADD -> "value + " + incoming;
			case SET -> incoming;
			case MAX -> (sqlite ? "MAX" : "GREATEST") + "(value, " + incoming + ")";
			case MIN -> (sqlite ? "MIN" : "LEAST") + "(value, " + incoming + ")";
		};

		final String upsert = "INSERT INTO " + Tables.MINIGAME_STATS + " (game, uuid, stat, value, updated_at) VALUES (?, ?, ?, ?, ?) "
				+ (sqlite ? "ON CONFLICT(game, uuid, stat) DO UPDATE SET value = " + merged + ", updated_at = excluded.updated_at"
						: "ON DUPLICATE KEY UPDATE value = " + merged + ", updated_at = VALUES(updated_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, game);
			statement.setString(2, uuid.toString());
			statement.setString(3, stat);
			statement.setLong(4, value);
			statement.setLong(5, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}
	}

	/**
	 * @return the player's stats of the game by name, empty if they never played
	 */
	public static Map<String, Long> getStats(final Connection connection, final String game, final UUID uuid) throws SQLException {
		final Map<String, Long> stats = new LinkedHashMap<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT stat, value FROM " + Tables.MINIGAME_STATS + " WHERE game = ? AND uuid = ? ORDER BY stat")) {
			statement.setString(1, game);
			statement.setString(2, uuid.toString());

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					stats.put(result.getString(1), result.getLong(2));
			}
		}

		return stats;
	}

	/**
	 * @param ascending true for stats where lower is better (times)
	 */
	public static List<TopEntry> getTop(final Connection connection, final String game, final String stat, final int limit, final boolean ascending) throws SQLException {
		final List<TopEntry> top = new ArrayList<>(limit);

		// Names live in the KingdomCoins accounts, which every player gets on their first join
		try (PreparedStatement statement = connection.prepareStatement("SELECT s.uuid, c.name, s.value FROM " + Tables.MINIGAME_STATS + " s "
				+ "LEFT JOIN " + Tables.COINS + " c ON c.uuid = s.uuid "
				+ "WHERE s.game = ? AND s.stat = ? ORDER BY s.value " + (ascending ? "ASC" : "DESC") + ", s.updated_at ASC LIMIT ?")) {
			statement.setString(1, game);
			statement.setString(2, stat);
			statement.setInt(3, limit);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					final String name = result.getString(2);

					top.add(new TopEntry(UUID.fromString(result.getString(1)), name != null ? name : "", result.getLong(3)));
				}
			}
		}

		return top;
	}

	/* ------------------------------------------------------------------------------- */
	/* Pending joins */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Stores the join, replacing an older one of the same player.
	 */
	public static void putJoin(final Connection connection, final PendingJoin join, final boolean sqlite) throws SQLException {
		final String upsert = "INSERT INTO " + Tables.MINIGAME_PENDING_JOINS + " (uuid, game, server, arena, data, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
				+ (sqlite ? "ON CONFLICT(uuid) DO UPDATE SET game = excluded.game, server = excluded.server, arena = excluded.arena, data = excluded.data, "
						+ "created_at = excluded.created_at, expires_at = excluded.expires_at"
						: "ON DUPLICATE KEY UPDATE game = VALUES(game), server = VALUES(server), arena = VALUES(arena), data = VALUES(data), "
								+ "created_at = VALUES(created_at), expires_at = VALUES(expires_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, join.uuid().toString());
			statement.setString(2, join.game());
			statement.setString(3, join.server());
			statement.setString(4, join.arena());
			statement.setString(5, join.data());
			statement.setLong(6, join.createdAt());
			statement.setLong(7, join.expiresAt());
			statement.executeUpdate();
		}
	}

	/**
	 * Returns and deletes the player's pending join if it is meant for this server. An expired join is deleted
	 * and not returned; a join for another server stays.
	 *
	 * @return the join, or null
	 */
	public static PendingJoin takeJoin(final Connection connection, final UUID uuid, final String server) throws SQLException {
		final PendingJoin join;

		try (PreparedStatement statement = connection.prepareStatement("SELECT game, server, arena, data, created_at, expires_at FROM " + Tables.MINIGAME_PENDING_JOINS + " WHERE uuid = ?")) {
			statement.setString(1, uuid.toString());

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next())
					return null;

				join = new PendingJoin(uuid, result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getLong(5), result.getLong(6));
			}
		}

		final boolean expired = join.expiresAt() <= System.currentTimeMillis() / 1000;

		if (!expired && !join.server().equalsIgnoreCase(server))
			return null;

		// Two takers cannot both get it: only the one whose DELETE removed the row wins
		return deleteJoin(connection, uuid) && !expired ? join : null;
	}

	/**
	 * @return true if the player had a pending join
	 */
	public static boolean deleteJoin(final Connection connection, final UUID uuid) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + Tables.MINIGAME_PENDING_JOINS + " WHERE uuid = ?")) {
			statement.setString(1, uuid.toString());

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * @return the number of expired joins removed
	 */
	public static int purgeExpiredJoins(final Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + Tables.MINIGAME_PENDING_JOINS + " WHERE expires_at <= ?")) {
			statement.setLong(1, System.currentTimeMillis() / 1000);

			return statement.executeUpdate();
		}
	}
}
