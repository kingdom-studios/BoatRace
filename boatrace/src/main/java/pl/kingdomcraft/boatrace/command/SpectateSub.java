package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.spectate.SpectateManager;

/** /bre spectate <player|arena> - spectator mode attached to a racer; /bre spectate off returns. */
final class SpectateSub extends RaceSubCommand {

	SpectateSub(final SimpleCommandGroup group) {
		super(group, "spectate|spec", "Spectate a racing player or the race on a map ('off' = stop).", "<player|arena|off>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player admin = this.requirePlayer();
		final SpectateManager spectate = this.plugin().getSpectate();
		final String argument = this.args[0];

		if ("off".equalsIgnoreCase(argument) || "stop".equalsIgnoreCase(argument)) {
			if (!spectate.stop(admin, false))
				this.fail("spectate.not-spectating");

			return;
		}

		final Player byName = Bukkit.getPlayerExact(argument);

		if (byName != null) {
			if (!this.plugin().getRaces().isRacing(byName)) {
				this.fail("spectate.not-racing", "player", byName.getName());
				return;
			}

			if (spectate.start(admin, byName))
				this.info("spectate.started", "player", byName.getName());

			return;
		}

		final Arena arena = this.plugin().getArenas().get(argument);

		if (arena == null) {
			this.fail("spectate.unknown", "value", argument);
			return;
		}

		final Player racer = this.plugin().getRaces().racerOnArena(arena);

		if (racer == null) {
			this.fail("spectate.nobody", "arena", arena.getName());
			return;
		}

		if (spectate.start(admin, racer))
			this.info("spectate.started-arena", "player", racer.getName(), "arena", arena.getName());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length != 1)
			return new ArrayList<>();

		final List<String> options = new ArrayList<>(this.plugin().getRaces().racerNames());

		options.addAll(this.arenaNames());
		options.add("off");

		return this.completeLastWord(options);
	}
}
