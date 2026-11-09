package pl.kingdomcraft.boatrace.arena;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads / saves arenas.yml. All access happens on the main thread. */
public final class ArenaManager {

	private final JavaPlugin plugin;
	private final File file;
	private final Map<String, Arena> arenas = new LinkedHashMap<>();

	public ArenaManager(final JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "arenas.yml");
	}

	private static String key(final String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public Arena get(final String name) {
		return name == null ? null : this.arenas.get(key(name));
	}

	public boolean exists(final String name) {
		return this.get(name) != null;
	}

	public Arena create(final String name) {
		final Arena arena = new Arena(name);

		this.arenas.put(key(name), arena);
		this.save();

		return arena;
	}

	public Collection<Arena> all() {
		return this.arenas.values();
	}

	public List<String> names() {
		final List<String> names = new ArrayList<>();

		for (final Arena arena : this.arenas.values())
			names.add(arena.getName());

		return names;
	}

	public List<Arena> readyArenas() {
		final List<Arena> ready = new ArrayList<>();

		for (final Arena arena : this.arenas.values())
			if (arena.isReady())
				ready.add(arena);

		return ready;
	}

	public void load() {
		this.arenas.clear();

		if (!this.file.exists())
			return;

		final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
		final ConfigurationSection root = yaml.getConfigurationSection("arenas");

		if (root == null)
			return;

		for (final String name : root.getKeys(false)) {
			final ConfigurationSection section = root.getConfigurationSection(name);

			if (section == null)
				continue;

			final Arena arena = new Arena(name);

			arena.setSpawn(Point.load(section.getConfigurationSection("spawn")));
			arena.setStartBlock(Point.load(section.getConfigurationSection("start-block")));
			arena.setLightsAlongX(section.getBoolean("lights-along-x", true));
			arena.setFinish(Point.load(section.getConfigurationSection("finish")));

			final ConfigurationSection cps = section.getConfigurationSection("checkpoints");

			if (cps != null) {
				final List<Integer> order = new ArrayList<>();

				for (final String index : cps.getKeys(false))
					try {
						order.add(Integer.parseInt(index));
					} catch (final NumberFormatException ignored) {
					}

				order.sort(Integer::compare);

				for (final Integer index : order) {
					final Point point = Point.load(cps.getConfigurationSection(String.valueOf(index)));

					if (point != null)
						arena.getCheckpoints().add(point);
				}
			}

			this.arenas.put(key(name), arena);
		}
	}

	public void save() {
		final YamlConfiguration yaml = new YamlConfiguration();

		for (final Arena arena : this.arenas.values()) {
			final String base = "arenas." + arena.getName();

			// Creates the section even for brand-new, empty arenas
			yaml.createSection(base);

			if (arena.getSpawn() != null)
				arena.getSpawn().save(yaml.createSection(base + ".spawn"));

			if (arena.getStartBlock() != null)
				arena.getStartBlock().save(yaml.createSection(base + ".start-block"));

			yaml.set(base + ".lights-along-x", arena.isLightsAlongX());

			if (arena.getFinish() != null)
				arena.getFinish().save(yaml.createSection(base + ".finish"));

			int index = 0;

			for (final Point checkpoint : arena.getCheckpoints())
				checkpoint.save(yaml.createSection(base + ".checkpoints." + index++));
		}

		try {
			this.plugin.getDataFolder().mkdirs();
			yaml.save(this.file);

		} catch (final IOException ex) {
			this.plugin.getLogger().log(Level.SEVERE, "Could not save arenas.yml", ex);
		}
	}
}
