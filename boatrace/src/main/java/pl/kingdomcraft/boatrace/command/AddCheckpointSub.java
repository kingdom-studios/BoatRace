package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Point;

final class AddCheckpointSub extends RaceSubCommand {

	AddCheckpointSub(final SimpleCommandGroup group) {
		super(group, "addcheckpoint|addcp", "Dodaj niewidzialny checkpoint (blok na który patrzysz, albo Twoja pozycja).", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);
		final Location where = targetOrStanding(player, 30);

		arena.getCheckpoints().add(Point.of(where));
		this.plugin().getArenas().save();

		this.info("&aDodano checkpoint &f#" + arena.getCheckpoints().size() + " &aareny &f" + arena.getName()
				+ " &7(" + where.getBlockX() + ", " + where.getBlockY() + ", " + where.getBlockZ() + ")&a. Checkpointy są niewidzialne w trakcie wyścigu.");
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
