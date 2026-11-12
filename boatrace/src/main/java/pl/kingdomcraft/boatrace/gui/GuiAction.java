package pl.kingdomcraft.boatrace.gui;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * One thing a click in a menu does, written in guis.yml as {@code "name argument"} (or {@code "name: argument"}).
 *
 * <pre>
 * close                        close the menu
 * open-menu &lt;menu&gt;             open another menu (silent)
 * join-map [arena]             join a map with the laps chosen in the menu (arena = the item's own map by default)
 * ranked-map [arena|random]    join / leave the competitive lobby of a map (random = any map)
 * random-map [unranked|ranked] a random casual map / the competitive lobby of any map
 * laps-increase [arena]        +1 lap for the map (up to the menu's laps.max)
 * laps-decrease [arena]        -1 lap (down to laps.min)
 * player-command &lt;command&gt;     run a command as the player
 * console-command &lt;command&gt;    run a command from the console
 * message &lt;text&gt;               send a chat message to the player
 * </pre>
 * Placeholders in commands / messages: {player} {uuid} {arena} {laps}.
 */
public record GuiAction(Type type, String argument) {

	public enum Type {
		CLOSE, OPEN_MENU, JOIN_MAP, RANKED_MAP, RANDOM_MAP, LAPS_INCREASE, LAPS_DECREASE, PLAYER_COMMAND, CONSOLE_COMMAND, MESSAGE;

		/** "join-map" / "JOIN_MAP" / "Join-Map"; null when unknown */
		static Type of(final String raw) {
			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));

			} catch (final IllegalArgumentException ex) {
				return null;
			}
		}

		boolean needsArgument() {
			return this == OPEN_MENU || this == PLAYER_COMMAND || this == CONSOLE_COMMAND || this == MESSAGE;
		}
	}

	/** True for the actions that work on a competitive lobby. */
	public boolean isRanked() {
		return this.type == Type.RANKED_MAP || this.type == Type.RANDOM_MAP && "ranked".equals(this.argument);
	}

	/**
	 * @param raw  {@code "join-map monaco"}, {@code "close"}, {@code "message: &aHello"}
	 * @param warn receives the reason when the action is unusable
	 * @return null when the action is unknown or its argument is missing / invalid
	 */
	public static GuiAction parse(final String raw, final Consumer<String> warn) {
		final String text = raw == null ? "" : raw.trim();

		if (text.isEmpty())
			return null;

		// the name ends at the first space or colon; a colon right after the name is only a separator
		int end = 0;

		while (end < text.length() && text.charAt(end) != ' ' && text.charAt(end) != ':' && text.charAt(end) != '\t')
			end++;

		final String name = text.substring(0, end);
		String argument = text.substring(end).trim();

		if (argument.startsWith(":"))
			argument = argument.substring(1).trim();

		final Type type = Type.of(name);

		if (type == null) {
			warn.accept("unknown action '" + name + "' (in '" + text + "')");
			return null;
		}

		if (type.needsArgument() && argument.isEmpty()) {
			warn.accept("action '" + name + "' needs an argument (in '" + text + "')");
			return null;
		}

		if (type == Type.RANDOM_MAP) {
			if (argument.isEmpty() || "unranked".equalsIgnoreCase(argument))
				argument = "unranked";

			else if ("ranked".equalsIgnoreCase(argument))
				argument = "ranked";

			else {
				warn.accept("random-map takes 'ranked' or 'unranked', not '" + argument + "'");
				return null;
			}
		}

		return new GuiAction(type, type == Type.CLOSE ? "" : argument);
	}
}
