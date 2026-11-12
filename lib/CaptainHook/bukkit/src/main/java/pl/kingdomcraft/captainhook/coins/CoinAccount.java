package pl.kingdomcraft.captainhook.coins;

import java.util.UUID;

/**
 * A KingdomCoins account as stored in the database.
 */
public final class CoinAccount {

	private final UUID uuid;
	private final String name;
	private final long balance;

	CoinAccount(final UUID uuid, final String name, final long balance) {
		this.uuid = uuid;
		this.name = name;
		this.balance = balance;
	}

	public UUID getUuid() {
		return this.uuid;
	}

	public String getName() {
		return this.name;
	}

	public long getBalance() {
		return this.balance;
	}
}
