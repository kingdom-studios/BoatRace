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

				this.info("&aRemoved all start lamps of &f" + arena.getName() + "&a.");
			}
			case "add" -> {
				final Player player = this.requirePlayer();
				Region lamp = this.plugin().getSelections().getRegion(player);

				if (lamp == null) {
					final Block target = player.getTargetBlockExact(30);

					if (target == null)
						this.returnTell("&cSelect the lamp with the wand, or look at the lamp block (max 30 blocks).");

					lamp = Region.ofBlock(target.getLocation());
				}

				if (lamp.volume() > MAX_LAMP_VOLUME)
					this.returnTell("&cOne lamp may have at most " + MAX_LAMP_VOLUME + " blocks (selection: " + lamp.volume() + "). Use &f/bre lights "
							+ arena.getName() + " set &cto split a row into lamps.");

				arena.getLights().add(lamp);
				this.plugin().getArenas().save();

				// a fresh selection is needed for the next lamp
				this.plugin().getSelections().clear(player);

				this.info("&aAdded lamp &f#" + arena.getLights().size() + " &ato &f" + arena.getName() + "&a: &7" + lamp.describe()
						+ " &7(selection cleared - select the next lamp)");
			}
			case "set" -> {
				final Player player = this.requirePlayer();
				final Region region = this.requireSelection(player);
				final int lamps = this.plugin().getSettings().lamps;
				final List<Region> parts = region.split(lamps, this.plugin().getSelections().getPosition(player, 0));

				if (parts == null)
					this.returnTell("&cThe selection must be at least " + lamps + " blocks long (one block per lamp).");

				for (final Region part : parts)
					if (part.volume() > MAX_LAMP_VOLUME)
						this.returnTell("&cEach lamp would have " + part.volume() + " blocks (max " + MAX_LAMP_VOLUME + "). Select a smaller row.");

				arena.getLights().clear();
				arena.getLights().addAll(parts);
				this.plugin().getArenas().save();

				this.info("&aSet &f" + lamps + " &astart lamps for &f" + arena.getName() + "&a, lamp #1 is at pos1. They light up in order and turn black at lights out.");
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
