package pl.kingdomcraft.captainhook.velocity.reward;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.google.gson.JsonArray;

import pl.kingdomcraft.captainhook.velocity.coins.CoinResult;
import pl.kingdomcraft.captainhook.velocity.coins.CoinStore;
import pl.kingdomcraft.captainhook.velocity.coins.TransactionType;
import pl.kingdomcraft.captainhook.velocity.database.Tables;
import pl.kingdomcraft.captainhook.velocity.pass.PassState;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassStore;

/**
 * Grants rewards inside the transaction that unlocked an achievement or claimed a season pass tier, so a player
 * can never end up with the unlock but without the reward (or the other way round).
 */
public final class Rewards {

	/**
	 * Stored as "server" of reward transactions in the KingdomCoins log.
	 */
	public static final String SERVER = "proxy";

	private Rewards() {
	}

	/**
	 * What changed, for notifying the player's server after the commit.
	 *
	 * @param coinBalance the new KingdomCoins balance, null if the reward had no coins
	 * @param pass        the new season pass state, null if no pass XP was granted
	 * @param xpBefore    pass XP before the grant, only meaningful when pass is not null
	 */
	public record Granted(Long coinBalance, PassState pass, long xpBefore) {

		public static final Granted NOTHING = new Granted(null, null, 0);
	}

	/**
	 * Call on the database thread.
	 *
	 * @param source what the reward is for, e.g. "achievement:first_blood" or "seasonpass:s1:5:free"
	 * @param season the season pass XP goes to, null when there is none (pass XP is then skipped)
	 */
	public static Granted grant(final Connection connection, final boolean sqlite, final UUID uuid, final Reward reward, final String source,
			final SeasonPassSettings season) throws SQLException {
		if (reward.isEmpty())
			return Granted.NOTHING;

		Long balance = null;
		PassState pass = null;
		long xpBefore = 0;

		if (reward.kingdomCoins() > 0) {
			final CoinResult result = CoinStore.change(connection, uuid, reward.kingdomCoins(), TransactionType.REWARD, "REWARD", SERVER, source);

			// Accounts are created when a player joins the network, so this means a reward for a stranger
			if (!result.isSuccess())
				throw new SQLException("Cannot grant " + source + ": " + uuid + " has no KingdomCoins account");

			balance = result.balance();
		}

		if (reward.passXp() > 0 && season != null) {
			pass = SeasonPassStore.addXp(connection, season.id(), uuid, reward.passXp(), sqlite);
			xpBefore = pass.xp() - reward.passXp();
		}

		if (!reward.commands().isEmpty())
			queue(connection, uuid, source, reward);

		return new Granted(balance, pass, xpBefore);
	}

	/**
	 * Commands cannot run on the proxy, so they wait in the reward queue until a Paper server runs them.
	 */
	private static void queue(final Connection connection, final UUID uuid, final String source, final Reward reward) throws SQLException {
		final JsonArray commands = new JsonArray();

		reward.commands().forEach(commands::add);

		try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + Tables.REWARD_QUEUE
				+ " (uuid, source, commands, message, created_at, require_server) VALUES (?, ?, ?, ?, ?, ?)")) {
			statement.setString(1, uuid.toString());
			statement.setString(2, source.length() > 96 ? source.substring(0, 96) : source);
			statement.setString(3, commands.toString());
			statement.setString(4, reward.message());
			statement.setLong(5, System.currentTimeMillis() / 1000);
			statement.setString(6, reward.requireServer());
			statement.executeUpdate();
		}
	}
}
