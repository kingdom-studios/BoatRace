package pl.kingdomcraft.captainhook.velocity.coins;

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
	REWARD;

	/**
	 * @return the type with this name, or null
	 */
	public static TransactionType find(final String name) {
		for (final TransactionType type : values())
			if (type.name().equals(name))
				return type;

		return null;
	}
}
