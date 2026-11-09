package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;

final class SetFinishSub extends RaceSubCommand {

	SetFinishSub(final SimpleCommandGroup group) {
		super(group, "setfinish|setfinishline", "Set a separate finish line to your wand selection ('clear' = use the start line).", "<arena> [clear]", 1, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		if (this.args.length > 1 && "clear".equalsIgnoreCase(this.args[1])) {
			arena.setFinishLine(null);
			this.plugin().getArenas().save();

			this.info("&aArena &f" + arena.getName() + " &anow uses the start line as the finish line (circuit).");
			return;
		}

		final Player player = this.requirePlayer();
		final Region region = this.requireSelection(player);

		arena.setFinishLine(region);
		this.plugin().getArenas().save();

		this.info("&aFinish line of &f" + arena.getName() + " &aset: &7" + region.describe());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("clear") : new ArrayList<>();
	}
}
