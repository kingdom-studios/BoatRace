package pl.kingdomcraft.captainhook.velocity.coins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.database.Database;

/**
 * KingdomCoins - the network-wide premium currency.
 *
 * Every write goes to the database (never to a cache) and returns a future completed on the database thread,
 * failing with SQLException when the database is unavailable. Balances of online players and the top list are
 * cached and pushed to the Paper servers whenever they change, and reloaded periodically so changes made
 * directly in the database (e.g. by the web shop) show up within kingdomcoins.refresh-interval-seconds.
 */
public final class KingdomCoins {

	public static final int TOP_SIZE = 10;
	public static final long MAX_AMOUNT = 1_000_000_000_000L;

	private final Database database;
	private final Network network;
	private final Logger logger;

	/**
	 * Last known balances of online players, only used to tell which balances changed.
	 */
	private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
	private volatile List<CoinAccount> top = List.of();

	public KingdomCoins(final Database database, final Network network, final Logger logger) {
		this.database = database;
		this.network = network;
		this.logger = logger;
	}

	/* ------------------------------------------------------------------------------- */
	/* Lifecycle */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Creates the account if needed and remembers the balance. Called when a player joins the network and
	 * again when a Paper server reports the player ready.
	 */
	public CompletableFuture<Long> load(final UUID uuid, final String name) {
		return this.database.submit(connection -> CoinStore.load(connection, uuid, name, this.database.isSqlite()))
				.whenComplete((balance, error) -> {
					if (error == null)
						this.balances.put(uuid, balance);
				});
	}

	public void unload(final UUID uuid) {
		this.balances.remove(uuid);
	}

	/**
	 * Reloads balances of online players and the top list in one database round and pushes what changed.
	 */
	public void refresh() {
		final List<UUID> online = new ArrayList<>(this.network.onlinePlayers());

		this.database.submit(connection -> new Refreshed(CoinStore.getTop(connection, TOP_SIZE), CoinStore.getBalances(connection, online)))
				.whenComplete((fresh, error) -> {
					if (error != null) {
						this.logger.warn("Could not refresh KingdomCoins: {}", error.toString());

						return;
					}

					this.balances.keySet().retainAll(online);
					fresh.balances.forEach(this::onBalanceChanged);

					if (!fresh.top.equals(this.top)) {
						this.top = List.copyOf(fresh.top);
						this.network.broadcast(Protocol.COINS_TOP, this.topToJson());
					}
				});
	}

	private record Refreshed(List<CoinAccount> top, Map<UUID, Long> balances) {
	}

	/**
	 * Remembers the new balance and tells the player's server, unless nothing changed or the player is offline.
	 */
	public void onBalanceChanged(final UUID uuid, final long balance) {
		if (!this.network.onlinePlayers().contains(uuid))
			return;

		final Long previous = this.balances.put(uuid, balance);

		if (previous == null || previous != balance) {
			final JsonObject data = new JsonObject();

			data.addProperty("uuid", uuid.toString());
			data.addProperty("balance", balance);

			this.network.notify(uuid, Protocol.COINS_BALANCE, data);
		}
	}

	/* ------------------------------------------------------------------------------- */
	/* Reading */
	/* ------------------------------------------------------------------------------- */

	/**
	 * @return the cached richest players, at most {@link #TOP_SIZE}
	 */
	public List<CoinAccount> getTop() {
		return this.top;
	}

	/**
	 * @return the payload of a coins.top notification
	 */
	public JsonObject topToJson() {
		final JsonArray entries = new JsonArray();

		for (final CoinAccount account : this.top)
			entries.add(toJson(account));

		final JsonObject data = new JsonObject();

		data.add("entries", entries);
		return data;
	}

	public static JsonObject toJson(final CoinAccount account) {
		final JsonObject json = new JsonObject();

		json.addProperty("uuid", account.uuid().toString());
		json.addProperty("name", account.name());
		json.addProperty("balance", account.balance());

		return json;
	}

	/**
	 * @return the account with this name (case-insensitive), or null if the player never joined the network
	 */
	public CompletableFuture<CoinAccount> find(final String name) {
		return this.database.submit(connection -> CoinStore.findByName(connection, name));
	}

	public CompletableFuture<List<CoinTransaction>> getHistory(final UUID uuid, final int limit, final int offset) {
		return this.database.submit(connection -> CoinStore.getHistory(connection, uuid, limit, offset));
	}

	/* ------------------------------------------------------------------------------- */
	/* Writing */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Adds the amount, or takes it when negative (failing with INSUFFICIENT_FUNDS instead of going below zero).
	 *
	 * @param actor  UUID of the player causing the change, "CONSOLE" or a plugin name
	 * @param server ID of the server the change comes from
	 */
	public CompletableFuture<CoinResult> change(final UUID uuid, final long amount, final TransactionType type, final String actor, final String server, final String reason) {
		if (amount == 0 || Math.abs(amount) > MAX_AMOUNT)
			throw new IllegalArgumentException("Amount must be between 1 and " + MAX_AMOUNT + ", got " + amount);

		return this.track(uuid, this.database.submit(connection -> CoinStore.change(connection, uuid, amount, type, actor, server, reason)));
	}

	public CompletableFuture<CoinResult> set(final UUID uuid, final long balance, final String actor, final String server, final String reason) {
		if (balance < 0 || balance > MAX_AMOUNT)
			throw new IllegalArgumentException("Balance must be between 0 and " + MAX_AMOUNT + ", got " + balance);

		return this.track(uuid, this.database.submit(connection -> CoinStore.set(connection, uuid, balance, actor, server, reason)));
	}

	/**
	 * @return the sender's result, NOT_FOUND if the receiver has no account
	 */
	public CompletableFuture<CoinResult> pay(final UUID from, final UUID to, final long amount, final String server) {
		if (amount <= 0 || amount > MAX_AMOUNT)
			throw new IllegalArgumentException("Amount must be between 1 and " + MAX_AMOUNT + ", got " + amount);

		if (from.equals(to))
			throw new IllegalArgumentException("Cannot pay to self");

		return this.database.submit(connection -> {
			final CoinResult result = CoinStore.transfer(connection, from, to, amount, server);

			return new Paid(result, result.isSuccess() ? CoinStore.getBalance(connection, to) : null);

		}).thenApply(paid -> {
			if (paid.result.isSuccess()) {
				this.onBalanceChanged(from, paid.result.balance());

				if (paid.receiverBalance != null)
					this.onBalanceChanged(to, paid.receiverBalance);
			}

			return paid.result;
		});
	}

	private record Paid(CoinResult result, Long receiverBalance) {
	}

	/**
	 * Pushes the new balance of an online player once the change is committed.
	 */
	private CompletableFuture<CoinResult> track(final UUID uuid, final CompletableFuture<CoinResult> future) {
		return future.thenApply(result -> {
			if (result.isSuccess())
				this.onBalanceChanged(uuid, result.balance());

			return result;
		});
	}
}
