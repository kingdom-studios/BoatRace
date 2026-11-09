package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class ReloadSub extends RaceSubCommand {

	ReloadSub(final SimpleCommandGroup group) {
		super(group, "reload", "Reload config.yml and arenas.yml.", null, 0, true);
	}

	@Override
	protected void onCommand() {
		this.plugin().reloadAll();
		this.info("&aReloaded config.yml and arenas.yml (racers were sent to spawn).");
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} reload" };
	}
}
