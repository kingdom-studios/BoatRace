package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/** /bre laps <arena> <count> - laps of a normal race on the arena (events can override it). */
final class LapsSub extends RaceSubCommand {

	LapsSub(final SimpleCommandGroup group) {
		super(group, "laps", "Set how many laps a race on the arena has (events can override it).", "<arena> <count>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final Integer laps = this.optionalLaps(1);

		arena.setLaps(laps);
		this.plugin().getArenas().save();

		this.info("arena.laps-set", "arena", arena.getName(), "laps", laps);
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("1", "2", "3", "5", "10") : new ArrayList<>();
	}
}
