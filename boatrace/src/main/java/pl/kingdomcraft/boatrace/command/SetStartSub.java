package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

final class SetStartSub extends RaceSubCommand {

	SetStartSub(final SimpleCommandGroup group) {
		super(group, "setstart|setstartline", "Set the start line to your wand selection (also the finish unless setfinish is used).", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);
		final Region region = this.requireSelection(player);

		arena.setStartLine(region);
		this.plugin().getArenas().save();

		this.info("&aStart line of &f" + arena.getName() + " &aset: &7" + region.describe()
				+ (arena.getFinishLineRaw() == null ? " &7(it is also the finish line - circuit)" : ""));
		this.warnSpawnPlacement(arena);
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
