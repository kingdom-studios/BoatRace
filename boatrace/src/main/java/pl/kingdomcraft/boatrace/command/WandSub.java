package pl.kingdomcraft.boatrace.command;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

final class WandSub extends RaceSubCommand {

	WandSub(final SimpleCommandGroup group) {
		super(group, "wand", "Get the selection wand (left click = pos1, right click = pos2).", null, 0, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();

		player.getInventory().addItem(this.plugin().getSelections().createWand());
		this.info("selection.wand-received");
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} wand" };
	}
}
