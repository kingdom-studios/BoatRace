package pl.kingdomcraft.captainhook.velocity.coins;

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
 * SQL of the KingdomCoins tables. Every method is blocking and runs inside a transaction on the database thread.
 *
 * Balances are only ever changed with atomic relative updates, so changes made by the website directly in the
 * database never get overwritten.
 */
public final class CoinStore {

	private static final int IN_CLAUSE_CHUNK = 500;

	private CoinStore() {
	}

	/**
	 * Creates the account if missing, updates the name and returns the balance.
	 */
	public static long load(final Connection connection, final UUID uuid, final String name, final boolean sqlite) throws SQLException {
		final String upsert = "INSERT INTO " + Tables.COINS + " (uuid, name, balance, last_seen) VALUES (?, ?, 0, ?) "
				+ (sqlite ? "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen"
						: "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)");

		try (PreparedStatement statement = connection.prepareStatement(upsert)) {
			statement.setString(1, uuid.toString());
			statement.setString(2, name);
			statement.setLong(3, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}

		final Long balance = getBalance(connection, uuid);

		return balance != null ? balance : 0;
	}

	/**
	 * Case-insensitive lookup, the most recently seen account wins if a name was reused.
	 */
	public static CoinAccount findByName(final Connection connection, final String name) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, name, balance FROM " + Tables.COINS + " WHERE name = ? ORDER BY last_seen DESC LIMIT 1")) {
			statement.setString(1, name);

			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? new CoinAccount(UUID.fromString(result.getString(1)), result.getString(2), result.getLong(3)) : null;
			}
		}
	}

	public static Long getBalance(final Connection connection, final UUID uuid) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM " + Tables.COINS + " WHERE uuid = ?")) {
			statement.setString(1, uuid.toString());

			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? result.getLong(1) : null;
			}
		}
	}

	public static Map<UUID, Long> getBalances(final Connection connection, final Collection<UUID> uuids) throws SQLException {
		final List<UUID> list = new ArrayList<>(uuids);
		final Map<UUID, Long> balances = new HashMap<>();

		for (int from = 0; from < list.size(); from += IN_CLAUSE_CHUNK) {
			final List<UUID> chunk = list.subList(from, Math.min(from + IN_CLAUSE_CHUNK, list.size()));
			final String placeholders = String.join(", ", Collections.nCopies(chunk.size(), "?"));

			try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, balance FROM " + Tables.COINS + " WHERE uuid IN (" + placeholders + ")")) {
				for (int i = 0; i < chunk.size(); i++)
					statement.setString(i + 1, chunk.get(i).toString());

				try (ResultSet result = statement.executeQuery()) {
					while (result.next())
						balances.put(UUID.fromString(result.getString(1)), result.getLong(2));
				}
			}
		}

		return balances;
	}

	/**
	 * Adds the (possibly negative) amount, refusing to go below zero.
	 *
	 * @param server ID of the server the change comes from, stored in the log
	 */
	public static CoinResult change(final Connection connection, final UUID uuid, final long amount, final TransactionType type,
			final String actor, final String server, final String reason) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.COINS + " SET balance = balance + ? WHERE uuid = ? AND balance + ? >= 0")) {
			statement.setLong(1, amount);
			statement.setString(2, uuid.toString());
			statement.setLong(3, amount);

			if (statement.executeUpdate() == 0)
				return getBalance(connection, uuid) == null ? CoinResult.NOT_FOUND : CoinResult.INSUFFICIENT_FUNDS;
		}

		final long balance = getBalance(connection, uuid);

		log(connection, uuid, type, amount, balance, actor, server, reason);
		return CoinResult.success(balance);
	}

	public static CoinResult set(final Connection connection, final UUID uuid, final long balance, final String actor, final String server, final String reason) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.COINS + " SET balance = ? WHERE uuid = ?")) {
			statement.setLong(1, balance);
			statement.setString(2, uuid.toString());

			if (statement.executeUpdate() == 0)
				return CoinResult.NOT_FOUND;
		}

		log(connection, uuid, TransactionType.SET, balance, balance, actor, server, reason);
		return CoinResult.success(balance);
	}

	/**
	 * Moves coins between two accounts in one transaction. Returns the sender's result.
	 */
	public static CoinResult transfer(final Connection connection, final UUID from, final UUID to, final long amount, final String server) throws SQLException {
		if (getBalance(connection, to) == null)
			return CoinResult.NOT_FOUND;

		final CoinResult withdrawn = change(connection, from, -amount, TransactionType.PAY_SENT, to.toString(), server, null);

		if (!withdrawn.isSuccess())
			return withdrawn;

		// Throwing rolls the withdrawal back
		if (!change(connection, to, amount, TransactionType.PAY_RECEIVED, from.toString(), server, null).isSuccess())
			throw new SQLException("Could not deposit " + amount + " coins to " + to);

		return withdrawn;
	}

	public static List<CoinAccount> getTop(final Connection connection, final int limit) throws SQLException {
		final List<CoinAccount> top = new ArrayList<>(limit);

		try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, name, balance FROM " + Tables.COINS + " WHERE balance > 0 ORDER BY balance DESC, name LIMIT ?")) {
			statement.setInt(1, limit);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					top.add(new CoinAccount(UUID.fromString(result.getString(1)), result.getString(2), result.getLong(3)));
			}
		}

		return top;
	}

	public static List<CoinTransaction> getHistory(final Connection connection, final UUID uuid, final int limit, final int offset) throws SQLException {
		final List<CoinTransaction> history = new ArrayList<>(limit);

		try (PreparedStatement statement = connection.prepareStatement("SELECT type, amount, balance_after, actor, server, reason, created_at FROM " + Tables.COINS_LOG
				+ " WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
			statement.setString(1, uuid.toString());
			statement.setInt(2, limit);
			statement.setInt(3, offset);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					history.add(new CoinTransaction(result.getString(1), result.getLong(2), result.getLong(3),
							result.getString(4), result.getString(5), result.getString(6), result.getLong(7)));
			}
		}

		return history;
	}

	/**
	 * @return {accounts, coins} in the database
	 */
	public static long[] summarize(final Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*), COALESCE(SUM(balance), 0) FROM " + Tables.COINS);
				ResultSet result = statement.executeQuery()) {

			result.next();
			return new long[] { result.getLong(1), result.getLong(2) };
		}
	}

	/**
	 * Adds every balance from the source database (old SQLite) to the target and copies its transaction log.
	 *
	 * @param origin where the SQLite file comes from, stored in the log (e.g. "proxy")
	 * @return {merged accounts, merged coins}
	 */
	public static long[] merge(final Connection source, final Connection target, final boolean targetSqlite, final String origin) throws SQLException {
		final String insertMissing = (targetSqlite ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ") + Tables.COINS + " (uuid, name, balance, last_seen) VALUES (?, ?, 0, ?)";
		long accounts = 0, coins = 0;

		try (PreparedStatement read = source.prepareStatement("SELECT uuid, name, balance, last_seen FROM " + Tables.COINS);
				ResultSet rows = read.executeQuery();
				PreparedStatement insert = target.prepareStatement(insertMissing)) {

			while (rows.next()) {
				final UUID uuid = UUID.fromString(rows.getString(1));
				final long balance = rows.getLong(3);

				insert.setString(1, uuid.toString());
				insert.setString(2, rows.getString(2));
				insert.setLong(3, rows.getLong(4));
				insert.executeUpdate();

				if (balance != 0) {
					change(target, uuid, balance, TransactionType.MERGE, "SQLITE", origin, "Merged from " + origin + " SQLite");
					coins += balance;
				}

				accounts++;
			}
		}

		try (PreparedStatement read = source.prepareStatement("SELECT uuid, type, amount, balance_after, actor, server, reason, created_at FROM " + Tables.COINS_LOG + " ORDER BY id");
				ResultSet rows = read.executeQuery();
				PreparedStatement insert = target.prepareStatement("INSERT INTO " + Tables.COINS_LOG
						+ " (uuid, type, amount, balance_after, actor, server, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

			int batched = 0;

			while (rows.next()) {
				for (int column = 1; column <= 8; column++)
					insert.setObject(column, rows.getObject(column));

				insert.addBatch();

				if (++batched % 500 == 0)
					insert.executeBatch();
			}

			insert.executeBatch();
		}

		return new long[] { accounts, coins };
	}

	private static void log(final Connection connection, final UUID uuid, final TransactionType type, final long amount, final long balanceAfter,
			final String actor, final String server, final String reason) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + Tables.COINS_LOG
				+ " (uuid, type, amount, balance_after, actor, server, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, type.name());
			statement.setLong(3, amount);
			statement.setLong(4, balanceAfter);
			statement.setString(5, truncate(actor, 36));
			statement.setString(6, truncate(server, 64));
			statement.setString(7, truncate(reason, 255));
			statement.setLong(8, System.currentTimeMillis() / 1000);
			statement.executeUpdate();
		}
	}

	private static String truncate(final String value, final int length) {
		return value != null && value.length() > length ? value.substring(0, length) : value;
	}
}
