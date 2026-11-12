package pl.kingdomcraft.captainhook.mission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.model.SimpleTask;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * Reports what players do to the proxy, which counts it for the season pass missions. This server decides nothing:
 * it does not know which missions exist, it only sums events per player and kind and sends them every
 * missions.flush-interval-seconds (and soon after a player leaves).
 *
 * Counted: blocks broken, mobs killed, items crafted (survival and adventure only, not creative or spectator) and a
 * minute of play per minute for players who are not AFK. Logins, votes and minigame stats are counted by the proxy itself.
 *
 * Main thread only.
 */
@AutoRegister
public final class MissionTracker implements Listener {

	/**
	 * Entries of one mission.progress message, well below the proxy's limit and the size of a plugin message.
	 */
	private static final int ENTRIES_PER_MESSAGE = 100;

	private static final long LOG_INTERVAL_MILLIS = 60_000;

	private static final Map<Key, Long> buffer = new HashMap<>();

	private static SimpleTask flushTask;
	private static SimpleTask minuteTask;
	private static long lastLog;

	private static final class Key {

		private final UUID uuid;
		private final String objective;
		private final String world;
		private final String material;
		private final String entity;

		Key(final UUID uuid, final String objective, final String world, final String material, final String entity) {
			this.uuid = uuid;
			this.objective = objective;
			this.world = world;
			this.material = material;
			this.entity = entity;
		}

		UUID uuid() {
			return this.uuid;
		}

		String objective() {
			return this.objective;
		}

		String world() {
			return this.world;
		}

		String material() {
			return this.material;
		}

		String entity() {
			return this.entity;
		}

		@Override
		public boolean equals(final Object other) {
			if (!(other instanceof Key))
				return false;

			final Key key = (Key) other;

			return this.uuid.equals(key.uuid) && this.objective.equals(key.objective) && java.util.Objects.equals(this.world, key.world)
					&& java.util.Objects.equals(this.material, key.material) && java.util.Objects.equals(this.entity, key.entity);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(this.uuid, this.objective, this.world, this.material, this.entity);
		}
	}

	/**
	 * Starts the timers. Call on startup and after every reload (Foundation cancels its tasks on reload).
	 */
	public static void start() {
		stop();

		flushTask = Common.runTimer(Settings.Missions.FLUSH_TICKS, Settings.Missions.FLUSH_TICKS, MissionTracker::flush);
		minuteTask = Common.runTimer(20 * 60, 20 * 60, MissionTracker::countPlaytime);

		AfkTracker.hookEssentials();

		for (final Player player : Remain.getOnlinePlayers())
			AfkTracker.markActive(player);
	}

	public static void stop() {
		if (flushTask != null)
			flushTask.cancel();

		if (minuteTask != null)
			minuteTask.cancel();

		flushTask = null;
		minuteTask = null;
	}

	/**
	 * Adds to the progress of a mission objective. The public entry point of CaptainHookAPI.reportMissionProgress.
	 *
	 * @param world    world of the event, or null
	 * @param material block or item type, or null
	 * @param entity   entity type, or null
	 */
	public static void report(final UUID uuid, final String objective, final long delta, final String world, final String material, final String entity) {
		if (delta <= 0)
			return;

		buffer.merge(new Key(uuid, objective, world, material, entity), delta, Long::sum);
	}

	private static boolean counts(final Player player) {
		final GameMode mode = player.getGameMode();

		return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
	}

	private static void countPlaytime() {
		for (final Player player : Remain.getOnlinePlayers())
			if (counts(player) && !AfkTracker.isAfk(player))
				report(player.getUniqueId(), "PLAYTIME_MINUTES", 1, player.getWorld().getName(), null, null);
	}

	/**
	 * Sends everything counted so far. Requests are queued by ProxyLink while nobody can carry them, so progress made
	 * just before the last player leaves is not lost.
	 */
	public static void flush() {
		if (buffer.isEmpty())
			return;

		final List<Map.Entry<Key, Long>> entries = new ArrayList<>(buffer.entrySet());

		buffer.clear();

		for (int from = 0; from < entries.size(); from += ENTRIES_PER_MESSAGE) {
			final JsonArray array = new JsonArray();

			for (final Map.Entry<Key, Long> entry : entries.subList(from, Math.min(from + ENTRIES_PER_MESSAGE, entries.size()))) {
				final Key key = entry.getKey();
				final JsonObject json = new JsonObject();

				json.addProperty("uuid", key.uuid().toString());
				json.addProperty("objective", key.objective());
				json.addProperty("delta", entry.getValue());

				if (key.world() != null)
					json.addProperty("world", key.world());

				if (key.material() != null)
					json.addProperty("material", key.material());

				if (key.entity() != null)
					json.addProperty("entity", key.entity());

				array.add(json);
			}

			final JsonObject data = new JsonObject();

			data.add("entries", array);

			// A batch that fails is dropped, not repeated: the proxy may already have counted it
			ProxyLink.request(Protocol.MISSION_PROGRESS, data, entries.get(from).getKey().uuid(), true).whenComplete((reply, error) -> {
				if (error != null)
					logFailure(error);
			});
		}
	}

	private static void logFailure(final Throwable error) {
		final Throwable cause = error.getCause() != null ? error.getCause() : error;

		if (cause instanceof ProxyException && (ProxyException.STOPPED.equals(((ProxyException) cause).getCode()) || ProxyException.NO_CARRIER.equals(((ProxyException) cause).getCode())))
			return;

		final long now = System.currentTimeMillis();

		if (now - lastLog > LOG_INTERVAL_MILLIS) {
			lastLog = now;
			Common.warning("Could not report mission progress to the proxy (" + cause.getMessage() + "). At most one such warning a minute is logged.");
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(final BlockBreakEvent event) {
		final Player player = event.getPlayer();

		if (counts(player))
			report(player.getUniqueId(), "BLOCK_BREAK", 1, event.getBlock().getWorld().getName(), event.getBlock().getType().name(), null);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onEntityDeath(final EntityDeathEvent event) {
		final Player killer = event.getEntity().getKiller();

		if (killer != null && counts(killer) && event.getEntity() instanceof LivingEntity && !(event.getEntity() instanceof Player))
			report(killer.getUniqueId(), "MOB_KILL", 1, killer.getWorld().getName(), null, event.getEntityType().name());
	}

	/**
	 * Counts the items of the recipe once per craft (shift-clicking several crafts at once counts as one).
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onCraft(final CraftItemEvent event) {
		if (!(event.getWhoClicked() instanceof Player))
			return;

		final Player player = (Player) event.getWhoClicked();
		final org.bukkit.inventory.ItemStack result = event.getRecipe().getResult();

		if (counts(player) && result != null)
			report(player.getUniqueId(), "ITEM_CRAFT", Math.max(1, result.getAmount()), player.getWorld().getName(), result.getType().name(), null);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(final PlayerJoinEvent event) {
		AfkTracker.markActive(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		// A tick later: a player who is leaving cannot carry the message any more, another one (or the queue) can
		Common.runLater(1, MissionTracker::flush);
	}
}
