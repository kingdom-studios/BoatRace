package pl.kingdomcraft.captainhook.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.mineacademy.fo.model.HookManager;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.hook.VanishHook;

/**
 * Immutable absolute stats of one player on this server, one row of ch_player_stats. The proxy writes the row,
 * see {@link #toJson()}.
 */
final class PlayerSnapshot {

	final UUID uuid;
	final String nickname;
	final BigDecimal balance;
	final int level;
	final long playtimeSeconds;
	final long blocksPlaced;
	final int playerKills;
	final int mobKills;
	final int deaths;
	final String clanTag;
	final boolean online;
	/**
	 * Online in vanish (SuperVanish): the website shows the player as offline.
	 */
	final boolean vanished;
	final long lastSeen;

	private PlayerSnapshot(final Player player, final boolean online) {
		final double balance = HookManager.getBalance(player);

		this.uuid = player.getUniqueId();
		this.nickname = player.getName();
		this.balance = Double.isFinite(balance) ? BigDecimal.valueOf(balance).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
		this.level = player.getLevel();
		this.playtimeSeconds = Remain.getPlaytimeSeconds(player);
		this.blocksPlaced = StatsTracker.getBlocksPlaced(player);
		this.playerKills = player.getStatistic(Statistic.PLAYER_KILLS);
		this.mobKills = player.getStatistic(Statistic.MOB_KILLS);
		this.deaths = player.getStatistic(Statistic.DEATHS);
		this.clanTag = null; // TODO hook the clan plugin once chosen
		this.online = online;
		this.vanished = online && VanishHook.isVanished(player);
		this.lastSeen = System.currentTimeMillis() / 1000;
	}

	/**
	 * Must be called on the main thread (Vault and statistics are not thread-safe).
	 *
	 * @param online false for players leaving the server
	 */
	static PlayerSnapshot of(final Player player, final boolean online) {
		return new PlayerSnapshot(player, online);
	}

	/**
	 * @return one entry of "players" in a stats.batch message
	 */
	JsonObject toJson() {
		final JsonObject json = new JsonObject();

		json.addProperty("uuid", this.uuid.toString());
		json.addProperty("name", this.nickname);
		json.addProperty("balance", this.balance.toPlainString());
		json.addProperty("level", this.level);
		json.addProperty("playtime", this.playtimeSeconds);
		json.addProperty("blocks", this.blocksPlaced);
		json.addProperty("pkills", this.playerKills);
		json.addProperty("mkills", this.mobKills);
		json.addProperty("deaths", this.deaths);

		if (this.clanTag != null)
			json.addProperty("clan", this.clanTag);

		json.addProperty("online", this.online);
		json.addProperty("vanished", this.vanished);
		json.addProperty("seen", this.lastSeen);

		return json;
	}
}
