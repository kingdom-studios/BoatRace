package pl.kingdomcraft.boatrace.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Who plays the next competitive match of a lobby: teams (a party, or a solo player as a team of 1) are taken in
 * queue order (first come, first served) as long as they fit. A team that does not fit any more (too few grid
 * positions left, too big, no color left) is skipped and keeps its place for the next match.
 */
public final class LobbyFill {

	private LobbyFill() {
	}

	/**
	 * @param sizes       size of every queued team, in queue order
	 * @param capacity    most racers of the match (grid positions)
	 * @param maxTeams    most teams of the match (number of team colors)
	 * @param maxTeamSize biggest allowed team
	 * @return indexes (into {@code sizes}) of the teams of the match, in queue order
	 */
	public static List<Integer> select(final List<Integer> sizes, final int capacity, final int maxTeams, final int maxTeamSize) {
		final List<Integer> selected = new ArrayList<>();
		int free = capacity;

		for (int index = 0; index < sizes.size() && selected.size() < maxTeams && free > 0; index++) {
			final int size = sizes.get(index);

			if (size >= 1 && size <= maxTeamSize && size <= free) {
				selected.add(index);
				free -= size;
			}
		}

		return selected;
	}

	/** @return the number of racers of the selected teams */
	public static int players(final List<Integer> sizes, final List<Integer> selected) {
		int players = 0;

		for (final int index : selected)
			players += sizes.get(index);

		return players;
	}
}
