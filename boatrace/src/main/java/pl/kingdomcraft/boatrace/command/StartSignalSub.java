package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

/** /bre startsignal <arena> set|clear - blocks that switch to start-signal.go-block at lights out. */
final class StartSignalSub extends RaceSubCommand {

	StartSignalSub(final SimpleCommandGroup group) {
		super(group, "startsignal", "Blocks (wand selection) that turn green at lights out and back after a few seconds.", "<arena> <set|clear>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		switch (this.args[1].toLowerCase()) {
			case "set" -> {
				final Player player = this.requirePlayer();
				final Region region = this.requireSelection(player);

				arena.setStartSignal(region);
				this.plugin().getArenas().save();

				this.info("arena.signal-set", "arena", arena.getName(), "region", region.describe());
			}
			case "clear" -> {
				arena.setStartSignal(null);
				this.plugin().getArenas().save();

				this.info("arena.signal-cleared", "arena", arena.getName());
			}
			default -> this.returnInvalidArgs();
		}
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("set", "clear") : new ArrayList<>();
	}
}
