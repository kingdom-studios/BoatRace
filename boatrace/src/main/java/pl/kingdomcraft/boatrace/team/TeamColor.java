package pl.kingdomcraft.boatrace.team;

import java.util.Locale;

import org.bukkit.Color;

/**
 * A team color of config.yml "teams.colors": armor dye, display name and chat color code.
 *
 * @param id    key in the config (RED, BLUE ...)
 * @param name  shown to players
 * @param armor dye of the leather armor
 * @param chat  legacy color code for texts, e.g. "&c"
 */
public record TeamColor(String id, String name, Color armor, String chat) {

	/** "&c" + name */
	public String colored() {
		return this.chat + this.name;
	}

	/**
	 * @param raw a Bukkit color constant (RED, AQUA, ORANGE ...) or a hex color "#B02E26"
	 * @return the color, or null if the text is neither
	 */
	public static Color parseColor(final String raw) {
		if (raw == null || raw.isBlank())
			return null;

		final String value = raw.trim();

		if (value.startsWith("#"))
			try {
				return Color.fromRGB(Integer.parseInt(value.substring(1), 16) & 0xFFFFFF);

			} catch (final NumberFormatException ex) {
				return null;
			}

		try {
			final Object constant = Color.class.getField(value.toUpperCase(Locale.ROOT)).get(null);

			return constant instanceof final Color color ? color : null;

		} catch (final ReflectiveOperationException ex) {
			return null;
		}
	}
}
