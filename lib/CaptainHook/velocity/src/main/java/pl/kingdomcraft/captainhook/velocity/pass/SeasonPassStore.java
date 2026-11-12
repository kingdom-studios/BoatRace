package pl.kingdomcraft.captainhook.velocity.pass;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import pl.kingdomcraft.captainhook.velocity.database.Tables;

/**
 * SQL of the season pass tables. Every method is blocking and runs inside a transaction on the database thread.
 *
 * - ch_seasonpass         one row per (season, uuid): xp and whether the player owns the premium track
 * - ch_seasonpass_claims  one row per claimed (season, uuid, tier, track); the primary key makes a double
 *                         claim impossible, whoever claims (the game or the website)
 */
public final class SeasonPassStore {

	/**
	 * A claimed reward: the tier and the track key ("free" or "premium").
	 */
	public record Claim(int tier, String track) {
	}

	private SeasonPassStore() {
	}

	/**
	 * Adds XP with an atomic relative update, creating the row if needed.
	 *
	 * @return the state after the change
	 */
	public static PassState addXp(final Connection connection, final String season, final UUID uuid, final long amount, final boolean sqlite) throws SQLException {
		final String upsert = "INSERT INTO " + Tables.SEASON_PASS + " (season, uuid, xp, premium, updated_at) VALUES (?, ?, ?, 0, ?) "
				+ (sqlite ? "ON CONFLICT(season, uuid) DO UPDATE SET xp = xp + excluded.xp, updated_at = excluded.updated_at"
						: "ON DUPLICATE KEY UPDATE xp = xp + VALUES(xp), updated_at = VALUES(updated_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, season);
			statement.setString(2, uuid.toString());
			statement.setLong(3, amount);
			statement.setLong(4, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}

		return get(connection, season, uuid);
	}

	/**
	 * Sets the XP to an absolute value (an admin correction), creating the row if needed.
	 *
	 * @return the state before the change
	 */
	public static PassState setXp(final Connection connection, final String season, final UUID uuid, final long xp, final boolean sqlite) throws SQLException {
		final PassState before = get(connection, season, uuid);
		final String upsert = "INSERT INTO " + Tables.SEASON_PASS + " (season, uuid, xp, premium, updated_at) VALUES (?, ?, ?, 0, ?) "
				+ (sqlite ? "ON CONFLICT(season, uuid) DO UPDATE SET xp = excluded.xp, updated_at = excluded.updated_at"
						: "ON DUPLICATE KEY UPDATE xp = VALUES(xp), updated_at = VALUES(updated_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, season);
			statement.setString(2, uuid.toString());
			statement.setLong(3, xp);
			statement.setLong(4, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}

		return before;
	}

	public static void setPremium(final Connection connection, final String season, final UUID uuid, final boolean premium, final boolean sqlite) throws SQLException {
		final String upsert = "INSERT INTO " + Tables.SEASON_PASS + " (season, uuid, xp, premium, updated_at) VALUES (?, ?, 0, ?, ?) "
				+ (sqlite ? "ON CONFLICT(season, uuid) DO UPDATE SET premium = excluded.premium, updated_at = excluded.updated_at"
						: "ON DUPLICATE KEY UPDATE premium = VALUES(premium), updated_at = VALUES(updated_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, season);
			statement.setString(2, uuid.toString());
			statement.setBoolean(3, premium);
			statement.setLong(4, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}
	}

	/**
	 * @return the player's state, with zero XP when they have not earned any in this season
	 */
	public static PassState get(final Connection connection, final String season, final UUID uuid) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT xp, premium FROM " + Tables.SEASON_PASS + " WHERE season = ? AND uuid = ?")) {
			statement.setString(1, season);
			statement.setString(2, uuid.toString());

			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? new PassState(season, result.getLong(1), result.getBoolean(2)) : new PassState(season, 0, false);
			}
		}
	}

	public static List<Claim> getClaims(final Connection connection, final String season, final UUID uuid) throws SQLException {
		final List<Claim> claims = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT tier, track FROM " + Tables.SEASON_PASS_CLAIMS
				+ " WHERE season = ? AND uuid = ? ORDER BY tier, track")) {
			statement.setString(1, season);
			statement.setString(2, uuid.toString());

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					claims.add(new Claim(result.getInt(1), result.getString(2)));
			}
		}

		return claims;
	}

	/**
	 * @param source who claimed, e.g. "game" or "web"
	 * @return false if this tier and track were already claimed
	 */
	public static boolean insertClaim(final Connection connection, final String season, final UUID uuid, final int tier, final Track track,
			final String source, final boolean sqlite) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement((sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ") + Tables.SEASON_PASS_CLAIMS
				+ " (season, uuid, tier, track, source, claimed_at) VALUES (?, ?, ?, ?, ?, ?)")) {
			statement.setString(1, season);
			statement.setString(2, uuid.toString());
			statement.setInt(3, tier);
			statement.setString(4, track.key());
			statement.setString(5, source.length() > 16 ? source.substring(0, 16) : source);
			statement.setLong(6, System.currentTimeMillis() / 1000);

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * Adds season pass XP from an old SQLite database (it started empty, so its XP was earned during the outage)
	 * and copies its claims, so rewards claimed there cannot be claimed again.
	 *
	 * @return the number of players whose XP was merged
	 */
	public static long merge(final Connection source, final Connection target, final boolean targetSqlite) throws SQLException {
		long players = 0;

		try (PreparedStatement read = source.prepareStatement("SELECT season, uuid, xp, premium FROM " + Tables.SEASON_PASS);
				ResultSet rows = read.executeQuery()) {

			while (rows.next()) {
				final String season = rows.getString(1);
				final UUID uuid = UUID.fromString(rows.getString(2));

				if (rows.getLong(3) > 0)
					addXp(target, season, uuid, rows.getLong(3), targetSqlite);

				if (rows.getBoolean(4))
					setPremium(target, season, uuid, true, targetSqlite);

				players++;
			}
		}

		try (PreparedStatement read = source.prepareStatement("SELECT season, uuid, tier, track, source, claimed_at FROM " + Tables.SEASON_PASS_CLAIMS);
				ResultSet rows = read.executeQuery();
				PreparedStatement write = target.prepareStatement((targetSqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ") + Tables.SEASON_PASS_CLAIMS
						+ " (season, uuid, tier, track, source, claimed_at) VALUES (?, ?, ?, ?, ?, ?)")) {

			while (rows.next()) {
				for (int column = 1; column <= 6; column++)
					write.setObject(column, rows.getObject(column));

				write.executeUpdate();
			}
		}

		return players;
	}
}
