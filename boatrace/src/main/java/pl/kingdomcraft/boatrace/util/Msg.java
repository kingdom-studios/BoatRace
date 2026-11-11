package pl.kingdomcraft.boatrace.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * All texts of the plugin come from messages.yml. Keys missing in the user's file fall back to the defaults
 * shipped in the jar, so updating the plugin never breaks an older messages.yml.
 *
 * Placeholders are passed as name/value pairs: {@code Msg.send(player, "arena.exists", "arena", name)}.
 */
public final class Msg {

	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	/** key -> list of raw lines (a single-line value is a list of one). Rebuilt on every load. */
	private static Map<String, List<String>> texts = new HashMap<>();
	private static YamlConfiguration yaml = new YamlConfiguration();
	private static String prefix = "&8[&bBoatRace&8] &7";

	private Msg() {
	}

	// ------------------------------------------------------------------------------------------
	// Loading
	// ------------------------------------------------------------------------------------------

	public static void load(final JavaPlugin plugin) {
		final File file = new File(plugin.getDataFolder(), "messages.yml");

		if (!file.exists())
			plugin.saveResource("messages.yml", false);

		final YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);

		try (InputStream in = plugin.getResource("messages.yml")) {
			if (in != null)
				loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));

		} catch (final Exception ex) {
			plugin.getLogger().log(Level.WARNING, "Could not read the default messages.yml", ex);
		}

		yaml = loaded;
		texts = new HashMap<>();
		prefix = loaded.getString("prefix", "");
	}

	private static List<String> rawLines(final String key) {
		return texts.computeIfAbsent(key, k -> {
			if (yaml.isList(k))
				return Collections.unmodifiableList(new ArrayList<>(yaml.getStringList(k)));

			final String value = yaml.getString(k);

			// unknown key: show it, so a typo in messages.yml is easy to spot
			return value == null ? List.of(k) : List.of(value);
		});
	}

	// ------------------------------------------------------------------------------------------
	// Text building
	// ------------------------------------------------------------------------------------------

	/** Replaces {name} placeholders (and {prefix}); also used for the texts of gui.yml. */
	public static String apply(String text, final Object... placeholders) {
		if (text.indexOf('{') < 0)
			return text;

		for (int i = 0; i + 1 < placeholders.length; i += 2)
			text = text.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));

		return text.replace("{prefix}", prefix);
	}

	/** The (single line) message with placeholders applied, still with '&' color codes. */
	public static String text(final String key, final Object... placeholders) {
		return String.join("\n", lines(key, placeholders));
	}

	/** All lines of the message with placeholders applied; lines that end up empty are dropped. */
	public static List<String> lines(final String key, final Object... placeholders) {
		final List<String> result = new ArrayList<>();

		for (final String line : rawLines(key)) {
			final String applied = apply(line, placeholders);

			if (!applied.isEmpty())
				result.add(applied);
		}

		return result;
	}

	public static Component c(final String legacy) {
		return LEGACY.deserialize(legacy);
	}

	/** For item names / lore: colored, not italic. */
	public static Component item(final String legacy) {
		return c(legacy).decoration(TextDecoration.ITALIC, false);
	}

	// ------------------------------------------------------------------------------------------
	// Output
	// ------------------------------------------------------------------------------------------

	/** Chat message, every line with the prefix. */
	public static void send(final CommandSender to, final String key, final Object... placeholders) {
		for (final String line : lines(key, placeholders))
			to.sendMessage(c(prefix + line));
	}

	/** Chat message without the automatic prefix ({prefix} may be used inside). */
	public static void raw(final CommandSender to, final String key, final Object... placeholders) {
		for (final String line : lines(key, placeholders))
			to.sendMessage(c(line));
	}

	public static void actionBar(final Player player, final String key, final Object... placeholders) {
		player.sendActionBar(c(text(key, placeholders)));
	}

	/** Shows title.KEY.title / title.KEY.subtitle with the configured times (ticks). */
	public static void title(final Player player, final String key, final Object... placeholders) {
		final String base = "title." + key;
		final String title = text(base + ".title", placeholders);
		final String subtitle = text(base + ".subtitle", placeholders);

		if (title.isEmpty() && subtitle.isEmpty())
			return;

		player.showTitle(Title.title(c(title), c(subtitle), Title.Times.times(
				ticks(yaml.getInt(base + ".fade-in", 0)),
				ticks(yaml.getInt(base + ".stay", 40)),
				ticks(yaml.getInt(base + ".fade-out", 10)))));
	}

	private static Duration ticks(final int ticks) {
		return Duration.ofMillis(Math.max(0, ticks) * 50L);
	}
}
