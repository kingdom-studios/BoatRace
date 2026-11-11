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

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads / saves arenas.yml. All access happens on the main thread. */
public final class ArenaManager {

	/** Old (point based) checkpoints / finish are migrated into cubes of this radius. */
	private static final int LEGACY_RADIUS = 3;

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

			// grid positions; "spawn" is the single spawn of older versions = position #1
			final ConfigurationSection spawns = section.getConfigurationSection("spawns");

			if (spawns != null)
				for (final String index : spawns.getKeys(false))
					try {
						final Point point = Point.load(spawns.getConfigurationSection(index));

						if (point != null)
							arena.getSpawns().put(Integer.parseInt(index), point);

					} catch (final NumberFormatException ignored) {
					}

			final Point legacySpawn = Point.load(section.getConfigurationSection("spawn"));

			if (legacySpawn != null)
				arena.getSpawns().putIfAbsent(1, legacySpawn);

			// start-line.1, start-line.2 ... = segments; older versions stored a single region directly under start-line
			final ConfigurationSection startLine = section.getConfigurationSection("start-line");

			if (startLine != null && startLine.isString("world"))
				arena.setStartLine(Region.load(startLine, LEGACY_RADIUS));
			else
				arena.getStartSegments().addAll(loadList(startLine));

			arena.setFinishLine(Region.load(section.getConfigurationSection("finish"), LEGACY_RADIUS));

			arena.getCheckpoints().addAll(loadList(section.getConfigurationSection("checkpoints")));
			arena.getLights().addAll(loadList(section.getConfigurationSection("lights")));

			for (final String raw : section.getStringList("track-blocks")) {
				final Material material = Material.matchMaterial(raw);

				if (material != null && material.isBlock())
					arena.getTrackBlocks().add(material);
				else
					this.plugin.getLogger().warning("Arena " + name + ": unknown track block '" + raw + "' ignored.");
			}

			arena.setLaps(section.getInt("laps", 1));
			arena.setMaintenance(section.getBoolean("maintenance", false));
			arena.setRewardMode(Arena.RewardMode.parse(section.getString("reward.mode", "NONE")));
			arena.getRewardCommands().addAll(section.getStringList("reward.commands"));

			this.arenas.put(key(name), arena);
		}
	}

	private static List<Region> loadList(final ConfigurationSection section) {
		final List<Region> result = new ArrayList<>();

		if (section == null)
			return result;

		final List<Integer> order = new ArrayList<>();

		for (final String index : section.getKeys(false))
			try {
				order.add(Integer.parseInt(index));
			} catch (final NumberFormatException ignored) {
			}

		order.sort(Integer::compare);

		for (final Integer index : order) {
			final Region region = Region.load(section.getConfigurationSection(String.valueOf(index)), LEGACY_RADIUS);

			if (region != null)
				result.add(region);
		}

		return result;
	}

	public void save() {
		final YamlConfiguration yaml = new YamlConfiguration();

		for (final Arena arena : this.arenas.values()) {
			final String base = "arenas." + arena.getName();

			// Creates the section even for brand-new, empty arenas
			yaml.createSection(base);

			for (final Map.Entry<Integer, Point> spawn : arena.getSpawns().entrySet())
				spawn.getValue().save(yaml.createSection(base + ".spawns." + spawn.getKey()));

			int segment = 1;

			for (final Region region : arena.getStartSegments())
				region.save(yaml.createSection(base + ".start-line." + segment++));

			if (arena.getFinishLineRaw() != null)
				arena.getFinishLineRaw().save(yaml.createSection(base + ".finish"));

			int index = 0;

			for (final Region checkpoint : arena.getCheckpoints())
				checkpoint.save(yaml.createSection(base + ".checkpoints." + index++));

			index = 0;

			for (final Region lamp : arena.getLights())
				lamp.save(yaml.createSection(base + ".lights." + index++));

			final List<String> trackBlocks = new ArrayList<>();

			for (final Material material : arena.getTrackBlocks())
				trackBlocks.add(material.name());

			yaml.set(base + ".track-blocks", trackBlocks);

			yaml.set(base + ".laps", arena.getLaps());
			yaml.set(base + ".maintenance", arena.isMaintenance());
			yaml.set(base + ".reward.mode", arena.getRewardMode().name());
			yaml.set(base + ".reward.commands", new ArrayList<>(arena.getRewardCommands()));
		}

		try {
			this.plugin.getDataFolder().mkdirs();
			yaml.save(this.file);

		} catch (final IOException ex) {
			this.plugin.getLogger().log(Level.SEVERE, "Could not save arenas.yml", ex);
		}
	}
}
