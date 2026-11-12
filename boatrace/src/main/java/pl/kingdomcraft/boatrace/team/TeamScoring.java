package pl.kingdomcraft.boatrace.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Competitive team result: every racer scores the F1 points of their finishing position (a racer who did not finish
 * scores 0), a team scores the AVERAGE of its members - so teams of different sizes are comparable and every member
 * counts, the weakest one too. Ties are broken by the best single position of the team.
 */
public final class TeamScoring {

	private TeamScoring() {
	}

	/**
	 * @param team         team index
	 * @param points       average points per member
	 * @param bestPosition best finishing position of a member, Integer.MAX_VALUE if nobody finished
	 * @param members      members of the team in the match (also those who left)
	 */
	public record Standing(int team, double points, int bestPosition, int members) {

		/** "8.5" / "10" */
		public String pointsText() {
			final double rounded = Math.round(this.points * 10.0D) / 10.0D;

			return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
		}
	}

	/**
	 * @param finishOrder racers who finished, first to last
	 * @param teamOf      team index of every racer of the match
	 * @param teams       number of teams
	 * @param points      points by position: points[0] for 1st place ...; positions beyond the list score 0
	 * @return every team, best first
	 */
	public static List<Standing> standings(final List<UUID> finishOrder, final Map<UUID, Integer> teamOf, final int teams, final int[] points) {
		final int[] sum = new int[teams];
		final int[] best = new int[teams];
		final int[] members = new int[teams];

		java.util.Arrays.fill(best, Integer.MAX_VALUE);

		for (final int team : teamOf.values())
			if (team >= 0 && team < teams)
				members[team]++;

		for (int index = 0; index < finishOrder.size(); index++) {
			final Integer team = teamOf.get(finishOrder.get(index));

			if (team == null || team < 0 || team >= teams)
				continue;

			sum[team] += index < points.length ? points[index] : 0;
			best[team] = Math.min(best[team], index + 1);
		}

		final List<Standing> standings = new ArrayList<>(teams);

		for (int team = 0; team < teams; team++)
			standings.add(new Standing(team, members[team] > 0 ? (double) sum[team] / members[team] : 0.0D, best[team], members[team]));

		standings.sort(Comparator.comparingDouble(Standing::points).reversed().thenComparingInt(Standing::bestPosition).thenComparingInt(Standing::team));

		return standings;
	}

	/** Live score during the race: the average points of the members who finished so far (DNF / still racing = 0). */
	public static double liveAverage(final List<UUID> finishOrder, final Map<UUID, Integer> teamOf, final int team, final int[] points) {
		int sum = 0;
		int members = 0;

		for (final int value : teamOf.values())
			if (value == team)
				members++;

		for (int index = 0; index < finishOrder.size(); index++) {
			final Integer value = teamOf.get(finishOrder.get(index));

			if (value != null && value == team)
				sum += index < points.length ? points[index] : 0;
		}

		return members > 0 ? (double) sum / members : 0.0D;
	}
}
