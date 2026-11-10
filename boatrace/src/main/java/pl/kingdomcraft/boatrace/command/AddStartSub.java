package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

/**
 * /bre addstart <arena> - adds the wand selection as the next segment of the start / finish line. Several segments
 * form one curved / slanted / arc-shaped line; entering any of them counts as crossing the line.
 */
final class AddStartSub extends RaceSubCommand {

	AddStartSub(final SimpleCommandGroup group) {
		super(group, "addstart|addstartsegment", "Add your wand selection as the next segment of the start/finish line (curved lines).", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);
		final Region region = this.requireSelection(player);

		arena.getStartSegments().add(region);
		this.plugin().getArenas().save();

		this.info("arena.start-segment-added", "arena", arena.getName(), "index", arena.getStartSegments().size(),
				"count", arena.getStartSegments().size(), "region", region.describe());
		this.warnSpawnPlacement(arena);
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
