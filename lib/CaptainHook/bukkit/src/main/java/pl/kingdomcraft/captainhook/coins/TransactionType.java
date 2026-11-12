package pl.kingdomcraft.captainhook.coins;

/**
 * Stored in the "type" column of the transaction log, do not rename existing constants.
 * WEB is reserved for coins credited by the website directly in the database.
 */
public enum TransactionType {
	GIVE,
	TAKE,
	SET,
	PAY_SENT,
	PAY_RECEIVED,
	PLUGIN,
	MERGE,
	WEB,
	/**
	 * Coins granted by an achievement or a season pass tier.
	 */
	REWARD
}
