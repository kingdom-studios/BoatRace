package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

final class AddCheckpointSub extends RaceSubCommand {

	AddCheckpointSub(final SimpleCommandGroup group) {
		super(group, "addcheckpoint|addcp", "Add your wand selection as the next invisible checkpoint zone.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);
		final Region region = this.requireSelection(player);

		arena.getCheckpoints().add(region);
		this.plugin().getArenas().save();

		this.info(arena.isReady() ? "arena.checkpoint-added" : "arena.checkpoint-added-missing",
				"number", arena.getCheckpoints().size(), "arena", arena.getName(), "region", region.describe(), "missing", arena.missing());
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
