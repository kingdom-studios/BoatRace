package pl.kingdomcraft.captainhook.velocity.coins;

/**
 * Outcome of a balance change. Database errors complete the future exceptionally instead.
 *
 * @param balance the balance after a successful change, -1 otherwise
 */
public record CoinResult(Status status, long balance) {

	public static final CoinResult NOT_FOUND = new CoinResult(Status.NOT_FOUND, -1);
	public static final CoinResult INSUFFICIENT_FUNDS = new CoinResult(Status.INSUFFICIENT_FUNDS, -1);

	static CoinResult success(final long balance) {
		return new CoinResult(Status.SUCCESS, balance);
	}

	public boolean isSuccess() {
		return this.status == Status.SUCCESS;
	}

	public enum Status {
		SUCCESS,
		NOT_FOUND,
		INSUFFICIENT_FUNDS
	}
}
