package pl.kingdomcraft.boatrace.command;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

/** /bre pos1 and /bre pos2 - sets the corner to the block the admin stands in (handy on water / in the air). */
final class PosSub extends RaceSubCommand {

	private final int index;

	PosSub(final SimpleCommandGroup group, final int index) {
		super(group, "pos" + (index + 1), "Set selection corner " + (index + 1) + " to your current position.", null, 0, true);

		this.index = index;
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();

		this.plugin().getSelections().setPosition(player, this.index, player.getLocation());
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} pos" + (this.index + 1) };
	}
}
