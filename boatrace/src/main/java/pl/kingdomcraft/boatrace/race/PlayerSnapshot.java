package pl.kingdomcraft.boatrace.race;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Saves a player's inventory / game mode / location to disk (plugins/BoatRace/snapshots/UUID.yml) before the race
 * kit is handed out, and restores it afterwards. Because it is on disk, nothing is lost if the server crashes
 * mid-race - the snapshot is restored the next time the player joins.
 */
final class PlayerSnapshot {

	private PlayerSnapshot() {
	}

	private static File file(final JavaPlugin plugin, final UUID uuid) {
		return new File(new File(plugin.getDataFolder(), "snapshots"), uuid + ".yml");
	}

	/** @return false when the snapshot could not be written (the caller must then NOT clear the inventory) */
	static boolean save(final JavaPlugin plugin, final Player player) {
		final File file = file(plugin, player.getUniqueId());

		file.getParentFile().mkdirs();

		final YamlConfiguration yaml = new YamlConfiguration();

		yaml.set("gamemode", player.getGameMode().name());
		yaml.set("origin", player.getLocation());
		yaml.set("contents", new ArrayList<>(Arrays.asList(player.getInventory().getContents())));

		try {
			yaml.save(file);
			return true;

		} catch (final IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not save inventory snapshot of " + player.getName(), ex);
			return false;
		}
	}

	/**
	 * Restores the saved state if a snapshot exists and deletes it.
	 *
	 * @return the location the player came from, or null when there was no snapshot
	 */
	static Location restore(final JavaPlugin plugin, final Player player) {
		final File file = file(plugin, player.getUniqueId());

		if (!file.exists())
			return null;

		final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		final PlayerInventory inventory = player.getInventory();

		final List<?> raw = yaml.getList("contents");

		if (raw != null) {
			final ItemStack[] contents = new ItemStack[inventory.getSize()];

			for (int i = 0; i < raw.size() && i < contents.length; i++) {
				final Object item = raw.get(i);

				contents[i] = item instanceof ItemStack ? (ItemStack) item : null;
			}

			inventory.setContents(contents);
		}

		try {
			player.setGameMode(GameMode.valueOf(yaml.getString("gamemode", "SURVIVAL")));
		} catch (final IllegalArgumentException ex) {
			player.setGameMode(GameMode.SURVIVAL);
		}

		final Location origin = yaml.getLocation("origin");

		if (!file.delete())
			plugin.getLogger().warning("Could not delete snapshot file " + file.getName());

		return origin == null ? player.getWorld().getSpawnLocation() : origin;
	}
}
