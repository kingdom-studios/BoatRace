package pl.kingdomcraft.boatrace.placeholder;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.model.SimpleExpansion;

import pl.kingdomcraft.boatrace.BoatRacePlugin;
import pl.kingdomcraft.boatrace.storage.PlayerRating;
import pl.kingdomcraft.boatrace.storage.RaceRecord;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * PlaceholderAPI expansion, identifier "boatrace" (Foundation uses the plugin name):
 *
 *   %boatrace_besttime_<arena>%              best time of the player asking
 *   %boatrace_top_player_<arena>_<position>% name of the player on that position
 *   %boatrace_top_time_<arena>_<position>%   time on that position
 *   %boatrace_top_server_<arena>_<position>% server (Server.Name) the time was driven on
 *   %boatrace_playing%                       players currently racing (all arenas)
 *   %boatrace_playing_<arena>%               players currently racing on that arena
 *
 * Ranking (holograms, scoreboards; the leaderboard needs ranking.leaderboard-min-games ranked races):
 *   %boatrace_rating%                        ranking points of the player asking
 *   %boatrace_rating_rank%                   their position on the ranking leaderboard (empty text if not on it)
 *   %boatrace_rating_games% / _wins%         their ranked races / wins
 *   %boatrace_rating_streak% / _best_streak% their current / longest run of ranked races without being last
 *   %boatrace_rating_multiplier%             their season pass XP multiplier, e.g. 1.25
 *   %boatrace_rating_top_player_<position>%  name on that position of the ranking leaderboard
 *   %boatrace_rating_top_value_<position>%   points on that position
 *
 * Competitive:
 *   %boatrace_team%                          colored team name while the player races a competitive match ("" otherwise)
 *   %boatrace_team_tag%                      the team tag (competitive.luckperms.format), same text as the LuckPerms meta
 *   %boatrace_comp_games% / %boatrace_comp_wins%   competitive matches played / won by the player's team
 *
 * Leaderboards / best times are the personal bests of the standard single-lap race; multi-lap totals are only stored
 * in the event results table.
 * Arena names may contain underscores. Registered automatically by Foundation (final class,
 * single private constructor + private static final instance).
 */
public final class BoatRaceExpansion extends SimpleExpansion {

	@SuppressWarnings("unused")
	private static final BoatRaceExpansion instance = new BoatRaceExpansion();

	private BoatRaceExpansion() {
	}

	@Override
	protected String onReplace(final CommandSender sender, final String identifier) {
		final BoatRacePlugin plugin = BoatRacePlugin.getInstance();
		final RecordStorage storage = plugin.getStorage();

		if (storage == null)
			return NO_REPLACE;

		final String empty = plugin.getSettings().placeholderEmpty;
		final String lower = identifier.toLowerCase(Locale.ROOT);

		// player counts - PlaceholderAPI may call from async threads, the counters are thread safe
		if (lower.equals("playing") || lower.startsWith("playing_")) {
			if (plugin.getRaces() == null)
				return "0";

			return String.valueOf(lower.equals("playing") ? plugin.getRaces().playingCount()
					: plugin.getRaces().playingCount(identifier.substring("playing_".length())));
		}

		if (lower.startsWith("rating"))
			return this.rating(plugin, storage, sender, lower, empty);

		if (lower.startsWith("team") || lower.startsWith("comp_"))
			return this.team(plugin, storage, sender, lower, empty);

		if (lower.startsWith("besttime_")) {
			final String arena = identifier.substring("besttime_".length());

			if (!(sender instanceof Player player))
				return empty;

			final RaceRecord record = storage.getRecord(arena, player.getUniqueId());

			return record == null ? empty : TimeFmt.format(record.getBestMs());
		}

		final boolean topPlayer = lower.startsWith("top_player_");
		final boolean topTime = lower.startsWith("top_time_");
		final boolean topServer = lower.startsWith("top_server_");

		if (topPlayer || topTime || topServer) {
			final String rest = identifier.substring(topPlayer ? "top_player_".length() : topTime ? "top_time_".length() : "top_server_".length());
			final int split = rest.lastIndexOf('_');

			if (split <= 0)
				return NO_REPLACE;

			final String arena = rest.substring(0, split);
			final int position;

			try {
				position = Integer.parseInt(rest.substring(split + 1));

			} catch (final NumberFormatException ex) {
				return NO_REPLACE;
			}

			final List<RaceRecord> top = storage.getTop(arena);

			if (position < 1 || position > top.size())
				return empty;

			final RaceRecord record = top.get(position - 1);

			if (topServer)
				return record.getServer().isEmpty() ? empty : record.getServer();

			return topPlayer ? record.getName() : TimeFmt.format(record.getBestMs());
		}

		return NO_REPLACE;
	}

	private String team(final BoatRacePlugin plugin, final RecordStorage storage, final CommandSender sender, final String lower, final String empty) {
		if (!(sender instanceof Player player))
			return lower.startsWith("team") ? "" : empty;

		switch (lower) {
			case "team": {
				final pl.kingdomcraft.boatrace.team.TeamColor color = plugin.getRaces() != null ? plugin.getRaces().teamColorOf(player.getUniqueId()) : null;

				return color == null ? "" : color.colored();
			}

			case "team_tag":
				return plugin.getRaces() != null ? plugin.getRaces().teamTagOf(player.getUniqueId()) : "";

			case "comp_games":
				return String.valueOf(storage.getRating(player.getUniqueId(), player.getName()).compGames());

			case "comp_wins":
				return String.valueOf(storage.getRating(player.getUniqueId(), player.getName()).compWins());

			default:
				return NO_REPLACE;
		}
	}

	private String rating(final BoatRacePlugin plugin, final RecordStorage storage, final CommandSender sender, final String lower, final String empty) {
		final boolean topPlayer = lower.startsWith("rating_top_player_");

		if (topPlayer || lower.startsWith("rating_top_value_")) {
			final int position;

			try {
				position = Integer.parseInt(lower.substring(topPlayer ? "rating_top_player_".length() : "rating_top_value_".length()));

			} catch (final NumberFormatException ex) {
				return NO_REPLACE;
			}

			final List<PlayerRating> top = storage.getRatingTop();

			if (position < 1 || position > top.size())
				return empty;

			final PlayerRating rating = top.get(position - 1);

			return topPlayer ? rating.name() : String.valueOf(rating.rating());
		}

		if (!(sender instanceof Player player))
			return empty;

		final PlayerRating rating = storage.getRating(player.getUniqueId(), player.getName());

		switch (lower) {
			case "rating":
				return String.valueOf(rating.rating());

			case "rating_rank": {
				final int rank = storage.getRatingRank(player.getUniqueId());

				return rank > 0 ? String.valueOf(rank) : empty;
			}

			case "rating_games":
				return String.valueOf(rating.games());

			case "rating_wins":
				return String.valueOf(rating.wins());

			case "rating_streak":
				return String.valueOf(rating.streak());

			case "rating_best_streak":
				return String.valueOf(rating.bestStreak());

			case "rating_multiplier":
				return String.valueOf(plugin.getSettings().xpMultiplier(rating.rating()));

			default:
				return NO_REPLACE;
		}
	}
}
