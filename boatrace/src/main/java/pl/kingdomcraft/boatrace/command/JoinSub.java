package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

final class JoinSub extends RaceSubCommand {

	JoinSub(final SimpleCommandGroup group) {
		super(group, "join", "Dołącz do toru (bez nazwy: losowa arena).", "[arena]", 0, false);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena;

		if (this.args.length > 0)
			arena = this.requireArena(0);

		else {
			arena = this.plugin().getRaces().pickRandomReadyArena();

			if (arena == null)
				this.returnTell("&cNie ma żadnej aktywnej areny.");
		}

		this.plugin().getRaces().join(player, arena);
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
