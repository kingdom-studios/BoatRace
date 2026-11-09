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

	protected final BoatRacePlugin plugin() {
		return BoatRacePlugin.getInstance();
	}

	/** Returns the sender as a player or stops the command with a message. */
	protected final Player requirePlayer() {
		if (!(this.getSender() instanceof Player))
			this.returnTell("&cThis command can only be used by players.");

		return (Player) this.getSender();
	}

	/** Returns the arena named by the argument at {@code index} or stops the command with a message. */
	protected final Arena requireArena(final int index) {
		final Arena arena = this.plugin().getArenas().get(this.args[index]);

		if (arena == null)
			this.returnTell("&cArena &f" + this.args[index] + " &cdoes not exist. See &f/bre list");

		return arena;
	}

	/** Returns the player's wand selection (pos1 + pos2) or stops the command with a message. */
	protected final Region requireSelection(final Player player) {
		final Region region = this.plugin().getSelections().getRegion(player);

		if (region == null)
			this.returnTell("&cSelect an area first: &f/bre wand &c(left click = pos1, right click = pos2) or &f/bre pos1&c / &f/bre pos2&c.");

		if (region.volume() > MAX_ZONE_VOLUME)
			this.returnTell("&cThe selection is too large (" + region.volume() + " blocks, max " + MAX_ZONE_VOLUME + ").");

		return region;
	}

	/** Warns when the spawn lies inside the start line (the boat must spawn BEFORE the line). */
	protected final void warnSpawnPlacement(final Arena arena) {
		if (arena.getSpawn() == null || arena.getStartLine() == null)
			return;

		final org.bukkit.Location spawn = arena.getSpawn().toLocation();

		if (spawn != null && arena.getStartLine().contains(spawn))
			this.info("&eWarning: the spawn is INSIDE the start line. Move the spawn a few blocks in front of the line so the start is triggered by driving through it.");
	}

	protected final void info(final String message) {
		Msg.send(this.getSender(), message);
	}

	protected final List<String> arenaNames() {
		return this.plugin().getArenas().names();
	}
}
