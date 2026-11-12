package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Point;
import pl.kingdomcraft.boatrace.arena.Region;

/**
 * /bre addresetzone <arena> - adds the wand selection as a reset zone (void / kill zone): a racer who touches it during
 * a race is put back on the start (see RaceManager#resetByZone).
 */
final class AddResetZoneSub extends RaceSubCommand {

	/** Zones are checked as one box (no block list), so even a huge void slab is fine. */
	private static final long MAX_RESET_ZONE_VOLUME = 500_000_000L;

	AddResetZoneSub(final SimpleCommandGroup group) {
		super(group, "addresetzone|addkillzone", "Add your wand selection as a reset zone (touching it during a race resets the racer).", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final Arena arena = this.requireArena(0);
		final Region region = this.requireSelection(player, MAX_RESET_ZONE_VOLUME);

		arena.getResetZones().add(region);
		this.plugin().getArenas().save();

		this.info("arena.reset-zone-added", "arena", arena.getName(), "index", arena.getResetZones().size(),
				"count", arena.getResetZones().size(), "region", region.describe());

		// a grid position inside a reset zone would reset the racer again and again
		for (final Point spawn : arena.getSpawns().values()) {
			final Location location = spawn.toLocation();

			if (location != null && region.containsExact(location)) {
				this.info("arena.reset-zone-spawn-inside", "arena", arena.getName());
				break;
			}
		}
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
