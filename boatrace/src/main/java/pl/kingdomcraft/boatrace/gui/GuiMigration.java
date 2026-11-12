package pl.kingdomcraft.boatrace.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Turns the old single-menu {@code gui.yml} into {@code guis.yml}: the old menu becomes the menu "unranked" with the
 * same title, size, icons, slots, lore and the same clicks (written out as per-click actions), its competitive buttons
 * become ranked items, and the default "ranked" menu of the jar is added. Nothing the owner configured is lost.
 * Plain logic, no file access.
 */
public final class GuiMigration {

	private GuiMigration() {
	}

	/**
	 * @param old           the old gui.yml
	 * @param defaultRanked {@code menus.ranked} of the jar's guis.yml, null = none is added
	 */
	public static YamlConfiguration convert(final ConfigurationSection old, final ConfigurationSection defaultRanked) {
		final YamlConfiguration out = new YamlConfiguration();

		out.set("default-menu", "unranked");

		copyValues(old.getConfigurationSection("status"), out, "status");
		copyValues(old.getConfigurationSection("access"), out, "access");
		copyValues(old.getConfigurationSection("maintenance"), out, "maintenance");

		final String menu = "menus.unranked.";

		out.set(menu + "title", old.getString("title", "&8Select a map"));
		out.set(menu + "rows", old.getInt("rows", 3));
		out.set(menu + "laps.min", old.getInt("laps.min", 1));
		out.set(menu + "laps.max", old.getInt("laps.max", 5));

		final ConfigurationSection filler = old.getConfigurationSection("filler");

		if (filler != null)
			copyValues(filler, out, menu + "filler");

		final Map<String, String> clicks = clickActions(old);

		// auto-maps: the old "unlisted-maps"
		final ConfigurationSection unlisted = old.getConfigurationSection("unlisted-maps");

		if (unlisted != null) {
			copyValues(unlisted, out, menu + "auto-maps");
			out.set(menu + "auto-maps.filter", "all");
			writeClicks(out, menu + "auto-maps.actions", clicks);
		}

		// items: later ones win a slot, so the order is the old drawing order: decoration, close, random, competitive, maps
		final String items = menu + "items.";
		final Set<String> used = new java.util.HashSet<>();

		final ConfigurationSection decoration = old.getConfigurationSection("items");

		if (decoration != null)
			for (final String key : decoration.getKeys(false))
				if (decoration.isConfigurationSection(key))
					copyValues(decoration.getConfigurationSection(key), out, items + unique(used, key));

		for (final String key : List.of("close", "random")) {
			final ConfigurationSection section = old.getConfigurationSection(key);

			// the old buttons were on unless "enabled: false"
			if (section == null || !section.getBoolean("enabled", true))
				continue;

			final String path = items + unique(used, key);

			copyValues(section, out, path);
			out.set(path + ".enabled", null);
			out.set(path + ".actions", key.equals("close") ? List.of("close") : List.of("random-map unranked"));
		}

		final ConfigurationSection competitive = old.getConfigurationSection("competitive-buttons");

		if (competitive != null)
			for (final String key : competitive.getKeys(false)) {
				final ConfigurationSection section = competitive.getConfigurationSection(key);

				if (section == null)
					continue;

				final String path = items + unique(used, "ranked-" + key);

				copyValues(section, out, path);
				out.set(path + ".arena", section.getString("arena", "random"));
				out.set(path + ".requires-competitive", true);
				out.set(path + ".actions", List.of("ranked-map"));
			}

		final ConfigurationSection maps = old.getConfigurationSection("maps");

		if (maps != null)
			for (final String arena : maps.getKeys(false)) {
				final ConfigurationSection section = maps.getConfigurationSection(arena);

				if (section == null)
					continue;

				final String path = items + unique(used, "map-" + arena.toLowerCase(Locale.ROOT));

				copyValues(section, out, path);
				out.set(path + ".arena", arena);
				writeClicks(out, path + ".actions", clicks);
			}

		if (defaultRanked != null)
			copyValues(defaultRanked, out, "menus.ranked");

		return out;
	}

	/**
	 * The old "clicks" section as click -> action. The old order of precedence is kept: join before laps-increase before
	 * laps-decrease when a click was listed twice.
	 */
	static Map<String, String> clickActions(final ConfigurationSection old) {
		final Map<String, String> result = new LinkedHashMap<>();

		put(result, old, "clicks.join", "join-map", "SHIFT_LEFT", "SHIFT_RIGHT", "MIDDLE");
		put(result, old, "clicks.laps-increase", "laps-increase", "LEFT");
		put(result, old, "clicks.laps-decrease", "laps-decrease", "RIGHT");

		return result;
	}

	private static void put(final Map<String, String> result, final ConfigurationSection old, final String path, final String action, final String... defaults) {
		final List<String> clicks = old.getStringList(path);

		if (clicks.isEmpty())
			clicks.addAll(List.of(defaults));

		for (final String click : clicks)
			result.putIfAbsent(click.trim().toUpperCase(Locale.ROOT).replace('-', '_'), action);
	}

	private static void writeClicks(final YamlConfiguration out, final String path, final Map<String, String> clicks) {
		for (final Map.Entry<String, String> entry : clicks.entrySet())
			out.set(path + "." + entry.getKey(), entry.getValue());
	}

	/** Copies every plain value below {@code from} to {@code to} (keeps nested sections, lists and their order). */
	static void copyValues(final ConfigurationSection from, final YamlConfiguration out, final String to) {
		if (from == null)
			return;

		for (final String key : from.getKeys(false)) {
			final Object value = from.get(key);

			if (value instanceof final ConfigurationSection nested)
				copyValues(nested, out, to + "." + key);
			else
				out.set(to + "." + key, value);
		}
	}

	private static String unique(final Set<String> used, final String id) {
		String candidate = id;
		int n = 2;

		while (!used.add(candidate.toLowerCase(Locale.ROOT)))
			candidate = id + "_" + n++;

		return candidate;
	}
}
