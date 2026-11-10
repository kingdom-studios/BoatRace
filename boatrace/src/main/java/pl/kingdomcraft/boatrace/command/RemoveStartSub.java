package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

/** /bre removestart <arena> <index|all|list> - removes / lists segments of the start / finish line. */
final class RemoveStartSub extends RaceSubCommand {

	RemoveStartSub(final SimpleCommandGroup group) {
		super(group, "removestart|delstart", "Remove a start/finish line segment by index ('all' / 'list').", "<arena> <index|all|list>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final List<Region> segments = arena.getStartSegments();
		final String arg = this.args[1];

		if ("list".equalsIgnoreCase(arg)) {
			if (segments.isEmpty())
				this.fail("arena.start-segments-none", "arena", arena.getName());

			this.info("arena.start-segments-header", "arena", arena.getName(), "count", segments.size());

			for (int i = 0; i < segments.size(); i++)
				this.info("arena.start-segments-line", "index", i + 1, "region", segments.get(i).describe());

			return;
		}

		if ("all".equalsIgnoreCase(arg)) {
			final int count = segments.size();

			segments.clear();
			this.plugin().getArenas().save();

			this.info("arena.start-segments-cleared", "arena", arena.getName(), "count", count);
			return;
		}

		final int index;

		try {
			index = Integer.parseInt(arg);

		} catch (final NumberFormatException ex) {
			this.fail("arena.not-a-number", "value", arg);
			return;
		}

		if (index < 1 || index > segments.size())
			this.fail("arena.start-segment-out-of-range", "arena", arena.getName(), "count", segments.size(), "index", index);

		segments.remove(index - 1);
		this.plugin().getArenas().save();

		this.info("arena.start-segment-removed", "arena", arena.getName(), "index", index, "count", segments.size(),
				"missing", arena.missing());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2) {
			final List<String> options = new ArrayList<>();
			final Arena arena = this.plugin().getArenas().get(this.args[0]);

			options.add("list");
			options.add("all");

			if (arena != null)
				for (int i = 1; i <= arena.getStartSegments().size(); i++)
					options.add(String.valueOf(i));

			return this.completeLastWord(options);
		}

		return new ArrayList<>();
	}
}
