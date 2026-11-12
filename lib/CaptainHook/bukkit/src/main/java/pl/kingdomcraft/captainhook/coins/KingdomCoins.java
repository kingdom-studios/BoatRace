package pl.kingdomcraft.captainhook.coins;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;

/**
 * KingdomCoins - the network-wide premium currency. Public API for commands, placeholders and other plugins.
 *
 * The coins live in the network database, which only the proxy talks to. Every write is a request to the proxy
 * and returns a future completed on the main thread, failing with {@link ProxyException} when the proxy cannot
 * be reached or its database is unavailable. Balances of the players on this server and the top list are
 * cached for placeholders; the proxy pushes every change, also those made on other servers or by the website.
 */
public final class KingdomCoins {

	public static final int TOP_SIZE = 10;
	public static final long MAX_AMOUNT = 1_000_000_000_000L;

	private static final Map<UUID, Long> balances = new ConcurrentHashMap<>();
	private static volatile List<CoinAccount> top = Collections.emptyList();

	private KingdomCoins() {
	}

	/* ------------------------------------------------------------------------------- */
	/* Lifecycle */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Listens for balance and top list changes pushed by the proxy. Call on startup and after every reload.
	 */
	public static void start() {
		ProxyLink.on(Protocol.COINS_BALANCE, data -> {
			final UUID uuid = Json.getUuid(data, "uuid");

			// Only players on this server are cached: a late push for someone who left must not bring them back
			if (uuid != null && Bukkit.getPlayer(uuid) != null)
				balances.put(uuid, Json.getLong(data, "balance", 0));
		});

		ProxyLink.on(Protocol.COINS_TOP, data -> top = parseTop(Json.getArray(data, "entries")));
	}

	/**
	 * Caches what the proxy sent about a player who just arrived (the reply to player.ready).
	 */
	public static void onPlayerReady(final UUID uuid, final JsonObject reply) {
		balances.put(uuid, Json.getLong(reply, "coins", 0));
		top = parseTop(Json.getArray(reply, "top"));
	}

	public static void unload(final UUID uuid) {
		balances.remove(uuid);
	}

	private static List<CoinAccount> parseTop(final JsonArray entries) {
		final List<CoinAccount> accounts = new ArrayList<>();

		for (final JsonElement element : entries)
			if (element.isJsonObject()) {
				final CoinAccount account = parseAccount(element.getAsJsonObject());

				if (account != null)
					accounts.add(account);
			}

		return Collections.unmodifiableList(accounts);
	}

	private static CoinAccount parseAccount(final JsonObject json) {
		final UUID uuid = Json.getUuid(json, "uuid");

		return uuid != null ? new CoinAccount(uuid, Json.getString(json, "name", ""), Json.getLong(json, "balance", 0)) : null;
	}

	/* ------------------------------------------------------------------------------- */
	/* Reading */
	/* ------------------------------------------------------------------------------- */

	/**
	 * @return the cached balance of a player on this server, or -1 if not loaded yet. Thread-safe and non-blocking.
	 */
	public static long getCachedBalance(final UUID uuid) {
		return balances.getOrDefault(uuid, -1L);
	}

	/**
	 * @return the cached richest players, at most {@link #TOP_SIZE}. Thread-safe and non-blocking.
	 */
	public static List<CoinAccount> getTop() {
		return top;
	}

	/**
	 * @return the account with this name (case-insensitive), or null if the player never joined the network
	 */
	public static CompletableFuture<CoinAccount> find(final String name) {
		final JsonObject data = new JsonObject();

		data.addProperty("name", name);

		return ProxyLink.request(Protocol.COINS_FIND, data).thenApply(reply -> Json.hasObject(reply, "account") ? parseAccount(Json.getObject(reply, "account")) : null);
	}

	public static CompletableFuture<List<CoinTransaction>> getHistory(final UUID uuid, final int limit, final int offset) {
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("limit", limit);
		data.addProperty("offset", offset);

		return ProxyLink.request(Protocol.COINS_HISTORY, data).thenApply(reply -> {
			final List<CoinTransaction> history = new ArrayList<>();

			for (final JsonElement element : Json.getArray(reply, "entries"))
				if (element.isJsonObject()) {
					final JsonObject entry = element.getAsJsonObject();

					history.add(new CoinTransaction(Json.getString(entry, "type", ""), Json.getLong(entry, "amount", 0), Json.getLong(entry, "after", 0),
							Json.getString(entry, "actor", ""), Json.getString(entry, "server", ""), Json.getString(entry, "reason", null), Json.getLong(entry, "at", 0)));
				}

			return history;
		});
	}

