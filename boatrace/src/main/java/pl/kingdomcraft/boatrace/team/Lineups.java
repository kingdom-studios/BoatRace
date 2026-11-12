package pl.kingdomcraft.boatrace.team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Same lineup" bonus of competitive matches, kept as light as possible: in memory only, ONE small entry per player
 * (a 64-bit fingerprint of their last team, how many matches in a row they played with exactly that team, when).
 * No team is ever stored - parties come and go; a lineup simply counts while the same players keep coming back.
 * Entries older than the expiry are dropped, a restart forgets everything (parties do not survive it either).
 */
public final class Lineups {

	private record Entry(long fingerprint, int streak, long lastMatch) {
	}

	private final Map<UUID, Entry> entries = new HashMap<>();

	/**
	 * Counts a match of the team for each of its members.
	 *
	 * @param team     every member of the team (a team of 1 never has a lineup streak)
	 * @param now      epoch ms
	 * @param expireMs a lineup that did not play for this long starts again at 1
	 * @return matches in a row with exactly this lineup, including this one (1 = first match together)
	 */
	public int countMatch(final Collection<UUID> team, final long now, final long expireMs) {
		if (team.size() < 2)
			return 1;

		this.purge(now, expireMs);

		final long fingerprint = fingerprint(team);
		int streak = 1;

		for (final UUID member : team) {
			final Entry entry = this.entries.get(member);

			// everybody must have played the previous match in exactly this lineup
			if (entry == null || entry.fingerprint != fingerprint) {
				streak = 1;
				break;
			}

			streak = Math.max(streak, entry.streak + 1);
		}

		for (final UUID member : team)
			this.entries.put(member, new Entry(fingerprint, streak, now));

		return streak;
	}

	/**
	 * @param streak   result of {@link #countMatch}
	 * @param perMatch bonus per match in a row after the first, e.g. 0.10
	 * @param max      highest bonus, e.g. 0.30
	 * @return the XP bonus, 0.0 for the first match together
	 */
	public static double bonus(final int streak, final double perMatch, final double max) {
		return Math.max(0.0D, Math.min(max, perMatch * (streak - 1)));
	}

	public int size() {
		return this.entries.size();
	}

	private void purge(final long now, final long expireMs) {
		this.entries.values().removeIf(entry -> now - entry.lastMatch > expireMs);
	}

	/** Order independent 64-bit fingerprint of a set of players. */
	static long fingerprint(final Collection<UUID> team) {
		final List<UUID> sorted = new ArrayList<>(team);

		sorted.sort(null);

		long hash = 1125899906842597L;

		for (final UUID uuid : sorted) {
			hash = 31 * hash + uuid.getMostSignificantBits();
			hash = 31 * hash + uuid.getLeastSignificantBits();
		}

		return hash;
	}
}
