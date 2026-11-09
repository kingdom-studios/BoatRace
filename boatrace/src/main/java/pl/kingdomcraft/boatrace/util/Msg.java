package pl.kingdomcraft.boatrace.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Small helper for '&'-colored messages. */
public final class Msg {

	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

	public static final String PREFIX = "&8[&bBoatRace&8] &7";

	private Msg() {
	}

	public static Component c(final String legacy) {
		return LEGACY.deserialize(legacy);
	}

	public static void send(final CommandSender to, final String message) {
		to.sendMessage(c(PREFIX + message));
	}

	public static void raw(final CommandSender to, final String message) {
		to.sendMessage(c(message));
	}

	public static void actionBar(final Player player, final String message) {
		player.sendActionBar(c(message));
	}
}
