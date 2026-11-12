package pl.kingdomcraft.captainhook.coins;

/**
 * Outcome of a balance change. Database errors complete the future exceptionally instead.
 */
public final class CoinResult {

	public static final CoinResult NOT_FOUND = new CoinResult(Status.NOT_FOUND, -1);
	public static final CoinResult INSUFFICIENT_FUNDS = new CoinResult(Status.INSUFFICIENT_FUNDS, -1);

	private final Status status;
	private final long balance;

	private CoinResult(final Status status, final long balance) {
		this.status = status;
		this.balance = balance;
	}

	static CoinResult success(final long balance) {
		return new CoinResult(Status.SUCCESS, balance);
	}

	public Status getStatus() {
		return this.status;
	}

	public boolean isSuccess() {
		return this.status == Status.SUCCESS;
	}

	/**
	 * @return the balance after a successful change, -1 otherwise
	 */
	public long getBalance() {
		return this.balance;
	}

	public enum Status {
		SUCCESS,
		NOT_FOUND,
		INSUFFICIENT_FUNDS
	}
}
