package pl.kingdomcraft.captainhook.velocity.config;

import java.nio.file.Path;

/**
 * config.yml of the proxy.
 *
 * @param coinsRefreshSeconds   how often balances of online players and the top list are reloaded
 * @param pendingJoinTtlSeconds how long a pending minigame join waits for the player by default
 * @param rewardDispatchSeconds how often the reward queue is checked for players who are online
 * @param rewardRetrySeconds    after how long an unacknowledged reward is sent again
 */
public record ProxyConfig(Database database, int coinsRefreshSeconds, int pendingJoinTtlSeconds, int rewardDispatchSeconds, int rewardRetrySeconds) {

	public static final String FILE = "config.yml";

	/**
	 * Read once on startup, changes require a proxy restart.
	 */
	public record Database(boolean mysqlEnabled, String host, int port, String name, String username, String password, String properties) {

		@Override
		public String toString() {
			return "Database[" + (this.mysqlEnabled ? this.host + ":" + this.port + "/" + this.name : "sqlite") + "]"; // never the credentials
		}
	}

	public static ProxyConfig load(final Path dataDirectory) throws ConfigException {
		final YamlFile file = YamlFile.load(dataDirectory, FILE);
		final YamlFile mysql = file.section("database").section("mysql");

		final Database database = new Database(
				mysql.getBoolean("enabled", false),
				mysql.getString("host", "localhost"),
				(int) mysql.getLong("port", 3306),
				mysql.getString("database", "captainhook"),
				mysql.getString("username", "root"),
				mysql.getString("password", ""),
				mysql.getString("properties", ""));

		return new ProxyConfig(database,
				(int) Math.max(5, file.section("kingdomcoins").getLong("refresh-interval-seconds", 30)),
				(int) Math.min(600, Math.max(5, file.section("minigames").getLong("pending-join-ttl-seconds", 60))),
				(int) Math.min(300, Math.max(1, file.section("rewards").getLong("dispatch-interval-seconds", 5))),
				(int) Math.min(3600, Math.max(10, file.section("rewards").getLong("retry-after-seconds", 60))));
	}
}
