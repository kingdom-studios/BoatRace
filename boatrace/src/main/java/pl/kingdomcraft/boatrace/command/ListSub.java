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
			this.returnTell("&cNo arena has been created yet.");

		this.info("Arenas:");

		for (final Arena arena : this.plugin().getArenas().all())
			this.info(" &8- &f" + arena.getName() + " " + (arena.isReady() ? "&a(ready)" : "&c(not ready, missing: " + arena.missing() + ")")
					+ " &7checkpoints: &f" + arena.getCheckpoints().size());
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} list" };
	}
}
