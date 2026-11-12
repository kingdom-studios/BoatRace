package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/**
 * /bre compset <arena> on|off            competitive matches on this arena (default: on)
 * /bre compset <arena> min|max <number>  racers needed for the countdown / most racers of a match
 * /bre compset <arena> reset             back to the config.yml defaults (max = grid positions)
 * /bre compset <arena> info
 */
final class CompSetSub extends RaceSubCommand {

	CompSetSub(final SimpleCommandGroup group) {
		super(group, "compset", "Competitive settings of an arena (on/off, min / max racers).", "<arena> <on|off|min|max|reset|info> [number]", 2, true);
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] {
				"/{label} compset <arena> on|off    &7- competitive matches on this map",
				"/{label} compset <arena> min <n>   &7- racers needed before the countdown starts",
				"/{label} compset <arena> max <n>   &7- most racers of a match (never more than the grid positions)",
				"/{label} compset <arena> reset     &7- config.yml defaults (max = grid positions)",
				"/{label} compset <arena> info"
		};
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final String action = this.args[1].toLowerCase();

		switch (action) {
			case "on", "off" -> arena.setCompetitive("on".equals(action), arena.getCompetitiveMinRaw(), arena.getCompetitiveMaxRaw());

			case "min", "max" -> {
				if (this.args.length < 3)
					this.returnInvalidArgs();

				final int value;

				try {
					value = Integer.parseInt(this.args[2]);

				} catch (final NumberFormatException ex) {
					this.fail("comp.set-not-a-number", "value", this.args[2]);
					return;
				}

				if (value < 2) {
					this.fail("comp.set-too-small");
					return;
				}

				if ("min".equals(action))
					arena.setCompetitive(arena.getCompetitiveEnabledRaw(), value, arena.getCompetitiveMaxRaw());
				else
					arena.setCompetitive(arena.getCompetitiveEnabledRaw(), arena.getCompetitiveMinRaw(), value);
			}

			case "reset" -> arena.setCompetitive(null, null, null);

			case "info" -> {
			}

			default -> {
				this.returnInvalidArgs();
				return;
			}
		}

		if (!"info".equals(action))
			this.plugin().getArenas().save();

		this.info("comp.set-info", "arena", arena.getName(), "enabled", arena.isCompetitive() ? "&aon" : "&coff",
				"min", arena.competitiveMin(this.plugin().getSettings().compMinPlayers), "max", arena.competitiveMax(), "spawns", arena.getSpawns().size());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2)
			return this.completeLastWord("on", "off", "min", "max", "reset", "info");

		return new ArrayList<>();
	}
}
