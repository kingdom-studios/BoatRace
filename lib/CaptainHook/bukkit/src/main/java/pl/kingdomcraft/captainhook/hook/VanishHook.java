package pl.kingdomcraft.captainhook.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.captainhook.stats.StatsTracker;

/**
 * Vanish integration (optional). Players in vanish are saved with vanished = 1: the website shows them as offline
 * everywhere (rankings, online counts) except on their own dashboard ("On vanish").
 *
 * The state is read from the "vanished" metadata, which SuperVanish, PremiumVanish, EssentialsX and CMI all set, so
 * no vanish plugin is a compile or runtime dependency (without one, nobody is vanished). With SuperVanish or
 * PremiumVanish installed their hide/show events are hooked by name, so a toggle reaches the website within seconds.
 */
public final class VanishHook {

	private static final String[] PLUGINS = { "SuperVanish", "PremiumVanish" };
	private static final String[] EVENTS = { "de.myzelyam.api.vanish.PostPlayerHideEvent", "de.myzelyam.api.vanish.PostPlayerShowEvent" };

	private VanishHook() {
	}

	/**
	 * Main thread only.
	 */
	public static boolean isVanished(final Player player) {
		for (final MetadataValue value : player.getMetadata("vanished"))
			if (value.asBoolean())
				return true;

		return false;
	}

	/**
	 * Hooks the hide/show events of SuperVanish or PremiumVanish if one is installed. Call on startup (like
	 * AfkTracker.hookEssentials, again after every reload).
	 */
	public static void start() {
		for (final String name : PLUGINS) {
			final Plugin vanish = Bukkit.getPluginManager().getPlugin(name);

			if (vanish == null || !vanish.isEnabled())
				continue;

			try {
				for (final String eventName : EVENTS) {
					final Class<? extends Event> eventClass = Class.forName(eventName, true, vanish.getClass().getClassLoader()).asSubclass(Event.class);

					Bukkit.getPluginManager().registerEvent(eventClass, new Listener() {
					}, EventPriority.MONITOR, (listener, event) -> markDirty(event), SimplePlugin.getInstance(), true);
				}

			} catch (final ReflectiveOperationException | LinkageError | RuntimeException ex) {
				Common.warning(name + " is installed but its vanish events could not be hooked (" + ex + "); vanish changes reach the website with the next stats update.");
			}

			return;
		}
	}

	private static void markDirty(final Event event) {
		try {
			final Object player = event.getClass().getMethod("getPlayer").invoke(event);

			if (player instanceof Player)
				StatsTracker.markDirty(((Player) player).getUniqueId());

		} catch (final ReflectiveOperationException | RuntimeException ex) {
			// a version with a different event shape: the change is saved with the next stats update
		}
	}
}
