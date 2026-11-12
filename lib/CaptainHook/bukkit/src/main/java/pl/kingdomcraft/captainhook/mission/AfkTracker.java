package pl.kingdomcraft.captainhook.mission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * Who is AFK, so minutes of play only count for players who are actually playing.
 *
 * With EssentialsX installed its own status is used: its AfkStatusChangeEvent tells when a player goes AFK or comes back
 * (so Essentials' auto-afk time and /afk apply). The event class is looked up by name, EssentialsX is not a
 * compile dependency. Without EssentialsX a player counts as AFK after afk-after-seconds without moving, looking
 * around or interacting.
 *
 * Main thread only.
 */
@AutoRegister
public final class AfkTracker implements Listener {

	private static final String ESSENTIALS_EVENT = "net.ess3.api.events.AfkStatusChangeEvent";

	/**
	 * Players Essentials reports as AFK.
	 */
	private static final Set<UUID> essentialsAfk = ConcurrentHashMap.newKeySet();

	private static final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();

	private static boolean essentialsHooked;

	/**
	 * Hooks into EssentialsX if it is installed. Call on startup (Foundation re-registers listeners on reload, so
	 * this may run again; the hook is only made once per start of the plugin).
	 */
	public static void hookEssentials() {
		essentialsAfk.clear();

		final Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");

		if (essentials == null || !essentials.isEnabled()) {
			essentialsHooked = false;

			return;
		}

		try {
			final Class<? extends Event> eventClass = Class.forName(ESSENTIALS_EVENT, true, essentials.getClass().getClassLoader()).asSubclass(Event.class);

			Bukkit.getPluginManager().registerEvent(eventClass, new Listener() {
			}, EventPriority.MONITOR, (listener, event) -> onEssentialsEvent(event), SimplePlugin.getInstance(), true);

			essentialsHooked = true;

		} catch (final ReflectiveOperationException | LinkageError | RuntimeException ex) {
			essentialsHooked = false;
			Common.warning("EssentialsX is installed but its AFK event could not be hooked (" + ex + "), using movement to detect AFK instead.");
		}
	}

	private static void onEssentialsEvent(final Event event) {
		try {
			final Object user = event.getClass().getMethod("getAffected").invoke(event);
			final boolean afk = (Boolean) event.getClass().getMethod("getValue").invoke(event);
			final Object base = user.getClass().getMethod("getBase").invoke(user);

			if (base instanceof Player) {
				final UUID uuid = ((Player) base).getUniqueId();

				if (afk)
					essentialsAfk.add(uuid);
				else
					essentialsAfk.remove(uuid);
			}

		} catch (final ReflectiveOperationException | RuntimeException ex) {
			// An Essentials version with a different event shape: the movement check is used for everyone
			essentialsHooked = false;
		}
	}

	public static boolean isAfk(final Player player) {
		if (essentialsHooked)
			return essentialsAfk.contains(player.getUniqueId());

		final Long last = lastActive.get(player.getUniqueId());

		return last != null && System.currentTimeMillis() - last > Settings.Missions.AFK_SECONDS * 1000L;
	}

	private static void active(final Player player) {
		lastActive.put(player.getUniqueId(), System.currentTimeMillis());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(final PlayerMoveEvent event) {
		// Standing still and turning the mouse around is activity too, falling or being pushed is not
		if (event.getFrom().getYaw() != event.getTo().getYaw() || event.getFrom().getPitch() != event.getTo().getPitch()
				|| event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockZ() != event.getTo().getBlockZ())
			active(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onInteract(final PlayerInteractEvent event) {
		active(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		lastActive.remove(event.getPlayer().getUniqueId());
		essentialsAfk.remove(event.getPlayer().getUniqueId());
	}

	/**
	 * A player who just joined is active.
	 */
	public static void markActive(final Player player) {
		active(player);
	}
}
