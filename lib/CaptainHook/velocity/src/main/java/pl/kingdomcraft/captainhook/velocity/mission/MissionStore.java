package pl.kingdomcraft.captainhook.velocity.mission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;

import pl.kingdomcraft.captainhook.velocity.database.Tables;

/**
 * SQL of the mission tables. Every method is blocking and runs inside a transaction on the database thread.
 *
 * - ch_mission_assignments  the missions of each period, drawn once by the proxy
 * - ch_mission_progress     one row per player and assignment; completed_at is set exactly once
 * - ch_mission_days         the days a player logged in (LOGIN and DISTINCT_DAYS count each day once)
 * - ch_xp_ledger            every XP grant that must happen once, unique per (player, source, source_id)
 */
public final class MissionStore {

	/**
	 * @param completedAt Unix seconds, null while not completed
	 */
	public record Progress(long progress, Long completedAt) {

		public static final Progress NONE = new Progress(0, null);
	}

	private static final String COLUMNS = "id, season, period_type, period_key, mission_key, objective, filter_json, target, xp, icon, unit_en, unit_pl, title_en, title_pl, starts_at, ends_at";
	private static final int IN_CLAUSE_CHUNK = 500;

	private MissionStore() {
	}

	/* ------------------------------------------------------------------------------- */
	/* Assignments */
	/* ------------------------------------------------------------------------------- */

