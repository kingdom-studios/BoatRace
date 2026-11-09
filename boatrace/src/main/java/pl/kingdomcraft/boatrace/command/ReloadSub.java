package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class ReloadSub extends RaceSubCommand {

	ReloadSub(final SimpleCommandGroup group) {
		super(group, "reload", "Przeładuj config.yml i arenas.yml.", null, 0, true);
	}

	@Override
	protected void onCommand() {
		this.plugin().reloadAll();
		this.info("&aPrzeładowano config.yml i arenas.yml (zawodnicy zostali odesłani na spawn).");
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} reload" };
	}
}
