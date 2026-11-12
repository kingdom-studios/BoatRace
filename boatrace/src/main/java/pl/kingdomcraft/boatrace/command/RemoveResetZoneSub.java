package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

/** /bre removeresetzone <arena> <index|all|list> - removes / lists the reset zones of an arena. */
final class RemoveResetZoneSub extends RaceSubCommand {

	RemoveResetZoneSub(final SimpleCommandGroup group) {
		super(group, "removeresetzone|delresetzone", "Remove a reset zone by index ('all' / 'list').", "<arena> <index|all|list>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final List<Region> zones = arena.getResetZones();
		final String arg = this.args[1];

		if ("list".equalsIgnoreCase(arg)) {
			if (zones.isEmpty())
				this.fail("arena.reset-zones-none", "arena", arena.getName());

			this.info("arena.reset-zones-header", "arena", arena.getName(), "count", zones.size());

			for (int i = 0; i < zones.size(); i++)
				this.info("arena.reset-zones-line", "index", i + 1, "region", zones.get(i).describe());

			return;
		}

		if ("all".equalsIgnoreCase(arg)) {
			final int count = zones.size();

			zones.clear();
			this.plugin().getArenas().save();

			this.info("arena.reset-zones-cleared", "arena", arena.getName(), "count", count);
			return;
		}

		final int index;

		try {
			index = Integer.parseInt(arg);

		} catch (final NumberFormatException ex) {
			this.fail("arena.not-a-number", "value", arg);
			return;
		}

		if (index < 1 || index > zones.size()) {
			this.fail("arena.reset-zone-out-of-range", "arena", arena.getName(), "count", zones.size(), "index", index);
			return;
		}

		zones.remove(index - 1);
		this.plugin().getArenas().save();

		this.info("arena.reset-zone-removed", "arena", arena.getName(), "index", index, "count", zones.size());
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
				for (int i = 1; i <= arena.getResetZones().size(); i++)
					options.add(String.valueOf(i));

			return this.completeLastWord(options);
		}

		return new ArrayList<>();
	}
}
