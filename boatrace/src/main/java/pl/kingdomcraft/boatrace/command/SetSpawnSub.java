package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Point;

/** /bre setspawn <arena> [index] - grid position (default #1, the solo spawn). */
final class SetSpawnSub extends RaceSubCommand {

	SetSpawnSub(final SimpleCommandGroup group) {
		super(group, "setspawn", "Set grid position #index (default 1 = solo spawn) to your position and facing. Must be BEFORE the start line.", "<arena> [index]", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);
		final int index = this.args.length > 1 ? this.gridIndex(this.args[1]) : 1;

		arena.getSpawns().put(index, Point.of(player.getLocation()));
		this.plugin().getArenas().save();

		this.info("arena.spawn-set", "arena", arena.getName(), "index", index);
		this.warnSpawnPlacement(arena);
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2) {
			final List<String> indexes = new ArrayList<>();

			for (int i = 1; i <= this.plugin().getSettings().maxGridPositions; i++)
				indexes.add(String.valueOf(i));

			return this.completeLastWord(indexes);
		}

		return new ArrayList<>();
	}
}
