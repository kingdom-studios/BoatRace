package pl.kingdomcraft.captainhook.velocity.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;

/**
 * Absolute stats of one player on one Paper server, one row of ch_player_stats. Taken on the Paper server and
 * sent to the proxy in a stats.batch message.
 *
 * @param balance  balance of the server's regular economy (Vault)
 * @param vanished online in vanish: the website shows the player as offline
 * @param lastSeen Unix seconds when the snapshot was taken
 */
public record PlayerSnapshot(UUID uuid, String nickname, BigDecimal balance, int level, long playtimeSeconds, long blocksPlaced,
		int playerKills, int mobKills, int deaths, String clanTag, boolean online, boolean vanished, long lastSeen) {

	private static final BigDecimal MAX_BALANCE = new BigDecimal("999999999999999999.99"); // DECIMAL(20,2)

	/**
	 * @return the snapshot, or null if the entry has no valid UUID or name
	 */
	public static PlayerSnapshot fromJson(final JsonObject json) {
		final UUID uuid = Json.getUuid(json, "uuid");
		final String name = Json.getString(json, "name", "");

		if (uuid == null || name.isEmpty() || name.length() > 16)
			return null;

		final String clan = Json.getString(json, "clan", null);
		final boolean online = Json.getBoolean(json, "online", false);

		return new PlayerSnapshot(uuid, name, parseBalance(Json.getString(json, "balance", "0")),
				(int) clamp(Json.getLong(json, "level", 0), Integer.MAX_VALUE),
				clamp(Json.getLong(json, "playtime", 0), Long.MAX_VALUE),
				clamp(Json.getLong(json, "blocks", 0), Long.MAX_VALUE),
				(int) clamp(Json.getLong(json, "pkills", 0), Integer.MAX_VALUE),
				(int) clamp(Json.getLong(json, "mkills", 0), Integer.MAX_VALUE),
				(int) clamp(Json.getLong(json, "deaths", 0), Integer.MAX_VALUE),
				clan != null && !clan.isEmpty() && clan.length() <= 16 ? clan : null,
				online,
				online && Json.getBoolean(json, "vanished", false),
				clamp(Json.getLong(json, "seen", System.currentTimeMillis() / 1000), Long.MAX_VALUE));
	}

	/**
	 * @return this snapshot marked offline (and therefore not vanished)
	 */
	public PlayerSnapshot offline() {
		return new PlayerSnapshot(this.uuid, this.nickname, this.balance, this.level, this.playtimeSeconds, this.blocksPlaced,
				this.playerKills, this.mobKills, this.deaths, this.clanTag, false, false, this.lastSeen);
	}

	private static long clamp(final long value, final long max) {
		return Math.max(0, Math.min(value, max));
	}

	private static BigDecimal parseBalance(final String value) {
		try {
			final BigDecimal balance = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);

			return balance.abs().compareTo(MAX_BALANCE) > 0 ? BigDecimal.ZERO : balance;

		} catch (final NumberFormatException | ArithmeticException ex) {
			return BigDecimal.ZERO;
		}
	}
}
