package pl.kingdomcraft.boatrace.spectate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * /bre spectate: puts an admin into spectator mode attached to a racer. The admin's game mode and location are
 * remembered and restored by /bre spectate off, when they join a race themselves, or when they leave the server.
 */
public final class SpectateManager implements Listener {

	private record Previous(GameMode mode, Location location) {
	}

	private final JavaPlugin plugin;
	private final RaceManager races;
	private final Map<UUID, Previous> spectators = new HashMap<>();

	public SpectateManager(final JavaPlugin plugin, final RaceManager races) {
		this.plugin = plugin;
		this.races = races;
	}

	public boolean isSpectating(final Player player) {
		return this.spectators.containsKey(player.getUniqueId());
	}

	/**
	 * Teleports the admin to the racer and attaches the camera to them. Sneaking releases the camera (the admin can
	 * fly around), /bre spectate off brings them back.
	 *
	 * @return false when the admin cannot spectate right now (they are racing themselves)
	 */
	public boolean start(final Player admin, final Player target) {
		if (this.races.isRacing(admin)) {
			Msg.send(admin, "spectate.you-racing");
			return false;
		}

		this.spectators.putIfAbsent(admin.getUniqueId(), new Previous(admin.getGameMode(), admin.getLocation()));

		admin.setGameMode(GameMode.SPECTATOR);
		admin.teleport(target.getLocation());

		// the camera can only be attached after the game mode change and the teleport were processed
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			if (admin.isOnline() && target.isOnline() && this.spectators.containsKey(admin.getUniqueId()))
				admin.setSpectatorTarget(target);
		});

		return true;
	}

	/**
	 * Gives the admin their game mode and location back.
	 *
	 * @param silent no "stopped" message
	 * @return false when the player was not spectating
	 */
	public boolean stop(final Player player, final boolean silent) {
		final Previous previous = this.spectators.remove(player.getUniqueId());

		if (previous == null)
			return false;

		player.setSpectatorTarget(null);
		player.setGameMode(previous.mode());

		if (previous.location().getWorld() != null)
			player.teleport(previous.location());

		if (!silent)
			Msg.send(player, "spectate.stopped");

		return true;
	}

	/** Plugin disable / server stop. */
	public void shutdown() {
		for (final UUID uuid : new ArrayList<>(this.spectators.keySet())) {
			final Player player = Bukkit.getPlayer(uuid);

			if (player != null)
				this.stop(player, true);
		}

		this.spectators.clear();
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		this.stop(event.getPlayer(), true);
	}
}
