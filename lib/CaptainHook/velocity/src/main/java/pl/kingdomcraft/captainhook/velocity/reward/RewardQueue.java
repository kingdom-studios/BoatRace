package pl.kingdomcraft.captainhook.velocity.reward;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import pl.kingdomcraft.captainhook.velocity.database.Tables;

/**
 * SQL of ch_reward_queue: command rewards waiting to run on a Paper server. Rows are written by the proxy
 * (achievements, season pass) and by the website (claims); the proxy delivers them, see {@link RewardDispatcher}.
 */
public final class RewardQueue {

	private static final int IN_CLAUSE_CHUNK = 500;

	/**
	 * One undelivered row.
	 *
	 * @param message shown to the player once the commands ran, may be null
	 * @param requireServer   Velocity name of the only server the commands may run on, null for any
	 * @param trusted true if commands and server are exactly those the proxy published for this source, see {@link #isPublished}
	 */
	public record PendingReward(long id, UUID uuid, String source, List<String> commands, String message, String requireServer, boolean trusted) {

		/**
		 * @return true if the commands may run on this server
		 */
		public boolean allowedOn(final String velocityServer) {
			return this.requireServer == null || this.requireServer.equalsIgnoreCase(velocityServer);
		}
	}

	private RewardQueue() {
	}

	/**
	 * @return undelivered rewards of these players, oldest first, at most limit
	 */
	public static List<PendingReward> pending(final Connection connection, final Collection<UUID> uuids, final int limit) throws SQLException {
		final List<UUID> list = new ArrayList<>(uuids);
		final List<PendingReward> pending = new ArrayList<>();

		for (int from = 0; from < list.size() && pending.size() < limit; from += IN_CLAUSE_CHUNK) {
			final List<UUID> chunk = list.subList(from, Math.min(from + IN_CLAUSE_CHUNK, list.size()));

			try (PreparedStatement statement = connection.prepareStatement("SELECT id, uuid, source, commands, message, require_server FROM " + Tables.REWARD_QUEUE
					+ " WHERE delivered_at IS NULL AND uuid IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ") ORDER BY id LIMIT ?")) {
				for (int i = 0; i < chunk.size(); i++)
					statement.setString(i + 1, chunk.get(i).toString());

				statement.setInt(chunk.size() + 1, limit - pending.size());

				try (ResultSet result = statement.executeQuery()) {
					while (result.next())
						pending.add(new PendingReward(result.getLong(1), UUID.fromString(result.getString(2)), result.getString(3),
								parseCommands(result.getString(4)), result.getString(5), result.getString(6), false));
				}
			}
		}

		final List<PendingReward> checked = new ArrayList<>(pending.size());

		for (final PendingReward reward : pending)
			checked.add(new PendingReward(reward.id(), reward.uuid(), reward.source(), reward.commands(), reward.message(), reward.requireServer(),
					isPublished(connection, reward.source(), reward.commands(), reward.requireServer())));

		return checked;
	}

	/**
	 * The website may write to the queue, and queued commands run as the console, so a row is only trusted when its
	 * commands (and its required server: dropping it must not move an item to another game mode) are exactly the
	 * ones the proxy published for its source (ch_seasonpass_rewards for
	 * "seasonpass:season:tier:track", ch_achievement_definitions for "achievement:id"). The website can only read
	 * those tables, so a compromised website cannot make the servers run commands of its own.
	 */
	static boolean isPublished(final Connection connection, final String source, final List<String> commands, final String requireServer) throws SQLException {
		final String[] parts = source.split(":");
		final String sql;
		final Object[] params;

		if (parts.length == 4 && parts[0].equals("seasonpass")) {
			final int tier;

			try {
				tier = Integer.parseInt(parts[2]);
			} catch (final NumberFormatException ex) {
				return false;
			}

			sql = "SELECT commands, require_server FROM " + Tables.SEASON_PASS_REWARDS + " WHERE season = ? AND tier = ? AND track = ?";
			params = new Object[] { parts[1], tier, parts[3] };

		} else if (parts.length == 2 && parts[0].equals("achievement")) {
			sql = "SELECT commands, require_server FROM " + Tables.ACHIEVEMENT_DEFINITIONS + " WHERE id = ?";
			params = new Object[] { parts[1] };

		} else
			return false;

		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++)
				statement.setObject(i + 1, params[i]);

			try (ResultSet result = statement.executeQuery()) {
				return result.next() && parseCommands(result.getString(1)).equals(commands) && java.util.Objects.equals(result.getString(2), requireServer);
			}
		}
	}

	/**
	 * @return false if the row was delivered already (or does not belong to this player)
	 */
	public static boolean markDelivered(final Connection connection, final long id, final UUID uuid, final String server) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.REWARD_QUEUE
				+ " SET delivered_at = ?, delivered_server = ? WHERE id = ? AND uuid = ? AND delivered_at IS NULL")) {
			statement.setLong(1, System.currentTimeMillis() / 1000);
			statement.setString(2, server.length() > 64 ? server.substring(0, 64) : server);
			statement.setLong(3, id);
			statement.setString(4, uuid.toString());

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * @return the commands of a row, or an empty list if the column is not a JSON array of texts
	 */
	@SuppressWarnings("deprecation")
	static List<String> parseCommands(final String json) {
		final List<String> commands = new ArrayList<>();

		try {
			final JsonElement element = new JsonParser().parse(json == null ? "" : json);

			if (element.isJsonArray())
				for (final JsonElement command : (JsonArray) element)
					if (command.isJsonPrimitive())
						commands.add(command.getAsString());

		} catch (final RuntimeException ignored) {
		}

		return commands;
	}
}
