package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.captainhook.CaptainHookHook;

/**
 * /bre tracks   track ids used in the website stats (fastest_<track>, fastest_race_<track>) -> arena names.
 * Warns when two arenas end up with the same id (their stats would be mixed).
 */
final class TracksSub extends RaceSubCommand {

	TracksSub(final SimpleCommandGroup group) {
		super(group, "tracks", "Track ids of the arenas for the website stats.", null, 0, true);
	}

	@Override
	protected void onCommand() {
		if (this.plugin().getArenas().all().isEmpty())
			this.fail("arena.list-empty");

		final Map<String, List<String>> byId = new LinkedHashMap<>();

		for (final Arena arena : this.plugin().getArenas().all())
			byId.computeIfAbsent(CaptainHookHook.trackId(arena.getName()), id -> new ArrayList<>()).add(arena.getName());

		this.info("ranking.tracks-header");

		for (final Map.Entry<String, List<String>> entry : byId.entrySet())
			if (entry.getValue().size() > 1)
				this.info("ranking.tracks-duplicate", "id", entry.getKey(), "arenas", String.join(", ", entry.getValue()));
			else
				this.info("ranking.tracks-line", "id", entry.getKey(), "arena", entry.getValue().get(0));
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} tracks" };
	}
}
