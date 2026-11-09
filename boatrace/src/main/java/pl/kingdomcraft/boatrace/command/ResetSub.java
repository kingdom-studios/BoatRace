package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class ResetSub extends RaceSubCommand {

	ResetSub(final SimpleCommandGroup group) {
		super(group, "reset|restart", "Zrestartuj przejazd (nowa łódka, odliczanie od nowa).", null, 0, false);
	}

	@Override
	protected void onCommand() {
		this.plugin().getRaces().reset(this.requirePlayer());
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} reset" };
	}
}
