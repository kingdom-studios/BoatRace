package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class LeaveSub extends RaceSubCommand {

	LeaveSub(final SimpleCommandGroup group) {
		super(group, "leave|hub", "Leave the race and go back to spawn.", null, 0, false);
	}

	@Override
	protected void onCommand() {
		this.plugin().getRaces().leave(this.requirePlayer());
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} leave" };
	}
}
