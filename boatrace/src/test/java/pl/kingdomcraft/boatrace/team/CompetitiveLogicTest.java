package pl.kingdomcraft.boatrace.team;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CompetitiveLogicTest {

	@Test
	void lobbyTakesTeamsInQueueOrderWhileTheyFit() {
		// capacity 8: party of 4, solo, party of 3 -> 8 players
		assertEquals(List.of(0, 1, 2), LobbyFill.select(List.of(4, 1, 3), 8, 8, 4));

		// the party of 4 does not fit in the 3 places left after 4 + 1: skipped, the solo player behind it plays
		assertEquals(List.of(0, 1, 3), LobbyFill.select(List.of(4, 1, 4, 1), 7, 8, 4));

		// a party bigger than the team limit never plays
		assertEquals(List.of(1), LobbyFill.select(List.of(5, 2), 8, 8, 4));

		// no more teams than colors
		assertEquals(List.of(0, 1), LobbyFill.select(List.of(1, 1, 1), 8, 2, 4));
		assertEquals(5, LobbyFill.players(List.of(4, 1, 3), List.of(0, 1)));
	}

	@Test
	void teamsScoreTheAveragePerMember() {
		final UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), a3 = UUID.randomUUID(), solo = UUID.randomUUID();
		final Map<UUID, Integer> teamOf = Map.of(a1, 0, a2, 0, a3, 0, solo, 1);
		final int[] points = { 10, 8, 6, 5 };

		// team of 3 takes 1st, 2nd, 4th: (10 + 8 + 5) / 3 = 7.67; the solo player 3rd: 6
		List<TeamScoring.Standing> standings = TeamScoring.standings(List.of(a1, a2, solo, a3), teamOf, 2, points);

		assertEquals(0, standings.get(0).team());
		assertEquals(23.0D / 3, standings.get(0).points(), 1e-9);
		assertEquals("7.7", standings.get(0).pointsText());

		// the solo player wins, the team's 2nd, 3rd and a DNF: (8 + 6 + 0) / 3 = 4.67 < 10
		standings = TeamScoring.standings(List.of(solo, a1, a2), teamOf, 2, points);
		assertEquals(1, standings.get(0).team());
		assertEquals("10", standings.get(0).pointsText());

		assertEquals(14.0D / 3, TeamScoring.liveAverage(List.of(solo, a1, a2), teamOf, 0, points), 1e-9);
	}

	@Test
	void equalAveragesGoToTheTeamWithTheBestPlace() {
		final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
		final Map<UUID, Integer> teamOf = Map.of(a, 0, b, 0, c, 1, d, 1);

		// A: 1st + 4th = 9 + 1, B: 2nd + 3rd = 6 + 4 -> 5.0 each, A has the 1st place
		final List<TeamScoring.Standing> standings = TeamScoring.standings(List.of(a, c, d, b), teamOf, 2, new int[] { 9, 6, 4, 1 });

		assertEquals(List.of(0, 1), standings.stream().map(TeamScoring.Standing::team).toList());
	}

	@Test
	void theSameLineupComingBackEarnsABonus() {
		final Lineups lineups = new Lineups();
		final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
		final long minute = 60_000L, expire = 30 * minute;

		assertEquals(1, lineups.countMatch(List.of(a, b), 0, expire));
		assertEquals(2, lineups.countMatch(List.of(b, a), minute, expire), "the order of the members does not matter");
		assertEquals(3, lineups.countMatch(List.of(a, b), 2 * minute, expire));

		// a new member: a new lineup
		assertEquals(1, lineups.countMatch(List.of(a, b, c), 3 * minute, expire));
		// back to the old lineup: A and B played the last match with C, so it starts again
		assertEquals(1, lineups.countMatch(List.of(a, b), 4 * minute, expire));

		// too long a break
		assertEquals(1, lineups.countMatch(List.of(a, b), 40 * minute, expire));
		assertEquals(1, lineups.countMatch(List.of(c), 41 * minute, expire), "a solo player has no lineup");

		assertEquals(0.0D, Lineups.bonus(1, 0.10, 0.30));
		assertEquals(0.20D, Lineups.bonus(3, 0.10, 0.30), 1e-9);
		assertEquals(0.30D, Lineups.bonus(9, 0.10, 0.30), 1e-9);
	}
}
