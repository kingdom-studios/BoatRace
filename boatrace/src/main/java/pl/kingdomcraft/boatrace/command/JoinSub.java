package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.gui.GuiManager;
import pl.kingdomcraft.boatrace.race.RaceManager.JoinResult;

/**
 * /bre join                        random ready arena (yourself)
 * /bre join random [player]        random ready arena
 * /bre join <arena> [player]       a specific arena
 *
 * Sending another player needs boatrace.join.others (works from the console, NPCs, command blocks...).
 * A successful join is completely silent - the player just appears in a boat on the grid. A busy arena puts the
 * player (or the party, when a party leader joins) in the queue. With map-permissions.enforce-on-join-command the
 * permission boatrace.map.<arena> is required for joining yourself, like in the map menu.
 */
final class JoinSub extends RaceSubCommand {

	static final String OTHERS_PERMISSION = "boatrace.join.others";

	JoinSub(final SimpleCommandGroup group) {
		super(group, "join", "Join a track: random or a named arena, optionally for another player.", "[random|arena] [player]", 0, false);
	}

	@Override
	protected void onCommand() {
		final Player target;

		if (this.args.length >= 2) {
			if (!this.getSender().hasPermission(OTHERS_PERMISSION))
				this.fail("command.join-others-no-permission");

			target = Bukkit.getPlayerExact(this.args[1]);

			if (target == null)
				this.fail("command.player-not-found", "player", this.args[1]);

		} else {
			if (!(this.getSender() instanceof Player))
				this.fail("command.join-console-needs-player");

			target = (Player) this.getSender();
		}

		final Arena arena;
		final boolean checkPermission = this.plugin().getSettings().enforceMapPermissionOnCommand && this.getSender() == target;

		if (this.args.length == 0 || "random".equalsIgnoreCase(this.args[0])) {
			// open arenas only; a party leader gets only arenas with a grid position for every member
			arena = this.plugin().getRaces().pickRandomReadyArena(target, candidate -> !checkPermission || target.hasPermission(GuiManager.permission(candidate)));

			if (arena == null) {
				if (this.plugin().getRaces().groupSize(target) > 1)
					this.fail("party.no-map-fits", "size", this.plugin().getRaces().groupSize(target));
				else
					this.fail("command.no-ready-arena");

				return;
			}

		} else {
			arena = this.requireArena(0);

			if (checkPermission && !target.hasPermission(GuiManager.permission(arena)))
				this.fail("gui.no-permission", "arena", arena.getName(), "permission", GuiManager.permission(arena));
		}

		final JoinResult result = this.plugin().getRaces().join(target, arena);

		// The racer was told why it failed; tell whoever sent them too (console, admin)
		if (result != JoinResult.JOINED && this.getSender() != target)
			switch (result) {
				case BANNED -> this.info("race.banned");
				case NOT_READY -> this.info("race.not-ready", "arena", arena.getName(), "missing", arena.missing());
				case MAINTENANCE -> this.info("race.maintenance", "arena", arena.getName());
				case BUSY -> this.info("race.all-busy", "arena", arena.getName());
				case QUEUED, PARTY_BLOCKED -> {
					// the players concerned were already told (queue position / which party member is busy)
				}
				default -> this.info("race.inventory-save-failed");
			}
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1) {
			final List<String> options = new ArrayList<>();

			options.add("random");

			for (final Arena arena : this.plugin().getArenas().readyArenas())
				options.add(arena.getName());

			return this.completeLastWord(options);
		}

		if (this.args.length == 2 && this.getSender().hasPermission(OTHERS_PERMISSION))
			return this.completeLastWordPlayerNames();

		return new ArrayList<>();
	}
}
