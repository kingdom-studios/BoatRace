package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/** /bre start <arena> - starts the F1 lights of the event lobby on that arena. */
final class StartSub extends RaceSubCommand {

	StartSub(final SimpleCommandGroup group) {
		super(group, "start", "Start the lights of the event on an arena.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		EventSub.report(this, arena, this.plugin().getRaces().startEvent(arena));
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
