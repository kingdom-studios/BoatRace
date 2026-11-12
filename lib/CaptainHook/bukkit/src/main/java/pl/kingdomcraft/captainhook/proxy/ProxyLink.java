package pl.kingdomcraft.captainhook.proxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.plugin.SimplePlugin;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Message;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.protocol.ProtocolException;
import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * The connection of this server to the CaptainHook plugin on the proxy: the captainhook:main plugin messaging
 * channel, see docs/NETWORK_PROTOCOL.md. This server has no database, everything it stores or asks goes through here.
 *
 * Plugin messages travel inside a player's connection ("carrier"), so the proxy can only be reached while at
 * least one player is on this server. Requests made without a carrier fail right away with
 * {@link ProxyException#NO_CARRIER}, unless they were queued (see {@link #request(String, JsonObject, UUID, boolean)}).
 *
 * All state is touched on the main thread only. The request methods may be called from any thread, and the
 * futures they return always complete on the main thread.
 */
public final class ProxyLink {

	private static final long TIMEOUT_MILLIS = 10_000;

	/**
	 * Queued requests are for deltas (XP, minigame stats) reported while nobody could carry them. A server
	 * that stays empty must not collect them forever.
	 */
	private static final long QUEUE_MILLIS = 5 * 60_000;
	private static final int MAX_QUEUED = 2000;

	private static final Map<Long, Pending> pending = new HashMap<>();
	private static final Deque<Queued> queue = new ArrayDeque<>();
	private static final Map<String, Consumer<JsonObject>> listeners = new HashMap<>();

	/**
	 * Starts at the clock so IDs of a restarted server cannot match replies to requests made before the restart.
	 */
	private static long nextId = System.currentTimeMillis();
	private static boolean running;
	private static boolean versionWarned;

	private ProxyLink() {
	}

	/* ------------------------------------------------------------------------------- */
	/* Lifecycle */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Registers the channel. Foundation unregisters channels and tasks on every reload, so this is called
	 * on startup and after each reload.
	 */
	public static void start() {
		final SimplePlugin plugin = SimplePlugin.getInstance();
		final Messenger messenger = Bukkit.getMessenger();

		if (!messenger.isOutgoingChannelRegistered(plugin, Protocol.CHANNEL))
			messenger.registerOutgoingPluginChannel(plugin, Protocol.CHANNEL);

		if (!messenger.isIncomingChannelRegistered(plugin, Protocol.CHANNEL))
			messenger.registerIncomingPluginChannel(plugin, Protocol.CHANNEL, (channel, player, bytes) -> receive(bytes));

		running = true;
		Common.runTimer(20, 20, ProxyLink::expire);
	}

	/**
	 * Fails everything still waiting. Call before a reload and on shutdown.
	 */
	public static void stop() {
		running = false;
		listeners.clear();

		final List<CompletableFuture<JsonObject>> waiting = new ArrayList<>();

		for (final Pending request : pending.values())
			waiting.add(request.future);

		for (final Queued request : queue)
			waiting.add(request.future);

		pending.clear();
		queue.clear();

		for (final CompletableFuture<JsonObject> future : waiting)
			future.completeExceptionally(new ProxyException(ProxyException.STOPPED, null));
	}

	/**
	 * @return true if a message to the proxy could be sent right now
	 */
	public static boolean isAvailable() {
		return running && findCarrier(null) != null;
	}

	public static boolean canCarry(final Player player) {
		return player.isOnline() && player.getListeningPluginChannels().contains(Protocol.CHANNEL);
	}

	public static int getPendingCount() {
		return pending.size();
	}

	public static int getQueuedCount() {
		return queue.size();
	}

	/* ------------------------------------------------------------------------------- */
	/* Sending */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Sends a request and completes the future with the payload of the reply, or exceptionally with
	 * {@link ProxyException}. Fails right away when nobody can carry the message.
	 */
	public static CompletableFuture<JsonObject> request(final String type, final JsonObject data) {
		return request(type, data, null, false);
	}

	/**
	 * @param about player the request is about, preferred as the carrier (may be null)
	 * @param queue true to keep the request until a carrier appears instead of failing, for changes that
	 *              would otherwise be lost (XP, minigame stats)
	 */
	public static CompletableFuture<JsonObject> request(final String type, final JsonObject data, final UUID about, final boolean queue) {
		final CompletableFuture<JsonObject> future = new CompletableFuture<>();

		if (Bukkit.isPrimaryThread())
			dispatch(new Queued(type, data, about, future), queue);
		else
			Common.runLater(() -> dispatch(new Queued(type, data, about, future), queue));

		return future;
	}

	/**
	 * Sends a notification (no reply expected). Main thread only.
	 *
	 * @param about player the notification is about, preferred as the carrier (may be null)
	 * @return false if nobody can carry it right now, so it was not sent
	 */
	public static boolean notify(final String type, final JsonObject data, final UUID about) {
		final Player carrier = running ? findCarrier(about) : null;

		if (carrier == null)
			return false;

		final byte[] bytes = Message.notification(type, Settings.Api.SERVER_ID, data).encode();

		if (bytes.length > Protocol.MAX_PAYLOAD_BYTES)
			throw new IllegalArgumentException(type + " is " + bytes.length + " bytes, over the plugin message limit");

		carrier.sendPluginMessage(SimplePlugin.getInstance(), Protocol.CHANNEL, bytes);
		return true;
	}

	private static void dispatch(final Queued request, final boolean mayQueue) {
		if (!running) {
			request.future.completeExceptionally(new ProxyException(ProxyException.STOPPED, null));

			return;
		}

		final Player carrier = findCarrier(request.about);

		if (carrier != null) {
			send(carrier, request);

			return;
		}

		if (!mayQueue || queue.size() >= MAX_QUEUED)
			request.future.completeExceptionally(new ProxyException(ProxyException.NO_CARRIER, mayQueue ? "the queue is full" : null));
		else
			queue.add(request);
	}

	private static void send(final Player carrier, final Queued request) {
		final long id = nextId++;
		final byte[] bytes = Message.request(request.type, id, Settings.Api.SERVER_ID, request.data).encode();

		if (bytes.length > Protocol.MAX_PAYLOAD_BYTES) {
			request.future.completeExceptionally(new ProxyException(Protocol.ERROR_TOO_LARGE, request.type + " is " + bytes.length + " bytes"));

			return;
		}

		pending.put(id, new Pending(request.future));
		carrier.sendPluginMessage(SimplePlugin.getInstance(), Protocol.CHANNEL, bytes);
	}

	/**
	 * @param preferred the player the message is about, or null
	 * @return a player whose connection can carry a message to the proxy, or null
	 */
	private static Player findCarrier(final UUID preferred) {
		if (preferred != null) {
			final Player player = Bukkit.getPlayer(preferred);

			if (player != null && canCarry(player))
				return player;
		}

		for (final Player player : Remain.getOnlinePlayers())
			if (canCarry(player))
				return player;

		return null;
	}

	/**
	 * Sends what was queued while nobody could carry it. Called when a player becomes a carrier.
	 */
	static void flushQueue() {
		while (!queue.isEmpty()) {
			final Player carrier = findCarrier(queue.peek().about);

			if (carrier == null)
				return;

			send(carrier, queue.poll());
		}
	}

	/* ------------------------------------------------------------------------------- */
	/* Receiving */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Calls the handler with the payload of every notification of this type. Handlers are forgotten on
	 * {@link #stop()}, so register them on startup and after every reload.
	 */
	public static void on(final String type, final Consumer<JsonObject> handler) {
		listeners.put(type, handler);
	}

	private static void receive(final byte[] bytes) {
		final Message message;

		try {
			message = Message.decode(bytes);

		} catch (final ProtocolException ex) {
			Common.warning("Ignoring a malformed message from the proxy: " + ex.getMessage());

			return;
		}

		// Replies are matched whatever their version: "unsupported_version" is one
		if (message.isReply()) {
			final Pending request = pending.remove(message.getReplyTo());

			if (request == null)
				return; // timed out, or asked before a reload

			if (message.isOk())
				request.future.complete(message.getData());
			else
				request.future.completeExceptionally(new ProxyException(message.getError() != null ? message.getError() : "error", message.getErrorMessage()));

			return;
		}

		if (message.getVersion() != Protocol.VERSION) {
			if (!versionWarned)
				Common.warning("The proxy speaks protocol version " + message.getVersion() + ", this server speaks " + Protocol.VERSION
						+ ". Install the same CaptainHook version on both.");

			versionWarned = true;
			return;
		}

		final Consumer<JsonObject> listener = listeners.get(message.getType());

		// Types this version does not know are ignored: a newer proxy may send more
		if (listener != null)
			try {
				listener.accept(message.getData());

			} catch (final RuntimeException ex) {
				Common.error(ex, "Could not handle " + message.getType() + " from the proxy");
			}
	}

	/**
	 * Fails requests the proxy did not answer and queued ones nobody could carry for too long.
	 */
	private static void expire() {
		final long now = System.currentTimeMillis();
		final List<CompletableFuture<JsonObject>> expired = new ArrayList<>();
		final List<CompletableFuture<JsonObject>> undeliverable = new ArrayList<>();

		for (final Iterator<Pending> iterator = pending.values().iterator(); iterator.hasNext();) {
			final Pending request = iterator.next();

			if (now - request.sentAt > TIMEOUT_MILLIS) {
				iterator.remove();
				expired.add(request.future);
			}
		}

		while (!queue.isEmpty() && now - queue.peek().createdAt > QUEUE_MILLIS)
			undeliverable.add(queue.poll().future);

		// Completed after the collections are consistent: callbacks may send new requests
		for (final CompletableFuture<JsonObject> future : expired)
			future.completeExceptionally(new ProxyException(ProxyException.TIMEOUT, "no reply within " + TIMEOUT_MILLIS / 1000 + " seconds"));

		for (final CompletableFuture<JsonObject> future : undeliverable)
			future.completeExceptionally(new ProxyException(ProxyException.NO_CARRIER, "nobody joined to carry the message for " + QUEUE_MILLIS / 60_000 + " minutes"));
	}

	private static final class Pending {

		final CompletableFuture<JsonObject> future;
		final long sentAt = System.currentTimeMillis();

		Pending(final CompletableFuture<JsonObject> future) {
			this.future = future;
		}
	}

	private static final class Queued {

		final String type;
		final JsonObject data;
		final UUID about;
		final CompletableFuture<JsonObject> future;
		final long createdAt = System.currentTimeMillis();

		Queued(final String type, final JsonObject data, final UUID about, final CompletableFuture<JsonObject> future) {
			this.type = type;
			this.data = data;
			this.about = about;
			this.future = future;
		}
	}
}
