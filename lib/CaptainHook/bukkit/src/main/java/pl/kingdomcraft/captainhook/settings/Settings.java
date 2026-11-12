package pl.kingdomcraft.captainhook.settings;

import java.util.List;

import org.mineacademy.fo.settings.SimpleSettings;

/**
 * Maps config.yml. Key names under "api" follow the web integration contract. The database is configured on
 * the proxy (config.yml of the Velocity plugin), this server has none.
 */
public final class Settings extends SimpleSettings {

	@Override
	protected String getSettingsFileName() {
		return "config.yml";
	}

	@Override
	protected int getConfigVersion() {
		return 1;
	}

	public static final class Api {

		public static String BASE_URL;
		public static String SERVER_ID;
		public static String SECRET;
		public static Integer TIMEOUT_MILLIS;

		private static void init() {
			setPathPrefix("api");

			BASE_URL = getString("base-url").replaceAll("/+$", "");
			SERVER_ID = getString("server-id");
			SECRET = getString("secret");
			TIMEOUT_MILLIS = Math.max(1, getInteger("timeout-seconds")) * 1000;
		}
	}

	/**
	 * Season pass missions. The proxy decides which missions exist; this server only reports what players do.
	 * Keys are optional so configuration files of older versions keep working.
	 */
	public static final class Missions {

		public static String CLAIM_URL;
		public static Integer FLUSH_TICKS;
		public static Integer AFK_SECONDS;

		private static void init() {
			setPathPrefix("missions");

			CLAIM_URL = isSet("claim-url") ? getString("claim-url") : "https://kingdomcraft.pl/dashboard/seasonpass";
			FLUSH_TICKS = Math.max(5, isSet("flush-interval-seconds") ? getInteger("flush-interval-seconds") : 30) * 20;
			AFK_SECONDS = Math.max(30, isSet("afk-after-seconds") ? getInteger("afk-after-seconds") : 300);
		}
	}

	public static final class Stats {

		public static Integer REALTIME_INTERVAL_TICKS;
		public static Integer SYNC_INTERVAL_TICKS;

		private static void init() {
			setPathPrefix("stats");

			REALTIME_INTERVAL_TICKS = Math.max(1, getInteger("realtime-interval-seconds")) * 20;
			SYNC_INTERVAL_TICKS = Math.max(1, getInteger("sync-interval-minutes")) * 60 * 20;
		}
	}

	public static final class KingdomCoins {

		public static List<String> COMMAND_ALIASES;

		private static void init() {
			setPathPrefix("kingdomcoins");

			COMMAND_ALIASES = getStringList("command-aliases");
		}
	}
}
