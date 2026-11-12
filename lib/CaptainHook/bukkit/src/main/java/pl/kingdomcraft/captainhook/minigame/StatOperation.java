package pl.kingdomcraft.captainhook.minigame;

/**
 * How a reported value changes a minigame stat. The proxy applies each as one atomic statement, so reports
 * from several servers never overwrite each other.
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

	String key() {
		return this.name().toLowerCase();
	}
}