	/**
	 * @return the keys of this period that exist already: its own and its re-rolls ("2026-10-12", "2026-10-12.1")
	 */
	public static List<String> periodKeys(final Connection connection, final String season, final MissionPeriodType type, final String baseKey) throws SQLException {
		final List<String> keys = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT period_key FROM " + Tables.MISSION_ASSIGNMENTS
				+ " WHERE season = ? AND period_type = ? AND (period_key = ? OR period_key LIKE ?)")) {
			statement.setString(1, season);
			statement.setString(2, type.key());
			statement.setString(3, baseKey);
			statement.setString(4, baseKey + ".%");

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					keys.add(result.getString(1));
			}
		}

		return keys;
	}

	/**
	 * Stores the missions of a period. A mission that is stored already is left alone.
	 */
	public static void insertAssignments(final Connection connection, final String season, final MissionPeriod period, final List<MissionDefinition> missions, final boolean sqlite) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement((sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ") + Tables.MISSION_ASSIGNMENTS
				+ " (season, period_type, period_key, mission_key, objective, filter_json, target, xp, icon, unit_en, unit_pl, title_en, title_pl, starts_at, ends_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
			for (final MissionDefinition mission : missions) {
				statement.setString(1, season);
				statement.setString(2, period.type().key());
				statement.setString(3, period.key());
				statement.setString(4, mission.key());
				statement.setString(5, mission.objective().name());
				statement.setString(6, mission.filter().toJson().toString());
				statement.setLong(7, mission.target());
				statement.setLong(8, mission.xp());
				statement.setString(9, mission.icon());
				statement.setString(10, mission.unitEn());
				statement.setString(11, mission.unitPl());
				statement.setString(12, mission.titleEn());
				statement.setString(13, mission.titlePl());
				statement.setLong(14, period.startsAt());

				if (period.endsAt() == 0)
					statement.setNull(15, Types.BIGINT);
				else
					statement.setLong(15, period.endsAt());

				statement.addBatch();
			}

			statement.executeBatch();
		}
	}

	/**
	 * @return every assignment of the season that is valid at this moment, all re-rolls included
	 */
	public static List<Assignment> loadCurrent(final Connection connection, final String season, final long nowSeconds) throws SQLException {
		final List<Assignment> assignments = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + Tables.MISSION_ASSIGNMENTS
				+ " WHERE season = ? AND starts_at <= ? AND (ends_at IS NULL OR ends_at > ?) ORDER BY id")) {
			statement.setString(1, season);
			statement.setLong(2, nowSeconds);
			statement.setLong(3, nowSeconds);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					assignments.add(read(result));
			}
		}

		return assignments;
	}

	private static Assignment read(final ResultSet result) throws SQLException {
		final Objective objective = Objective.find(result.getString(6));
		final long endsAt = result.getLong(16);

		return new Assignment(result.getLong(1), result.getString(2), MissionPeriodType.find(result.getString(3)), result.getString(4), result.getString(5),
				objective != null ? objective : Objective.LOGIN, parseFilter(result.getString(7)), result.getLong(8), result.getLong(9), result.getString(10),
				result.getString(11), result.getString(12), result.getString(13), result.getString(14), result.getLong(15), result.wasNull() ? 0 : endsAt);
	}

	@SuppressWarnings("deprecation")
	private static MissionFilter parseFilter(final String json) {
		try {
			return json == null ? MissionFilter.ANY : MissionFilter.fromJson(new JsonParser().parse(json).getAsJsonObject());

		} catch (final RuntimeException ex) {
			return MissionFilter.ANY;
		}
	}

	/* ------------------------------------------------------------------------------- */
	/* Progress */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Adds progress with an atomic relative update, never above the target.
	 */
	public static void addProgress(final Connection connection, final UUID uuid, final Assignment assignment, final long delta, final boolean sqlite) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;
		final long capped = Math.min(delta, assignment.target());
		final String upsert = "INSERT INTO " + Tables.MISSION_PROGRESS + " (uuid, assignment_id, progress, updated_at) VALUES (?, ?, ?, ?) "
				+ (sqlite ? "ON CONFLICT(uuid, assignment_id) DO UPDATE SET progress = MIN(progress + excluded.progress, ?), updated_at = excluded.updated_at"
						: "ON DUPLICATE KEY UPDATE progress = LEAST(progress + VALUES(progress), ?), updated_at = VALUES(updated_at)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, uuid.toString());
			statement.setLong(2, assignment.id());
			statement.setLong(3, capped);
			statement.setLong(4, now);
			statement.setLong(5, assignment.target());
			statement.executeUpdate();
		}
	}

	/**
	 * Marks the mission completed if its progress reached the target.
	 *
	 * @return true only for the call that completed it, so its XP is granted once
	 */
	public static boolean complete(final Connection connection, final UUID uuid, final Assignment assignment) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.MISSION_PROGRESS
				+ " SET completed_at = ? WHERE uuid = ? AND assignment_id = ? AND completed_at IS NULL AND progress >= ?")) {
			statement.setLong(1, System.currentTimeMillis() / 1000);
			statement.setString(2, uuid.toString());
			statement.setLong(3, assignment.id());
			statement.setLong(4, assignment.target());

			return statement.executeUpdate() > 0;
		}
	}

	public static Map<Long, Progress> getProgress(final Connection connection, final UUID uuid, final Collection<Long> assignmentIds) throws SQLException {
		final List<Long> ids = new ArrayList<>(assignmentIds);
		final Map<Long, Progress> progress = new HashMap<>();

		for (int from = 0; from < ids.size(); from += IN_CLAUSE_CHUNK) {
			final List<Long> chunk = ids.subList(from, Math.min(from + IN_CLAUSE_CHUNK, ids.size()));

			try (PreparedStatement statement = connection.prepareStatement("SELECT assignment_id, progress, completed_at FROM " + Tables.MISSION_PROGRESS
					+ " WHERE uuid = ? AND assignment_id IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")")) {
				statement.setString(1, uuid.toString());

				for (int i = 0; i < chunk.size(); i++)
					statement.setLong(i + 2, chunk.get(i));

				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						final long completedAt = result.getLong(3);

						progress.put(result.getLong(1), new Progress(result.getLong(2), result.wasNull() ? null : completedAt));
					}
				}
			}
		}

		return progress;
	}

	/**
	 * @return true if the player had not logged in on this day yet
	 */
	public static boolean markDay(final Connection connection, final UUID uuid, final String day, final boolean sqlite) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement((sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ") + Tables.MISSION_DAYS + " (uuid, day) VALUES (?, ?)")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, day);

			return statement.executeUpdate() > 0;
		}
	}

	/* ------------------------------------------------------------------------------- */
	/* XP ledger */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Records an XP grant. The unique key (player, source, source_id) means a second try of the same grant does nothing.
	 *
	 * @return true if the grant is new, so the XP must be added now
	 */
	public static boolean insertLedger(final Connection connection, final UUID uuid, final String season, final long amount, final String source,
			final String sourceId, final boolean sqlite) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement((sqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ") + Tables.XP_LEDGER
				+ " (uuid, season, amount, source, source_id, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, season);
			statement.setLong(3, amount);
			statement.setString(4, source);
			statement.setString(5, sourceId);
			statement.setLong(6, System.currentTimeMillis() / 1000);

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * @return the sum of the ledger of a player in a season, for checks
	 */
	public static long ledgerTotal(final Connection connection, final UUID uuid, final String season) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM " + Tables.XP_LEDGER + " WHERE uuid = ? AND season = ?")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, season);

			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

}
