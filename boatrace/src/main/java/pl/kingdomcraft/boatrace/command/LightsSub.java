package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

/**
 * /bre lights <arena> add    - adds the wand selection (or the block you look at) as the NEXT lamp, so the admin
 *                              points out the lamps one by one in lighting order
 * /bre lights <arena> set    - splits the wand selection into N lamps (config start-lights.lamps), lamp #1 at pos1
 * /bre lights <arena> clear  - removes all lamps
 */
final class LightsSub extends RaceSubCommand {

	/** a single lamp may consist of several blocks, but not of a whole wall */
	private static final long MAX_LAMP_VOLUME = 64L;

	LightsSub(final SimpleCommandGroup group) {
		super(group, "lights", "Define the F1 start lamps (in lighting order).", "<arena> <add|set|clear>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final String action = this.args[1].toLowerCase();

		switch (action) {
			case "clear" -> {
				arena.getLights().clear();
				this.plugin().getArenas().save();

				this.info("lights.cleared", "arena", arena.getName());
			}
			case "add" -> {
				final Player player = this.requirePlayer();
				Region lamp = this.plugin().getSelections().getRegion(player);

				if (lamp == null) {
					final Block target = player.getTargetBlockExact(30);

					if (target == null)
						this.fail("lights.no-target");

					lamp = Region.ofBlock(target.getLocation());
				}

				if (lamp.volume() > MAX_LAMP_VOLUME)
					this.fail("lights.lamp-too-big", "max", MAX_LAMP_VOLUME, "volume", lamp.volume(), "arena", arena.getName());

				arena.getLights().add(lamp);
				this.plugin().getArenas().save();

				// a fresh selection is needed for the next lamp
				this.plugin().getSelections().clear(player);

				this.info("lights.added", "number", arena.getLights().size(), "arena", arena.getName(), "region", lamp.describe());
			}
			case "set" -> {
				final Player player = this.requirePlayer();
				final Region region = this.requireSelection(player);
				final int lamps = this.plugin().getSettings().lamps;
				final List<Region> parts = region.split(lamps, this.plugin().getSelections().getPosition(player, 0));

				if (parts == null)
					this.fail("lights.too-short", "lamps", lamps);

				for (final Region part : parts)
					if (part.volume() > MAX_LAMP_VOLUME)
						this.fail("lights.part-too-big", "volume", part.volume(), "max", MAX_LAMP_VOLUME);

				arena.getLights().clear();
				arena.getLights().addAll(parts);
				this.plugin().getArenas().save();

				this.info("lights.set", "lamps", lamps, "arena", arena.getName());
			}
			default -> this.returnInvalidArgs();
		}
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("add", "set", "clear") : new ArrayList<>();
	}
}
