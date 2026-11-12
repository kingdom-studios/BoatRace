package pl.kingdomcraft.boatrace.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EloTest {

	@Test
	void aRaceIsZeroSumAndOrdered() {
		final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
		final Map<UUID, Integer> deltas = Elo.deltas(List.of(a, b, c, d), Map.of(a, 1000, b, 1000, c, 1000, d, 1000), 32);

		assertEquals(0, deltas.values().stream().mapToInt(Integer::intValue).sum());
		assertTrue(deltas.get(a) > deltas.get(b) && deltas.get(b) > deltas.get(c) && deltas.get(c) > deltas.get(d));
	}

	@Test
	void arrangedResultsDoNotPay() {
		final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		final Map<UUID, Integer> ratings = new HashMap<>(Map.of(a, 1000, b, 1000));

		for (int race = 0; race < 50; race++) {
			final List<UUID> order = race % 2 == 0 ? List.of(a, b) : List.of(b, a);

			Elo.deltas(order, ratings, 32).forEach((uuid, delta) -> ratings.merge(uuid, delta, Integer::sum));
		}

		assertTrue(Math.abs(ratings.get(a) - 1000) <= 16, "friends taking turns stay around the start: " + ratings);
	}

	@Test
	void beatingAWeakerPlayerGivesLittle() {
		final UUID strong = UUID.randomUUID(), weak = UUID.randomUUID();

		assertTrue(Elo.deltas(List.of(strong, weak), Map.of(strong, 1400, weak, 1000), 32).get(strong) <= 3);
		assertTrue(Elo.deltas(List.of(weak, strong), Map.of(strong, 1400, weak, 1000), 32).get(weak) >= 28);
	}
}
