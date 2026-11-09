package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Point;

final class SetStartBlockSub extends RaceSubCommand {

	SetStartBlockSub(final SimpleCommandGroup group) {
		super(group, "setstartblock", "Zaznacz blok linii startowej (patrz na blok); nad nim pojawią się światła F1.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);

		Block block = player.getTargetBlockExact(15);

		if (block == null)
			block = player.getLocation().getBlock();

		// Direction of travel decides along which axis the row of lamps is built
		final double yaw = ((player.getLocation().getYaw() % 360.0D) + 360.0D) % 360.0D;
		final boolean travelAlongX = (yaw >= 45.0D && yaw < 135.0D) || (yaw >= 225.0D && yaw < 315.0D);

		arena.setStartBlock(Point.of(block.getLocation()));
		arena.setLightsAlongX(!travelAlongX);
		this.plugin().getArenas().save();

		this.info("&aUstawiono blok startowy areny &f" + arena.getName() + " &a(" + block.getX() + ", " + block.getY() + ", " + block.getZ()
				+ "). Światła F1 zapalą się nad nim, w rzędzie prostopadłym do Twojego kierunku patrzenia.");
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
