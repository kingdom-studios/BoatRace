package pl.kingdomcraft.boatrace.command;

import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;
import org.mineacademy.fo.command.SimpleSubCommand;

import pl.kingdomcraft.boatrace.BoatRacePlugin;
import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;
import pl.kingdomcraft.boatrace.util.Msg;

/** Shared helpers of all /bre sub commands. */
abstract class RaceSubCommand extends SimpleSubCommand {

	static final String ADMIN_PERMISSION = "boatrace.admin";

	/** Upper limit for one selection, so a typo in pos2 cannot create a zone of millions of blocks. */
	static final long MAX_ZONE_VOLUME = 200_000L;

	protected RaceSubCommand(final SimpleCommandGroup group, final String sublabel, final String description, final String usage, final int minArgs, final boolean admin) {
		super(group, sublabel);

		this.setDescription(description);

		if (usage != null)
			this.setUsage(usage);

		this.setMinArguments(minArgs);
		this.setPermission(admin ? ADMIN_PERMISSION : null);
	}

	final BoatRacePlugin plugin() {
		return BoatRacePlugin.getInstance();
	}

	/** Returns the sender as a player or stops the command with a message. */
	protected final Player requirePlayer() {
		if (!(this.getSender() instanceof Player))
			this.returnTell(Msg.text("command.only-players"));

		return (Player) this.getSender();
	}

	/** Returns the arena named by the argument at {@code index} or stops the command with a message. */
	protected final Arena requireArena(final int index) {
		final Arena arena = this.plugin().getArenas().get(this.args[index]);

		if (arena == null)
			this.returnTell(Msg.text("command.arena-not-found", "arena", this.args[index]));

		return arena;
	}

	/** Returns the player's wand selection (pos1 + pos2) or stops the command with a message. */
	protected final Region requireSelection(final Player player) {
		return this.requireSelection(player, MAX_ZONE_VOLUME);
	}

	/** Same with another size limit (reset zones are checked as one box, so they may be huge). */
	protected final Region requireSelection(final Player player, final long maxVolume) {
		final Region region = this.plugin().getSelections().getRegion(player);

		if (region == null)
			this.returnTell(Msg.text("command.selection-required"));

		if (region.volume() > maxVolume)
			this.returnTell(Msg.text("command.selection-too-large", "volume", region.volume(), "max", maxVolume));

		return region;
	}

	/** Warns when a grid position lies inside the start line (boats must spawn BEFORE the line). */
	protected final void warnSpawnPlacement(final Arena arena) {
		if (arena.getStartLine() == null)
			return;

		for (final pl.kingdomcraft.boatrace.arena.Point point : arena.getSpawns().values()) {
			final org.bukkit.Location spawn = point.toLocation();

			if (spawn != null && arena.getStartLine().contains(spawn)) {
				this.info("command.spawn-inside-start");
				return;
			}
		}
	}

	/** Parses a grid position (1, 2, 3 ... no upper limit) or stops the command. */
	protected final int gridIndex(final String raw) {
		try {
			final int index = Integer.parseInt(raw);

			if (index >= 1)
				return index;

		} catch (final NumberFormatException ignored) {
		}

		this.fail("arena.spawn-invalid-index", "value", raw);
		return 1;
	}

	/** Parses an optional lap count at {@code index} (1 - 999), null when the argument is missing. */
	protected final Integer optionalLaps(final int index) {
		if (this.args.length <= index)
			return null;

		try {
			final int laps = Integer.parseInt(this.args[index]);

			if (laps >= 1 && laps <= 999)
				return laps;

		} catch (final NumberFormatException ignored) {
		}

		this.fail("arena.invalid-laps", "value", this.args[index]);
		return null;
	}

	/** Sends the messages.yml entry {@code key} (with the prefix) to the command sender. */
	final void info(final String key, final Object... placeholders) {
		Msg.send(this.getSender(), key, placeholders);
	}

	/** Stops the command with the messages.yml entry {@code key}. */
	protected final void fail(final String key, final Object... placeholders) {
		this.returnTell(Msg.text(key, placeholders));
	}

	protected final List<String> arenaNames() {
		return this.plugin().getArenas().names();
	}
}
