package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

final class ListSub extends RaceSubCommand {

	ListSub(final SimpleCommandGroup group) {
		super(group, "list", "Pokaż listę aren.", null, 0, false);
	}

	@Override
	protected void onCommand() {
		if (this.plugin().getArenas().all().isEmpty())
			this.returnTell("&cNie utworzono jeszcze żadnej areny.");

		this.info("Areny:");

		for (final Arena arena : this.plugin().getArenas().all())
			this.info(" &8- &f" + arena.getName() + " " + (arena.isReady() ? "&a(aktywna)" : "&c(niegotowa: brak spawnu lub mety)")
					+ " &7checkpointy: &f" + arena.getCheckpoints().size());
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} list" };
	}
}
