package pl.kingdomcraft.captainhook.velocity.stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition;
import pl.kingdomcraft.captainhook.velocity.database.Tables;

/**
 * SQL of the stats tables, read by the website:
 *
 * - ch_player_stats       one row per (server_id, uuid), absolute snapshot of that server's stats
 * - ch_player_stats_total one row per uuid, network-wide sums maintained here, indexed for leaderboards
 *
 * Every method is blocking and runs inside a transaction on the database thread.
 */
public final class StatsStore {

	private static final int CHUNK_SIZE = 500;

	private static final String[] STATS_COLUMNS = { "server_id", "uuid", "nickname", "balance", "level", "playtime_seconds", "blocks_placed",
			"player_kills", "mob_kills", "deaths", "clan_tag", "online", "vanished", "last_seen", "updated_at" };

	private static final String[] TOTAL_COLUMNS = { "uuid", "nickname", "playtime_seconds", "blocks_placed", "player_kills", "mob_kills", "deaths",
			"online", "vanished", "last_seen", "updated_at" };

	private StatsStore() {
	}

	/**
	 * Saves the snapshots of one server and refreshes the totals of their players.
	 */
	public static void save(final Connection connection, final String serverId, final List<PlayerSnapshot> snapshots, final boolean sqlite) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;
		final Set<String> uuids = new LinkedHashSet<>();

		try (PreparedStatement statement = connection.prepareStatement(upsert(Tables.STATS, STATS_COLUMNS, 2, sqlite))) {
			int batched = 0;

			for (final PlayerSnapshot snapshot : snapshots) {
				statement.setString(1, serverId);
				statement.setString(2, snapshot.uuid().toString());
				statement.setString(3, snapshot.nickname());
				statement.setBigDecimal(4, snapshot.balance());
				statement.setInt(5, snapshot.level());
				statement.setLong(6, snapshot.playtimeSeconds());
				statement.setLong(7, snapshot.blocksPlaced());
				statement.setInt(8, snapshot.playerKills());
				statement.setInt(9, snapshot.mobKills());
				statement.setInt(10, snapshot.deaths());
				statement.setString(11, snapshot.clanTag());
				statement.setBoolean(12, snapshot.online());
				statement.setBoolean(13, snapshot.vanished());
				statement.setLong(14, snapshot.lastSeen());
				statement.setLong(15, now);
				statement.addBatch();

				uuids.add(snapshot.uuid().toString());

				if (++batched % CHUNK_SIZE == 0)
					statement.executeBatch();
			}

			statement.executeBatch();
		}

