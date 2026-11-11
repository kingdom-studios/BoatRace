package pl.kingdomcraft.boatrace.placeholder;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.model.SimpleExpansion;

import pl.kingdomcraft.boatrace.BoatRacePlugin;
import pl.kingdomcraft.boatrace.arena.Arena;
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
 * Leaderboards are those of the arena's configured lap count (laps in arenas.yml).
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

		if (lower.startsWith("besttime_")) {
			final String arena = identifier.substring("besttime_".length());

			if (!(sender instanceof Player player))
				return empty;

			final RaceRecord record = storage.getRecord(arena, lapsOf(plugin, arena), player.getUniqueId());

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

			final List<RaceRecord> top = storage.getTop(arena, lapsOf(plugin, arena));

			if (position < 1 || position > top.size())
				return empty;

			final RaceRecord record = top.get(position - 1);

			if (topServer)
				return record.getServer().isEmpty() ? empty : record.getServer();

			return topPlayer ? record.getName() : TimeFmt.format(record.getBestMs());
		}

		return NO_REPLACE;
	}

	private static int lapsOf(final BoatRacePlugin plugin, final String arenaName) {
		final Arena arena = plugin.getArenas().get(arenaName);

		return arena == null ? 1 : arena.getLaps();
	}
}
