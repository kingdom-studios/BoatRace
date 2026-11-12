package pl.kingdomcraft.boatrace.storage;

import java.util.UUID;

/**
 * A player's ranking (table boatrace_ratings), shared by every server of the network. There is ONE rating per player:
 * casual ranked races (events, parties) and competitive team matches both move it, competitive ones harder.
 *
 * @param rating     ELO-like points
 * @param games      ranked races (casual and competitive, finished or left)
 * @param wins       ranked races won (1st place of the racer)
 * @param streak     current run of ranked races finished without being last (a DNF or a last place resets it)
 * @param bestStreak the longest such run ever
 * @param compGames  competitive matches played
 * @param compWins   competitive matches won by the player's team
 */
public record PlayerRating(UUID uuid, String name, int rating, int games, int wins, int streak, int bestStreak, int compGames, int compWins) {

	public static PlayerRating fresh(final UUID uuid, final String name, final int startRating) {
		return new PlayerRating(uuid, name, startRating, 0, 0, 0, 0, 0, 0);
	}

	PlayerRating withRating(final String newName, final int newRating) {
		return new PlayerRating(this.uuid, newName, newRating, this.games, this.wins, this.streak, this.bestStreak, this.compGames, this.compWins);
	}

	PlayerRating withRankedResult(final String newName, final boolean win, final int newStreak) {
		return new PlayerRating(this.uuid, newName, this.rating, this.games + 1, this.wins + (win ? 1 : 0), newStreak, Math.max(this.bestStreak, newStreak),
				this.compGames, this.compWins);
	}

	PlayerRating withCompetitiveResult(final String newName, final boolean win) {
		return new PlayerRating(this.uuid, newName, this.rating, this.games, this.wins, this.streak, this.bestStreak, this.compGames + 1,
				this.compWins + (win ? 1 : 0));
	}
}