		refreshTotals(connection, uuids, sqlite);
	}

	/**
	 * Marks a player offline on the server they just left. The proxy sees players leave before (and even if)
	 * the Paper server reports it.
	 */
	public static void markOffline(final Connection connection, final String serverId, final UUID uuid, final boolean sqlite) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;

		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.STATS
				+ " SET online = 0, vanished = 0, last_seen = ?, updated_at = ? WHERE server_id = ? AND uuid = ? AND online = 1")) {
			statement.setLong(1, now);
			statement.setLong(2, now);
			statement.setString(3, serverId);
			statement.setString(4, uuid.toString());

			if (statement.executeUpdate() == 0)
				return;
		}

		refreshTotals(connection, Collections.singletonList(uuid.toString()), sqlite);
	}

	/**
	 * Marks everyone offline. Called when the proxy starts and stops: nobody can be online without the proxy,
	 * and a crash or kill skips the leave events.
	 *
	 * @return the number of players that were still marked online
	 */
	public static int markAllOffline(final Connection connection, final boolean sqlite) throws SQLException {
		final Set<String> uuids = new LinkedHashSet<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT uuid FROM " + Tables.STATS + " WHERE online = 1");
				ResultSet result = statement.executeQuery()) {
			while (result.next())
				uuids.add(result.getString(1));
		}

		if (uuids.isEmpty())
			return 0;

		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.STATS + " SET online = 0, vanished = 0 WHERE online = 1")) {
			statement.executeUpdate();
		}

		refreshTotals(connection, uuids, sqlite);
		return uuids.size();
	}

	/**
	 * Recomputes network-wide sums of the given players from all servers' rows.
	 */
	static void refreshTotals(final Connection connection, final Collection<String> uuids, final boolean sqlite) throws SQLException {
		final List<String> list = new ArrayList<>(uuids);
		final long now = System.currentTimeMillis() / 1000;
		final String insert = "INSERT INTO " + Tables.STATS_TOTAL + " (" + String.join(", ", TOTAL_COLUMNS) + ") "
				+ "SELECT s.uuid, "
				+ "(SELECT n.nickname FROM " + Tables.STATS + " n WHERE n.uuid = s.uuid ORDER BY n.last_seen DESC LIMIT 1), "
				+ "SUM(s.playtime_seconds), SUM(s.blocks_placed), SUM(s.player_kills), SUM(s.mob_kills), SUM(s.deaths), "
				+ "MAX(s.online), "
				// vanished in total = online somewhere, but visible nowhere
				+ "CASE WHEN MAX(s.online) = 1 AND MAX(CASE WHEN s.online = 1 AND s.vanished = 0 THEN 1 ELSE 0 END) = 0 THEN 1 ELSE 0 END, "
				+ "MAX(s.last_seen), ? "
				+ "FROM " + Tables.STATS + " s WHERE s.uuid IN (%s) GROUP BY s.uuid "
				+ updateClause(TOTAL_COLUMNS, 1, sqlite);

		for (int from = 0; from < list.size(); from += CHUNK_SIZE) {
			final List<String> chunk = list.subList(from, Math.min(from + CHUNK_SIZE, list.size()));

			try (PreparedStatement statement = connection.prepareStatement(String.format(insert, String.join(", ", Collections.nCopies(chunk.size(), "?"))))) {
				statement.setLong(1, now);

				for (int i = 0; i < chunk.size(); i++)
					statement.setString(i + 2, chunk.get(i));

				statement.executeUpdate();
			}
		}
	}

	/**
	 * @return network totals of the stats achievements can follow, by player and column name
	 */
	public static Map<UUID, Map<String, Long>> getTotals(final Connection connection, final Collection<UUID> uuids) throws SQLException {
		final List<UUID> list = new ArrayList<>(uuids);
		final List<String> stats = AchievementDefinition.NETWORK_STATS.stream().sorted().toList();
		final Map<UUID, Map<String, Long>> totals = new HashMap<>();

		for (int from = 0; from < list.size(); from += CHUNK_SIZE) {
			final List<UUID> chunk = list.subList(from, Math.min(from + CHUNK_SIZE, list.size()));

			try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, " + String.join(", ", stats) + " FROM " + Tables.STATS_TOTAL
					+ " WHERE uuid IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")")) {
				for (int i = 0; i < chunk.size(); i++)
					statement.setString(i + 1, chunk.get(i).toString());

				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						final Map<String, Long> values = new HashMap<>();

						for (int i = 0; i < stats.size(); i++)
							values.put(stats.get(i), result.getLong(i + 2));

						totals.put(UUID.fromString(result.getString(1)), values);
					}
				}
			}
		}

		return totals;
	}

	/**
	 * Copies stats rows from an old SQLite database to the target if they are newer there, then refreshes totals.
	 * Copied rows are marked offline.
	 *
	 * @return the number of copied rows
	 */
	public static long merge(final Connection source, final Connection target, final boolean targetSqlite) throws SQLException {
		final Set<String> merged = new LinkedHashSet<>();

		// Old databases may predate the vanished column; merged rows are offline anyway
		final String columns = String.join(", ", STATS_COLUMNS).replace("vanished", "0 AS vanished");

		try (PreparedStatement read = source.prepareStatement("SELECT " + columns + " FROM " + Tables.STATS);
				ResultSet rows = read.executeQuery();
				PreparedStatement current = target.prepareStatement("SELECT updated_at FROM " + Tables.STATS + " WHERE server_id = ? AND uuid = ?");
				PreparedStatement write = target.prepareStatement(upsert(Tables.STATS, STATS_COLUMNS, 2, targetSqlite))) {

			while (rows.next()) {
				current.setString(1, rows.getString("server_id"));
				current.setString(2, rows.getString("uuid"));

				try (ResultSet existing = current.executeQuery()) {
					if (existing.next() && existing.getLong(1) >= rows.getLong("updated_at"))
						continue;
				}

				for (int i = 0; i < STATS_COLUMNS.length; i++)
					write.setObject(i + 1, STATS_COLUMNS[i].equals("online") || STATS_COLUMNS[i].equals("vanished") ? Boolean.FALSE : rows.getObject(STATS_COLUMNS[i]));

				write.executeUpdate();
				merged.add(rows.getString("uuid"));
			}
		}

		refreshTotals(target, merged, targetSqlite);
		return merged.size();
	}

	/**
	 * INSERT ... ON DUPLICATE KEY UPDATE (MySQL) / ON CONFLICT DO UPDATE (SQLite) over all columns,
	 * the first keyColumns being the primary key.
	 */
	private static String upsert(final String table, final String[] columns, final int keyColumns, final boolean sqlite) {
		return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", Collections.nCopies(columns.length, "?")) + ") "
				+ updateClause(columns, keyColumns, sqlite);
	}

	private static String updateClause(final String[] columns, final int keyColumns, final boolean sqlite) {
		final List<String> updates = new ArrayList<>();

		for (int i = keyColumns; i < columns.length; i++)
			updates.add(columns[i] + " = " + (sqlite ? "excluded." + columns[i] : "VALUES(" + columns[i] + ")"));

		final List<String> keys = new ArrayList<>();

		for (int i = 0; i < keyColumns; i++)
			keys.add(columns[i]);

		return (sqlite ? "ON CONFLICT(" + String.join(", ", keys) + ") DO UPDATE SET " : "ON DUPLICATE KEY UPDATE ") + String.join(", ", updates);
	}
}
