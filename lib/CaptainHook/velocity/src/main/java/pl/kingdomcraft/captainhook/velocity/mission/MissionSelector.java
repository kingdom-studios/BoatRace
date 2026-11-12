package pl.kingdomcraft.captainhook.velocity.mission;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Draws the missions of a period. The draw depends only on the pool and the seed, never on the machine or the time it
 * runs, so a second proxy (or a re-run after a crash) draws the same missions.
 */
public final class MissionSelector {

	private MissionSelector() {
	}

	/**
	 * @return a stable 64-bit seed of the text, e.g. the season ID plus the period key
	 */
	public static long seed(final String text) {
		try {
			return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).getLong();

		} catch (final NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
		}
	}

	/**
	 * @param count total number of missions; "always" missions come first and count towards it (all of them are
	 *              taken even if there are more than count)
	 * @return the missions, "always" ones first, then the draw by weight without repeats
	 */
	public static List<MissionDefinition> select(final List<MissionDefinition> pool, final int count, final long seed) {
		// A fixed order, so the pool order in the file does not change the draw
		final List<MissionDefinition> sorted = new ArrayList<>(pool);

		sorted.sort(Comparator.comparing(MissionDefinition::key));

		final List<MissionDefinition> chosen = new ArrayList<>();
		final List<MissionDefinition> candidates = new ArrayList<>();

		for (final MissionDefinition mission : sorted)
			(mission.always() ? chosen : candidates).add(mission);

		final SplittableRandom random = new SplittableRandom(seed);

		while (chosen.size() < count && !candidates.isEmpty()) {
			long total = 0;

			for (final MissionDefinition mission : candidates)
				total += mission.weight();

			long roll = (long) (random.nextDouble() * total);

			for (int i = 0; i < candidates.size(); i++) {
				roll -= candidates.get(i).weight();

				if (roll < 0 || i == candidates.size() - 1) {
					chosen.add(candidates.remove(i));
					break;
				}
			}
		}

		return chosen;
	}
}
