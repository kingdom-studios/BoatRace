package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.race.RaceManager;

/**
 * /bre ranked <arena|random> [player]   join a competitive lobby (party leader = the whole party as one team)
 * /bre ranked leave                     leave the lobby
 * /bre ranked list                      lobbies with players waiting
 */
final class RankedSub extends RaceSubCommand {

	private static final String OTHERS_PERMISSION = "boatrace.join.others";

	RankedSub(final SimpleCommandGroup group) {
		super(group, "ranked|competitive|comp", "Competitive team race (party = team, solo = team of 1).", "<arena|random|leave|list> [player]", 1, false);
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] {
				"/{label} ranked random  &7- competitive match on any map (party leader: the whole party as one team)",
				"/{label} ranked <arena> &7- competitive match on that map",
				"/{label} ranked leave   &7- leave the lobby",
				"/{label} ranked list    &7- lobbies with players waiting"
		};
	}

	@Override
	protected void onCommand() {
		final RaceManager races = this.plugin().getRaces();
		final String action = this.args[0].toLowerCase();

		if ("leave".equals(action)) {
			if (races.leaveCompetitive(this.requirePlayer()))
				this.info("comp.queue-left");
			else
				this.fail("comp.not-queued");

			return;
		}

		if ("list".equals(action)) {
			final List<RaceManager.CompLobbyInfo> lobbies = races.competitiveLobbies();

			if (lobbies.isEmpty())
				this.fail("comp.list-empty");

			this.info("comp.list-header");

			for (final RaceManager.CompLobbyInfo lobby : lobbies)
				this.info("comp.list-line", "arena", lobby.arena(), "players", lobby.players(), "teams", lobby.teams(), "min", lobby.min(), "max", lobby.max(),
						"countdown", lobby.countdown() >= 0 ? lobby.countdown() + "s" : "-");

			return;
		}

		final Arena arena = "random".equals(action) ? null : this.requireArena(0);
		final Player target;

		if (this.args.length > 1) {
			if (!this.getSender().hasPermission(OTHERS_PERMISSION)) {
				this.fail("command.no-permission");
				return;
			}

			target = Bukkit.getPlayerExact(this.args[1]);

			if (target == null) {
				this.fail("command.player-not-found", "player", this.args[1]);
				return;
			}

		} else
			target = this.requirePlayer();

		races.joinCompetitive(target, arena);
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1) {
			final List<String> options = new ArrayList<>(List.of("random", "leave", "list"));

			options.addAll(this.arenaNames());

			return this.completeLastWord(options);
		}

		if (this.args.length == 2 && this.getSender().hasPermission(OTHERS_PERMISSION))
			return this.completeLastWordPlayerNames();

		return new ArrayList<>();
	}
}
