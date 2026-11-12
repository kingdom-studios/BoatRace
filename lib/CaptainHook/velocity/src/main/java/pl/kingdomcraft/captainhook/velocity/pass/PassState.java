package pl.kingdomcraft.captainhook.velocity.pass;

/**
 * A player's progress in one season, one row of ch_seasonpass (all zero when the player has no row yet).
 */
public record PassState(String season, long xp, boolean premium) {
}
