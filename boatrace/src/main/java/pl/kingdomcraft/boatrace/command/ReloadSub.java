package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class ReloadSub extends RaceSubCommand {

	ReloadSub(final SimpleCommandGroup group) {
		super(group, "reload", "Reload config.yml, messages.yml and arenas.yml.", null, 0, true);
	}

	@Override
	protected void onCommand() {
		this.plugin().reloadAll();
		this.info("admin.reloaded");
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} reload" };
	}
}
