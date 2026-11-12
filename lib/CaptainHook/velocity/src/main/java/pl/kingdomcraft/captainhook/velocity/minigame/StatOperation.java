package pl.kingdomcraft.captainhook.velocity.minigame;

/**
 * How a reported value changes a minigame stat. All four are single atomic statements, so reports from
 * several servers never overwrite each other.
 */
public enum StatOperation {
	/**
	 * Counters: wins, games played, kills.
	 */
	ADD,
	/**
	 * Replace the value.
	 */
	SET,
	/**
	 * Keep the highest value: best score, longest streak.
	 */
	MAX,
	/**
	 * Keep the lowest value: best time.
	 */
	MIN;

	public String key() {
		return this.name().toLowerCase();
	}

	/**
	 * @return the operation with this key, or null
	 */
	public static StatOperation find(final String key) {
		for (final StatOperation operation : values())
			if (operation.key().equalsIgnoreCase(key))
				return operation;

		return null;
	}
}
