package pl.kingdomcraft.captainhook.coins;

/**
 * One row of the KingdomCoins transaction log.
 */
public final class CoinTransaction {

	private final String type;
	private final long amount;
	private final long balanceAfter;
	private final String actor;
	private final String server;
	private final String reason;
	private final long createdAt;

	CoinTransaction(final String type, final long amount, final long balanceAfter, final String actor, final String server, final String reason, final long createdAt) {
		this.type = type;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.actor = actor;
		this.server = server;
		this.reason = reason;
		this.createdAt = createdAt;
	}

	/**
	 * @return a {@link TransactionType} name, or any other type written to the database by external systems (e.g. "WEB")
	 */
	public String getType() {
		return this.type;
	}

	public long getAmount() {
		return this.amount;
	}

	public long getBalanceAfter() {
		return this.balanceAfter;
	}

	/**
	 * @return UUID of the player or "CONSOLE"/plugin name that caused the change
	 */
	public String getActor() {
		return this.actor;
	}

	public String getServer() {
		return this.server;
	}

	public String getReason() {
		return this.reason;
	}

	/**
	 * @return Unix seconds
	 */
	public long getCreatedAt() {
		return this.createdAt;
	}
}
