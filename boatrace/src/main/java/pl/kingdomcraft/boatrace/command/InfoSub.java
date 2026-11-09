package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Point;
import pl.kingdomcraft.boatrace.arena.Region;

final class InfoSub extends RaceSubCommand {

	InfoSub(final SimpleCommandGroup group) {
		super(group, "info", "Show the setup of an arena.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final Point spawn = arena.getSpawn();

		this.info("Arena &f" + arena.getName() + " " + (arena.isReady() ? "&a(ready)" : "&c(not ready - missing: " + arena.missing() + ")"));
		this.info(" &8- &7Spawn: &f" + (spawn == null ? "-" : "(" + (int) Math.floor(spawn.x()) + ", " + (int) Math.floor(spawn.y()) + ", " + (int) Math.floor(spawn.z()) + ")"));
		this.info(" &8- &7Start line: &f" + describe(arena.getStartLine()));
		this.info(" &8- &7Finish line: &f" + (arena.getFinishLineRaw() == null ? "same as start line" : describe(arena.getFinishLineRaw())));
		this.info(" &8- &7Checkpoints: &f" + arena.getCheckpoints().size());
		this.info(" &8- &7Start lamps: &f" + arena.getLights().size());
		this.info(" &8- &7Reward: &f" + arena.getRewardMode().name() + " &7(" + arena.getRewardCommands().size() + " commands)");

		this.warnSpawnPlacement(arena);
	}

	private static String describe(final Region region) {
		return region == null ? "-" : region.describe();
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
