package pl.kingdomcraft.captainhook;

/**
 * Permissions of the plugin, defaults are declared in plugin.yml.
 */
public final class Permissions {

	/**
	 * /captainhook (reload, status, merge).
	 */
	public static final String ADMIN = "captainhook.admin";

	/**
	 * /kc give|take|set|history.
	 */
	public static final String COINS_ADMIN = "captainhook.kingdomcoins.admin";

	/**
	 * /kc balance <player>.
	 */
	public static final String COINS_BALANCE_OTHERS = "captainhook.kingdomcoins.balance.others";

	/**
	 * /karnet admin ...
	 */
	public static final String SEASON_PASS_ADMIN = "captainhook.seasonpass.admin";

	private Permissions() {
	}
}
