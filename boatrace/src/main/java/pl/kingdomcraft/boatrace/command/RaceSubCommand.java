package pl.kingdomcraft.boatrace.command;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;
import org.mineacademy.fo.command.SimpleSubCommand;

import pl.kingdomcraft.boatrace.BoatRacePlugin;
import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.util.Msg;

/** Shared helpers of all /br sub commands. */
abstract class RaceSubCommand extends SimpleSubCommand {

	static final String ADMIN_PERMISSION = "boatrace.admin";

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
			this.returnTell("&cTa komenda jest dostępna tylko dla graczy.");

		return (Player) this.getSender();
	}

	/** Returns the arena named by the argument at {@code index} or stops the command with a message. */
	protected final Arena requireArena(final int index) {
		final Arena arena = this.plugin().getArenas().get(this.args[index]);

		if (arena == null)
			this.returnTell("&cNie ma areny o nazwie &f" + this.args[index] + "&c. Lista: &f/br list");

		return arena;
	}

	protected final void info(final String message) {
		Msg.send(this.getSender(), message);
	}

	/**
	 * The block the player looks at (max {@code range} blocks) as a point, or - when looking at nothing - their own
	 * standing location. Used for checkpoints and the finish.
	 */
	protected static Location targetOrStanding(final Player player, final int range) {
		final Block target = player.getTargetBlockExact(range);

		if (target != null)
			return target.getLocation().add(0.5D, 0.5D, 0.5D);

		return player.getLocation();
	}

	protected final List<String> arenaNames() {
		return this.plugin().getArenas().names();
	}
}
