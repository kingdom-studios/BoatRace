package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.race.RaceManager.EventInfo;
import pl.kingdomcraft.boatrace.race.RaceManager.EventResult;

/**
 * /bre event <arena> open [laps] | start [laps] | close | status
 * /bre event start <arena> [laps]   (same as /bre start <arena> [laps])
 *
 * open:  a lobby on a free instance of the arena; joining players take grid positions 1, 2, 3 ...
 * start: starts the lights (same as /bre start <arena>); also automatic when the grid is full
 * close: everybody back to the hub, the arena is free again
 */
final class EventSub extends RaceSubCommand {

	EventSub(final SimpleCommandGroup group) {
		super(group, "event", "Event mode: multiplayer start on the grid.", "<arena> <open|start|close|status> [laps]", 2, true);
	}

	@Override
	protected void onCommand() {
		final RaceManager races = this.plugin().getRaces();

		// "/bre event start <arena> [laps]" word order
		if ("start".equalsIgnoreCase(this.args[0]) && this.plugin().getArenas().get(this.args[0]) == null) {
			final Arena arena = this.requireArena(1);

			report(this, arena, races.startEvent(arena, this.optionalLaps(2)));
			return;
		}

		final Arena arena = this.requireArena(0);

		switch (this.args[1].toLowerCase()) {
			case "open" -> report(this, arena, races.openEvent(arena, this.optionalLaps(2)));
			case "start" -> report(this, arena, races.startEvent(arena, this.optionalLaps(2)));
			case "close" -> report(this, arena, races.closeEvent(arena));
			case "status" -> {
				final EventInfo info = races.eventInfo(arena);

				if (info == null)
					this.fail("event.no-event", "arena", arena.getName());

				this.info("event.status", "arena", arena.getName(), "world", info.world(), "phase", info.phase(),
						"players", info.players(), "slots", info.capacity(), "laps", info.laps());
			}
			default -> this.returnInvalidArgs();
		}
	}

	/** Shared by /bre event and /bre start. */
	static void report(final RaceSubCommand command, final Arena arena, final EventResult result) {
		final EventInfo info = command.plugin().getRaces().eventInfo(arena);
		final String key = switch (result) {
			case OPENED -> "event.opened";
			case STARTED -> "event.started";
			case CLOSED -> "event.closed";
			case ALREADY_OPEN -> "event.already-open";
			case ALREADY_STARTED -> "event.already-started";
			case NO_EVENT -> "event.no-event";
			case NOT_READY -> "event.not-ready";
			case BUSY -> "event.busy";
			case EMPTY -> "event.empty";
		};

		command.info(key, "arena", arena.getName(),
				"world", info == null ? "-" : info.world(),
				"slots", info == null ? arena.getSpawns().size() : info.capacity(),
				"laps", info == null ? arena.getLaps() : info.laps());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1) {
			final List<String> options = new ArrayList<>(this.arenaNames());

			options.add("start");
			return this.completeLastWord(options);
		}

		if (this.args.length == 2)
			return "start".equalsIgnoreCase(this.args[0]) && this.plugin().getArenas().get(this.args[0]) == null
					? this.completeLastWord(this.arenaNames())
					: this.completeLastWord("open", "start", "close", "status");

		return new ArrayList<>();
	}
}
