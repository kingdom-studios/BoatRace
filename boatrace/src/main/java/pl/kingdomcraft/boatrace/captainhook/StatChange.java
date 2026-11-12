package pl.kingdomcraft.boatrace.captainhook;

import java.util.UUID;

/**
 * One minigame stat change for CaptainHook, in BoatRace's own type.
 */
public record StatChange(UUID player, String stat, long value, Op op) {

	public enum Op {
		/** counters */
		ADD,
		/** keep the lowest value: best times */
		MIN,
		/** keep the highest value: best speed, longest streak */
		MAX,
		/** replace the value: the rating */
		SET
	}

	public static StatChange add(final UUID player, final String stat, final long amount) {
		return new StatChange(player, stat, amount, Op.ADD);
	}

	public static StatChange min(final UUID player, final String stat, final long value) {
		return new StatChange(player, stat, value, Op.MIN);
	}

	public static StatChange max(final UUID player, final String stat, final long value) {
		return new StatChange(player, stat, value, Op.MAX);
	}

	public static StatChange set(final UUID player, final String stat, final long value) {
		return new StatChange(player, stat, value, Op.SET);
	}
}
