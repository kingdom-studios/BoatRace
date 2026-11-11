package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

final class ListSub extends RaceSubCommand {

	ListSub(final SimpleCommandGroup group) {
		super(group, "list", "List all arenas.", null, 0, false);
	}

	@Override
	protected void onCommand() {
		if (this.plugin().getArenas().all().isEmpty())
			this.fail("arena.list-empty");

		this.info("arena.list-header");

		for (final Arena arena : this.plugin().getArenas().all())
			this.info(!arena.isReady() ? "arena.list-not-ready" : arena.isMaintenance() ? "arena.list-maintenance" : "arena.list-ready",
					"arena", arena.getName(), "checkpoints", arena.getCheckpoints().size(), "missing", arena.missing());
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} list" };
	}
}
