package pl.kingdomcraft.captainhook.velocity.coins;

/**
 * One row of the KingdomCoins transaction log.
 *
 * @param type      a {@link TransactionType} name, or any other type written by external systems (e.g. "WEB")
 * @param actor     UUID of the player or "CONSOLE"/plugin name that caused the change
 * @param createdAt Unix seconds
 */
public record CoinTransaction(String type, long amount, long balanceAfter, String actor, String server, String reason, long createdAt) {
}
