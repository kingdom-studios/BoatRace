package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/** /bre delspawn <arena> <index> - removes a grid position; /bre delspawn <arena> list shows them. */
final class DelSpawnSub extends RaceSubCommand {

	DelSpawnSub(final SimpleCommandGroup group) {
		super(group, "delspawn", "Remove a grid position (or 'list' them).", "<arena> <index|list>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		if ("list".equalsIgnoreCase(this.args[1])) {
			this.info("arena.spawn-list", "arena", arena.getName(), "spawns", spawnList(arena));
			return;
		}

		final int index = this.gridIndex(this.args[1]);

		if (arena.getSpawns().remove(index) == null)
			this.fail("arena.spawn-not-found", "arena", arena.getName(), "index", index);

		this.plugin().getArenas().save();
		this.info("arena.spawn-removed", "arena", arena.getName(), "index", index);
	}

	static String spawnList(final Arena arena) {
		if (arena.getSpawns().isEmpty())
			return "-";

		final List<String> parts = new ArrayList<>();

		for (final Integer index : arena.getSpawns().keySet())
			parts.add("#" + index);

		return String.join(", ", parts);
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2) {
			final List<String> options = new ArrayList<>();
			final Arena arena = this.plugin().getArenas().get(this.args[0]);

			options.add("list");

			if (arena != null)
				for (final Integer index : arena.getSpawns().keySet())
					options.add(String.valueOf(index));

			return this.completeLastWord(options);
		}

		return new ArrayList<>();
	}
}
