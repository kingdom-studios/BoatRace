package pl.kingdomcraft.captainhook.velocity.achievement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import pl.kingdomcraft.captainhook.velocity.database.Tables;

/**
 * SQL of ch_achievements: one row per (uuid, achievement) once the player has any progress. Every method is
 * blocking and runs inside a transaction on the database thread.
 */
public final class AchievementStore {

	private static final int IN_CLAUSE_CHUNK = 500;

	/**
	 * @param unlockedAt Unix seconds, null while locked
	 * @param claimedAt  Unix seconds the reward was granted, null until then
	 */
	public record Row(long progress, Long unlockedAt, Long claimedAt) {

		public boolean isUnlocked() {
			return this.unlockedAt != null;
		}

		public boolean isClaimed() {
			return this.claimedAt != null;
		}
	}

	private AchievementStore() {
	}

	/**
	 * @return rows by achievement ID
	 */
	public static Map<String, Row> getRows(final Connection connection, final UUID uuid) throws SQLException {
		return getRows(connection, Collections.singletonList(uuid)).getOrDefault(uuid, Collections.emptyMap());
	}

	/**
	 * @return rows by player and achievement ID, players without any row are missing
	 */
	public static Map<UUID, Map<String, Row>> getRows(final Connection connection, final Collection<UUID> uuids) throws SQLException {
		final List<UUID> list = new ArrayList<>(uuids);
		final Map<UUID, Map<String, Row>> rows = new HashMap<>();

		for (int from = 0; from < list.size(); from += IN_CLAUSE_CHUNK) {
			final List<UUID> chunk = list.subList(from, Math.min(from + IN_CLAUSE_CHUNK, list.size()));
			final String placeholders = String.join(", ", Collections.nCopies(chunk.size(), "?"));

			try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, achievement, progress, unlocked_at, claimed_at FROM " + Tables.ACHIEVEMENTS
					+ " WHERE uuid IN (" + placeholders + ")")) {
				for (int i = 0; i < chunk.size(); i++)
					statement.setString(i + 1, chunk.get(i).toString());

				try (ResultSet result = statement.executeQuery()) {
					while (result.next())
						rows.computeIfAbsent(UUID.fromString(result.getString(1)), uuid -> new HashMap<>())
								.put(result.getString(2), new Row(result.getLong(3), nullableLong(result, 4), nullableLong(result, 5)));
				}
			}
		}

		return rows;
	}

	/**
	 * Stores the progress of a locked achievement. Unlocked rows are left alone.
	 */
	public static void setProgress(final Connection connection, final UUID uuid, final String achievement, final long progress, final boolean sqlite) throws SQLException {
		final String upsert = "INSERT INTO " + Tables.ACHIEVEMENTS + " (uuid, achievement, progress, updated_at) VALUES (?, ?, ?, ?) "
				+ (sqlite ? "ON CONFLICT(uuid, achievement) DO UPDATE SET progress = excluded.progress, updated_at = excluded.updated_at WHERE unlocked_at IS NULL"
						: "ON DUPLICATE KEY UPDATE progress = IF(unlocked_at IS NULL, VALUES(progress), progress), updated_at = IF(unlocked_at IS NULL, VALUES(updated_at), updated_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, uuid.toString());
			statement.setString(2, achievement);
			statement.setLong(3, progress);
			statement.setLong(4, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}
	}

	private static Long nullableLong(final ResultSet result, final int column) throws SQLException {
		final long value = result.getLong(column);

		return result.wasNull() ? null : value; // wasNull must be asked right after reading the column
	}

	/**
	 * Marks the achievement unlocked.
	 *
	 * @param claimed true if its reward is granted in this same transaction (automatic rewards)
	 * @return false if it was unlocked already (or has no row), so rewards must not be granted again
	 */
	public static boolean unlock(final Connection connection, final UUID uuid, final String achievement, final boolean claimed) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;

		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.ACHIEVEMENTS
				+ " SET unlocked_at = ?, claimed_at = ?, updated_at = ? WHERE uuid = ? AND achievement = ? AND unlocked_at IS NULL")) {
			statement.setLong(1, now);

			if (claimed)
				statement.setLong(2, now);
			else
				statement.setNull(2, java.sql.Types.BIGINT);

			statement.setLong(3, now);
			statement.setString(4, uuid.toString());
			statement.setString(5, achievement);

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * Marks the reward of an unlocked achievement claimed. The website runs the same statement.
	 *
	 * @return false if it is locked or was claimed already, so the reward must not be granted
	 */
	public static boolean claim(final Connection connection, final UUID uuid, final String achievement) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;

		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.ACHIEVEMENTS
				+ " SET claimed_at = ?, updated_at = ? WHERE uuid = ? AND achievement = ? AND unlocked_at IS NOT NULL AND claimed_at IS NULL")) {
			statement.setLong(1, now);
			statement.setLong(2, now);
			statement.setString(3, uuid.toString());
			statement.setString(4, achievement);

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * Copies unlocks from an old SQLite database, so achievements unlocked (and rewarded) there do not pay out
	 * again. Progress of locked achievements is not merged.
	 *
	 * @return the number of unlocks copied
	 */
	public static long mergeUnlocks(final Connection source, final Connection target, final boolean targetSqlite) throws SQLException {
		final String upsert = "INSERT INTO " + Tables.ACHIEVEMENTS + " (uuid, achievement, progress, unlocked_at, updated_at, claimed_at) VALUES (?, ?, ?, ?, ?, ?) "
				+ (targetSqlite ? "ON CONFLICT(uuid, achievement) DO UPDATE SET progress = excluded.progress, unlocked_at = excluded.unlocked_at, "
						+ "updated_at = excluded.updated_at, claimed_at = excluded.claimed_at WHERE unlocked_at IS NULL"
						: "ON DUPLICATE KEY UPDATE progress = IF(unlocked_at IS NULL, VALUES(progress), progress), updated_at = IF(unlocked_at IS NULL, VALUES(updated_at), updated_at), "
								+ "claimed_at = IF(unlocked_at IS NULL, VALUES(claimed_at), claimed_at), unlocked_at = COALESCE(unlocked_at, VALUES(unlocked_at))");
		long merged = 0;

		// A file of an older version has no claimed_at: everything unlocked there was rewarded right away
		final String claimed = hasColumn(source, Tables.ACHIEVEMENTS, "claimed_at") ? "claimed_at" : "unlocked_at";

		try (PreparedStatement read = source.prepareStatement("SELECT uuid, achievement, progress, unlocked_at, updated_at, " + claimed + " FROM " + Tables.ACHIEVEMENTS
				+ " WHERE unlocked_at IS NOT NULL");
				ResultSet rows = read.executeQuery();
				PreparedStatement write = target.prepareStatement(upsert)) {

			while (rows.next()) {
				write.setString(1, rows.getString(1));
				write.setString(2, rows.getString(2));
				write.setLong(3, rows.getLong(3));
				write.setLong(4, rows.getLong(4));
				write.setLong(5, rows.getLong(5));
				write.setObject(6, rows.getObject(6));
				write.executeUpdate();

				merged++;
			}
		}

		return merged;
	}

	private static boolean hasColumn(final Connection sqlite, final String table, final String column) throws SQLException {
		try (PreparedStatement statement = sqlite.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) {
			statement.setString(1, table);
			statement.setString(2, column);

			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}
}
