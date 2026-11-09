package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

final class DelCheckpointSub extends RaceSubCommand {

	DelCheckpointSub(final SimpleCommandGroup group) {
		super(group, "delcheckpoint|delcp", "Remove a checkpoint by number, or all of them.", "<arena> <number|all>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		if ("all".equalsIgnoreCase(this.args[1])) {
			final int count = arena.getCheckpoints().size();

			arena.getCheckpoints().clear();
			this.plugin().getArenas().save();

			this.info("&aRemoved all &f" + count + " &acheckpoints of &f" + arena.getName() + "&a.");
			return;
		}

		final int number;

		try {
			number = Integer.parseInt(this.args[1]);

		} catch (final NumberFormatException ex) {
			this.returnTell("&cNot a number: &f" + this.args[1]);
			return;
		}

		if (number < 1 || number > arena.getCheckpoints().size())
			this.returnTell("&cArena &f" + arena.getName() + " &chas checkpoints 1-" + arena.getCheckpoints().size() + ".");

		arena.getCheckpoints().remove(number - 1);
		this.plugin().getArenas().save();

		this.info("&aRemoved checkpoint &f#" + number + " &aof &f" + arena.getName() + "&a. The following ones moved up by one.");
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2) {
			final List<String> options = new ArrayList<>();
			final Arena arena = this.plugin().getArenas().get(this.args[0]);

			options.add("all");

			if (arena != null)
				for (int i = 1; i <= arena.getCheckpoints().size(); i++)
					options.add(String.valueOf(i));

			return this.completeLastWord(options);
		}

		return new ArrayList<>();
	}
}
