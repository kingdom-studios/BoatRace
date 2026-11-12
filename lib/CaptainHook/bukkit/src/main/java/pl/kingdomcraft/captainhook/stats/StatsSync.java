package pl.kingdomcraft.captainhook.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.model.SimpleTask;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * Sends player stat snapshots to the proxy in near real time. The proxy writes them to the database, where the
 * website reads them.
 *
 * Every stats.realtime-interval-seconds only players marked dirty by {@link StatsTracker} are sent, and every
 * stats.sync-interval-minutes all online players (to refresh playtime and the online heartbeat). Snapshots wait in
 * {@link #pending} (one per player, newest wins) and at most one send is in flight, so bursts of changes or a slow
 * proxy coalesce into fewer, larger batches. When the proxy or its database fails, the snapshots are kept and
 * sending pauses with exponential backoff.
 *
 * Whether a player is online is decided by the proxy, which sees every switch and disconnect. So nothing is
 * lost when this server stops or its last player leaves and no message can be sent any more: the numbers are
 * absolute vanilla statistics, and the next snapshot of the player carries them.
 */
public final class StatsSync {

	private static final long BASE_BACKOFF_MILLIS = 5_000;
	private static final long MAX_BACKOFF_MILLIS = 5 * 60_000;

	/**
	 * All fields are accessed on the main thread only.
	 */
	private static final Map<UUID, PlayerSnapshot> pending = new LinkedHashMap<>();

	private static SimpleTask timer;
	private static int ticksUntilFullSync;
	private static boolean saving;
	private static int failures;
	private static long pausedUntil;

	private StatsSync() {
	}

	public static void start() {
		stop();

		ticksUntilFullSync = 0; // send everyone online right away, e.g. after a reload
		timer = Common.runTimer(Settings.Stats.REALTIME_INTERVAL_TICKS, Settings.Stats.REALTIME_INTERVAL_TICKS, StatsSync::tick);
	}

	public static void stop() {
		if (timer != null)
			timer.cancel();

		timer = null;
	}

	/**
	 * Queues the snapshot of a player, sent with the next tick.
	 *
	 * @param online false for a player leaving the server
	 */
	static void queue(final Player player, final boolean online) {
		pending.put(player.getUniqueId(), PlayerSnapshot.of(player, online));
	}

	public static int getPendingCount() {
		return pending.size();
	}

	private static void tick() {
		if ((ticksUntilFullSync -= Settings.Stats.REALTIME_INTERVAL_TICKS) <= 0) {
			ticksUntilFullSync = Settings.Stats.SYNC_INTERVAL_TICKS;

			StatsTracker.clearDirty();

			for (final Player player : Remain.getOnlinePlayers())
				queue(player, true);

		} else
			for (final UUID uuid : StatsTracker.drainDirty()) {
				final Player player = Bukkit.getPlayer(uuid);

				if (player != null)
					queue(player, true);
			}

		// Without a carrier (nobody online, or just joined) the snapshots simply wait, that is not a failure
		if (!saving && !pending.isEmpty() && System.currentTimeMillis() >= pausedUntil && ProxyLink.isAvailable())
			save();
	}

	private static void save() {
		final List<PlayerSnapshot> snapshots = new ArrayList<>(pending.values());

		pending.clear();
		saving = true;

		send(snapshots).whenComplete((ignored, error) -> onSaved(snapshots, error));
	}

	/**
	 * Sends the snapshots in batches small enough for one plugin message each.
	 */
	private static CompletableFuture<Void> send(final List<PlayerSnapshot> snapshots) {
		CompletableFuture<Void> all = CompletableFuture.completedFuture(null);

		for (int from = 0; from < snapshots.size(); from += Protocol.STATS_BATCH_SIZE) {
			final JsonArray players = new JsonArray();

			for (final PlayerSnapshot snapshot : snapshots.subList(from, Math.min(from + Protocol.STATS_BATCH_SIZE, snapshots.size())))
				players.add(snapshot.toJson());

			final JsonObject data = new JsonObject();

			data.add("players", players);
			all = all.thenCombine(ProxyLink.request(Protocol.STATS_BATCH, data), (first, second) -> null);
		}

		return all;
	}

	private static void onSaved(final List<PlayerSnapshot> snapshots, final Throwable error) {
		saving = false;

		if (error == null) {
			failures = 0;

			return;
		}

		// A newer snapshot queued in the meantime wins over the failed one. Batches that did arrive are
		// simply sent again: snapshots are absolute values, saving one twice changes nothing.
		for (final PlayerSnapshot snapshot : snapshots)
			pending.putIfAbsent(snapshot.uuid, snapshot);

		final Throwable cause = error.getCause() != null ? error.getCause() : error;

		// A reload or shutdown of this plugin, not a problem of the proxy
		if (cause instanceof ProxyException && ProxyException.STOPPED.equals(((ProxyException) cause).getCode()))
			return;

		final long backoff = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << Math.min(failures++, 10));

		pausedUntil = System.currentTimeMillis() + backoff;
		Common.log("&cCould not save stats (" + cause.getMessage() + "), " + pending.size() + " snapshot(s) kept, retrying in " + backoff / 1000 + "s.");
	}

	/**
	 * Nothing can be sent while the plugin is being disabled (Bukkit refuses plugin messages of disabled
	 * plugins), so the last few seconds of changes reach the database with each player's next snapshot.
	 */
	public static void shutdown() {
		stop();

		pending.clear();
	}
}
