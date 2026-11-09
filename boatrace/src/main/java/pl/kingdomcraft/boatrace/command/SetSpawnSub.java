package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Point;

final class SetSpawnSub extends RaceSubCommand {

	SetSpawnSub(final SimpleCommandGroup group) {
		super(group, "setspawn", "Ustaw punkt startu/odrodzenia (Twoja pozycja i kierunek patrzenia).", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);

		arena.setSpawn(Point.of(player.getLocation()));
		this.plugin().getArenas().save();

		this.info("&aUstawiono spawn areny &f" + arena.getName() + "&a. Łódka będzie ustawiona w tym kierunku.");
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
