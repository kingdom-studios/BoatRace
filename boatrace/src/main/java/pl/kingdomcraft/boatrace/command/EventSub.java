package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.race.RaceManager.EventInfo;
import pl.kingdomcraft.boatrace.race.RaceManager.EventResult;

/**
 * /bre event <arena> open|start|close|status
 *
 * open:  a lobby on a free instance of the arena; joining players take grid positions 1, 2, 3 ...
 * start: starts the lights (same as /bre start <arena>); also automatic when the grid is full
 * close: everybody back to the hub, the arena is free again
 */
final class EventSub extends RaceSubCommand {

	EventSub(final SimpleCommandGroup group) {
		super(group, "event", "Event mode: multiplayer start on the grid.", "<arena> <open|start|close|status>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final RaceManager races = this.plugin().getRaces();

		switch (this.args[1].toLowerCase()) {
			case "open" -> report(this, arena, races.openEvent(arena));
			case "start" -> report(this, arena, races.startEvent(arena));
			case "close" -> report(this, arena, races.closeEvent(arena));
			case "status" -> {
				final EventInfo info = races.eventInfo(arena);

				if (info == null)
					this.fail("event.no-event", "arena", arena.getName());

				this.info("event.status", "arena", arena.getName(), "world", info.world(), "phase", info.phase(),
						"players", info.players(), "slots", info.capacity());
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
				"slots", info == null ? arena.getSpawns().size() : info.capacity());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("open", "start", "close", "status") : new ArrayList<>();
	}
}
