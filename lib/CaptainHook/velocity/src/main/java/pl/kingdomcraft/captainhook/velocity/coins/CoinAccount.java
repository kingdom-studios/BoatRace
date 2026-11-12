package pl.kingdomcraft.captainhook.velocity.coins;

import java.util.UUID;

/**
 * A KingdomCoins account as stored in the database.
 */
public record CoinAccount(UUID uuid, String name, long balance) {
}
