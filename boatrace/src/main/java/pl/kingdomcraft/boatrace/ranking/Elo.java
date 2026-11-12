package pl.kingdomcraft.boatrace.ranking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ELO for races of several players (as in chess, applied to every pair): the finishing order is split into
 * duels, everybody beat everybody behind them. Each duel moves {@code k / (n - 1)} points at most, so a race moves
 * about as many points as one chess game, whatever the field size.
 * <p>
 * The duel is zero-sum (what the winner gains the loser loses), which is what stops "arranged" results: two friends
 * who let each other win in turn only pass the same points back and forth, and beating a much weaker player gives
 * almost nothing.
 */
public final class Elo {

	private Elo() {
	}

	/**
	 * @param order   players from first to last place (finishers in finishing order, then players who left)
	 * @param ratings rating of every player of the order before the race
	 * @param k       points at stake per race (chess uses 16 to 40)
	 * @return the change of every player of the order (may be 0)
	 */
	public static Map<UUID, Integer> deltas(final List<UUID> order, final Map<UUID, Integer> ratings, final double k) {
		final Map<UUID, Integer> deltas = new HashMap<>();
		final int n = order.size();

		for (final UUID uuid : order)
			deltas.put(uuid, 0);

		if (n < 2)
			return deltas;

		final double perDuel = k / (n - 1);

		for (int better = 0; better < n; better++)
			for (int worse = better + 1; worse < n; worse++) {
				final UUID winner = order.get(better);
				final UUID loser = order.get(worse);
				final int points = (int) Math.round(perDuel * (1.0D - expected(ratings.get(winner), ratings.get(loser))));

				deltas.merge(winner, points, Integer::sum);
				deltas.merge(loser, -points, Integer::sum);
			}

		return deltas;
	}

	/** @return the chance of a player with {@code rating} to beat one with {@code opponent} */
	public static double expected(final int rating, final int opponent) {
		return 1.0D / (1.0D + Math.pow(10.0D, (opponent - rating) / 400.0D));
	}
}