	/* ------------------------------------------------------------------------------- */
	/* Writing */
	/* ------------------------------------------------------------------------------- */

	/**
	 * @param type  GIVE, TAKE or PLUGIN (other plugins use PLUGIN), the proxy refuses the other types
	 * @param actor UUID of the player causing the change, "CONSOLE" or your plugin name (at most 36 characters)
	 */
	public static CompletableFuture<CoinResult> give(final UUID uuid, final long amount, final TransactionType type, final String actor, final String reason) {
		checkAmount(amount);

		return change(uuid, amount, type, actor, reason);
	}

	/**
	 * Fails with {@link CoinResult.Status#INSUFFICIENT_FUNDS} instead of going below zero.
	 *
	 * @param type GIVE, TAKE or PLUGIN (other plugins use PLUGIN), the proxy refuses the other types
	 */
	public static CompletableFuture<CoinResult> take(final UUID uuid, final long amount, final TransactionType type, final String actor, final String reason) {
		checkAmount(amount);

		return change(uuid, -amount, type, actor, reason);
	}

	public static CompletableFuture<CoinResult> set(final UUID uuid, final long balance, final String actor, final String reason) {
		if (balance < 0 || balance > MAX_AMOUNT)
			throw new IllegalArgumentException("Balance must be between 0 and " + MAX_AMOUNT + ", got " + balance);

		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("balance", balance);
		data.addProperty("actor", actor);

		if (reason != null)
			data.addProperty("reason", reason);

		return track(uuid, ProxyLink.request(Protocol.COINS_SET, data));
	}

	/**
	 * @return the sender's result, {@link CoinResult.Status#NOT_FOUND} if the receiver has no account
	 */
	public static CompletableFuture<CoinResult> pay(final UUID from, final UUID to, final long amount) {
		checkAmount(amount);

		if (from.equals(to))
			throw new IllegalArgumentException("Cannot pay to self");

		final JsonObject data = new JsonObject();

		data.addProperty("from", from.toString());
		data.addProperty("to", to.toString());
		data.addProperty("amount", amount);

		// The receiver's new balance is pushed by the proxy to whichever server they are on
		return track(from, ProxyLink.request(Protocol.COINS_PAY, data));
	}

	private static CompletableFuture<CoinResult> change(final UUID uuid, final long amount, final TransactionType type, final String actor, final String reason) {
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("amount", amount);
		data.addProperty("type", type.name());
		data.addProperty("actor", actor);

		if (reason != null)
			data.addProperty("reason", reason);

		return track(uuid, ProxyLink.request(Protocol.COINS_CHANGE, data));
	}

	/**
	 * Parses the result and updates the cache of a player on this server.
	 */
	private static CompletableFuture<CoinResult> track(final UUID uuid, final CompletableFuture<JsonObject> reply) {
		return reply.thenApply(data -> {
			final CoinResult result = parseResult(data);

			if (result.isSuccess() && balances.containsKey(uuid))
				balances.put(uuid, result.getBalance());

			return result;
		});
	}

	private static CoinResult parseResult(final JsonObject data) {
		switch (Json.getString(data, "status", "")) {
			case "SUCCESS":
				return CoinResult.success(Json.getLong(data, "balance", 0));
			case "INSUFFICIENT_FUNDS":
				return CoinResult.INSUFFICIENT_FUNDS;
			case "NOT_FOUND":
				return CoinResult.NOT_FOUND;
			default:
				throw new ProxyException(Protocol.ERROR_BAD_REQUEST, "Unknown KingdomCoins status in the reply of the proxy");
		}
	}

	private static void checkAmount(final long amount) {
		if (amount <= 0 || amount > MAX_AMOUNT)
			throw new IllegalArgumentException("Amount must be between 1 and " + MAX_AMOUNT + ", got " + amount);
	}

	/* ------------------------------------------------------------------------------- */
	/* Formatting */
	/* ------------------------------------------------------------------------------- */

	/**
	 * @return the amount with space-separated thousands, e.g. "1 250 000"
	 */
	public static String format(final long amount) {
		final DecimalFormatSymbols symbols = new DecimalFormatSymbols();

		symbols.setGroupingSeparator(' ');
		return new DecimalFormat("#,##0", symbols).format(amount);
	}
}
