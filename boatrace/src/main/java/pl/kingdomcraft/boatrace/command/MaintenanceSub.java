package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/**
 * /bre maintenance <arena> <true|false> - closes (true) or opens (false) a map for players: it is shown disabled in the
 * menu, never picked by "random", and joining it shows race.maintenance. Races that are already running finish
 * normally, queues for the map are cancelled, admins (boatrace.admin) can still join it with the command.
 */
final class MaintenanceSub extends RaceSubCommand {

	MaintenanceSub(final SimpleCommandGroup group) {
		super(group, "maintenance", "Close (true) or open (false) a map for players.", "<arena> <true|false>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		switch (this.args[1].toLowerCase(Locale.ROOT)) {
			case "true", "on", "yes", "1" -> arena.setMaintenance(true);
			case "false", "off", "no", "0" -> arena.setMaintenance(false);
			default -> {
				this.fail("command.invalid-boolean", "value", this.args[1]);
				return;
			}
		}

		this.plugin().getArenas().save();
		this.info(arena.isMaintenance() ? "arena.maintenance-on" : "arena.maintenance-off", "arena", arena.getName());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("true", "false") : new ArrayList<>();
	}
}
