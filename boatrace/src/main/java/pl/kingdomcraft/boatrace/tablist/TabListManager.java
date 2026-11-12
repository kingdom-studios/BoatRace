package pl.kingdomcraft.boatrace.tablist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import pl.kingdomcraft.boatrace.Settings;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.spectate.SpectateManager;

/**
 * tablist.session-only: every player only sees the players of their own group in the tab list - the race session
 * they are in (or watch as a spectator), otherwise the lobby. Uses Paper's listPlayer / unlistPlayer: the players stay
 * visible in the world, only their tab list entry is hidden. Unlisting is never saved, so a crash leaves nothing behind.
 * <p>
 * Refreshed twice a second and when somebody joins (a race starting / ending shows up within half a second); a viewer
 * only gets a packet when the visibility of somebody actually changes. Players hidden by a vanish plugin are left alone.
 */
public final class TabListManager implements Listener {

	public static final String BYPASS_PERMISSION = "boatrace.tablist.bypass";

	private static final long PERIOD_TICKS = 10L;
	/** group of every player who is not in a race */
	private static final String LOBBY = "";

	private final JavaPlugin plugin;
	private final Settings settings;
	private final RaceManager races;
	private final SpectateManager spectate;

	private BukkitTask task;
	/** something was unlisted by us: restore once the feature is switched off */
	private boolean active;

	public TabListManager(final JavaPlugin plugin, final Settings settings, final RaceManager races, final SpectateManager spectate) {
		this.plugin = plugin;
		this.settings = settings;
		this.races = races;
		this.spectate = spectate;
	}

	public void start() {
		if (this.task == null)
			this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::refresh, PERIOD_TICKS, PERIOD_TICKS);
	}

	/** Plugin stop: everybody is listed again. */
	public void shutdown() {
		if (this.task != null) {
			this.task.cancel();
			this.task = null;
		}

		this.showAll();
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(final PlayerJoinEvent event) {
		// one tick later: the new player has been sent to everybody (and everybody to them) by then
		if (this.settings.tabListSessionOnly)
			Bukkit.getScheduler().runTask(this.plugin, this::refresh);
	}

	/** The group of a player: their race instance, the instance of the racer they spectate, or the lobby. */
	private String groupOf(final Player player) {
		final String own = this.races.tabGroupOf(player.getUniqueId());

		if (own != null)
			return own;

		final UUID target = this.spectate != null ? this.spectate.targetOf(player.getUniqueId()) : null;
		final String watched = target != null ? this.races.tabGroupOf(target) : null;

		return watched != null ? watched : LOBBY;
	}

	private void refresh() {
		if (!this.settings.tabListSessionOnly) {
			// switched off by /bre reload: undo what we did once
			if (this.active)
				this.showAll();

			return;
		}

		this.active = true;

		final List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
		final Map<Player, String> groups = new HashMap<>(online.size() * 2);

		for (final Player player : online)
			groups.put(player, this.groupOf(player));

		for (final Player viewer : online) {
			final boolean bypass = viewer.hasPermission(BYPASS_PERMISSION);
			final String group = groups.get(viewer);

			for (final Player target : online) {
				// a vanished player stays the vanish plugin's business
				if (target == viewer || !viewer.canSee(target))
					continue;

				final boolean show = bypass || Objects.equals(group, groups.get(target));

				if (show != viewer.isListed(target))
					if (show)
						viewer.listPlayer(target);
					else
						viewer.unlistPlayer(target);
			}
		}
	}

	private void showAll() {
		this.active = false;

		for (final Player viewer : Bukkit.getOnlinePlayers())
			for (final Player target : Bukkit.getOnlinePlayers())
				if (target != viewer && viewer.canSee(target) && !viewer.isListed(target))
					viewer.listPlayer(target);
	}
}
