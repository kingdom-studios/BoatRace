package pl.kingdomcraft.boatrace.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Brings an existing config file up to date after a plugin update: every key of the default file in the jar that the
 * server's file does not have yet is added - with its default value and its comments - inside the section it belongs
 * to. Nothing the server owner set is ever changed or removed; keys that are no longer used simply stay.
 * <p>
 * "Collections" (sections whose children are the owner's own entries, e.g. the colors or the map icons) are only added
 * as a whole when they are missing completely, never filled up with the example entries of the jar.
 * <p>
 * The file is only written when something was added, after a copy of the old file was saved to
 * {@code backups/<file>.<date>.bak}.
 */
public final class ConfigUpdater {

	private static final DateTimeFormatter BACKUP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private ConfigUpdater() {
	}

	/** config.yml, messages.yml and guis.yml of BoatRace, each with its own collections. */
	public static void updateAll(final JavaPlugin plugin) {
		update(plugin, "config.yml", Set.of("fallback.maps", "ranking.xp-multipliers", "competitive.colors"));
		update(plugin, "messages.yml", Set.of());
		// "menus" belongs to the owner as a whole (their own menus / items); the global settings are kept up to date
		update(plugin, "guis.yml", Set.of("menus"));
	}

	/**
	 * @param collections paths of sections that belong to the owner as a whole (see the class description)
	 * @return the added paths (empty when the file is up to date, missing or could not be read)
	 */
	public static List<String> update(final JavaPlugin plugin, final String name, final Set<String> collections) {
		final File file = new File(plugin.getDataFolder(), name);

		// a missing file is created from the jar by its loader - complete anyway
		if (!file.exists())
			return List.of();

		final YamlConfiguration defaults = new YamlConfiguration();

		try (InputStream in = plugin.getResource(name)) {
			if (in == null)
				return List.of();

			defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));

		} catch (final IOException | InvalidConfigurationException ex) {
			plugin.getLogger().log(Level.WARNING, "Could not read the default " + name + " of the jar - " + name + " is not updated.", ex);
			return List.of();
		}

		final YamlConfiguration user = new YamlConfiguration();

		try {
			user.load(file);

		} catch (final IOException | InvalidConfigurationException ex) {
			// never touch a file we cannot read: the owner has to fix it first
			plugin.getLogger().warning(name + " has a YAML error, missing keys are NOT added until it is fixed: " + ex.getMessage());
			return List.of();
		}

		final List<String> added = merge(user, defaults, collections);

		if (added.isEmpty())
			return added;

		try {
			final File backups = new File(plugin.getDataFolder(), "backups");

			Files.createDirectories(backups.toPath());

			final File backup = new File(backups, name + "." + LocalDateTime.now().format(BACKUP_DATE) + ".bak");

			Files.copy(file.toPath(), backup.toPath());

			// long texts (messages) stay on one line
			user.options().width(Integer.MAX_VALUE);
			user.save(file);

			plugin.getLogger().info(name + ": added " + added.size() + " new setting(s) with their default values (your values are unchanged, backup: backups/"
					+ backup.getName() + "): " + String.join(", ", added));

		} catch (final IOException ex) {
			plugin.getLogger().log(Level.WARNING, "Could not save the updated " + name + " - the new settings use their defaults until the next start.", ex);
		}

		return added;
	}

	/**
	 * Adds every key of {@code defaults} that {@code user} does not have (with its comments) and returns their paths.
	 * Values of {@code user} are never changed. Plain logic, no file access.
	 */
	public static List<String> merge(final YamlConfiguration user, final YamlConfiguration defaults, final Set<String> collections) {
		final List<String> added = new ArrayList<>();

		merge(user, defaults, defaults, collections, added);

		return added;
	}

	private static void merge(final YamlConfiguration user, final YamlConfiguration defaults, final ConfigurationSection section, final Set<String> collections,
			final List<String> added) {
		for (final String key : section.getKeys(false)) {
			final String path = section.getCurrentPath() == null || section.getCurrentPath().isEmpty() ? key : section.getCurrentPath() + "." + key;

			if (!user.contains(path, true)) {
				copy(user, defaults, path);
				added.add(path);

			} else if (defaults.isConfigurationSection(path) && user.isConfigurationSection(path) && !collections.contains(path))
				merge(user, defaults, defaults.getConfigurationSection(path), collections, added);

			// the owner has a value (or a different type) here: theirs wins, nothing below it is touched
		}
	}

	/** Copies one default key (a whole section with everything in it) together with its comments. */
	private static void copy(final YamlConfiguration user, final YamlConfiguration defaults, final String path) {
		if (defaults.isConfigurationSection(path)) {
			final ConfigurationSection source = defaults.getConfigurationSection(path);

			user.createSection(path);

			for (final String key : source.getKeys(false))
				copy(user, defaults, path + "." + key);

		} else
			user.set(path, defaults.get(path));

		user.setComments(path, defaults.getComments(path));
		user.setInlineComments(path, defaults.getInlineComments(path));
	}
}
