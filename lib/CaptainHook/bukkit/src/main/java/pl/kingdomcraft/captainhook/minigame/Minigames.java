package pl.kingdomcraft.captainhook.minigame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.event.MinigameJoinEvent;
import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;

/**
 * The minigame API as seen from this server: stats of any minigame and cross-server joins. The proxy stores
 * both in the ch_minigame_* tables; minigame plugins use it through CaptainHookAPI.
 */
public final class Minigames {

	/**
	 * Changes per minigame.stats message, small enough for one plugin message.
	 */
	private static final int STATS_PER_MESSAGE = 150;

	private Minigames() {
	}

	/**
	 * Listens for joins of players who are already on this server. Call on startup and after every reload.
	 */
	public static void start() {
		ProxyLink.on(Protocol.MINIGAME_JOIN, data -> {
			final UUID uuid = Json.getUuid(data, "uuid");
			final Player player = uuid != null ? Bukkit.getPlayer(uuid) : null;

			if (player != null)
				deliverJoin(player, data);
		});
	}

	/**
	 * Tells the minigame plugins that a player arrived (or already is) here to join a game.
	 */
	public static void deliverJoin(final Player player, final JsonObject join) {
		Common.callEvent(new MinigameJoinEvent(player, Json.getString(join, "game", ""), Json.getString(join, "arena", null), Json.getString(join, "data", null)));
	}

	/* ------------------------------------------------------------------------------- */
	/* Stats */
	/* ------------------------------------------------------------------------------- */

	public static CompletableFuture<Void> recordStats(final String game, final Collection<MinigameStat> changes) {
		final List<MinigameStat> list = new ArrayList<>(changes);
		CompletableFuture<Void> all = CompletableFuture.completedFuture(null);

		for (int from = 0; from < list.size(); from += STATS_PER_MESSAGE) {
			final JsonArray entries = new JsonArray();

			for (final MinigameStat change : list.subList(from, Math.min(from + STATS_PER_MESSAGE, list.size()))) {
				final JsonObject entry = new JsonObject();

				entry.addProperty("uuid", change.getUuid().toString());
				entry.addProperty("stat", change.getStat());
				entry.addProperty("op", change.getOperation().key());
				entry.addProperty("value", change.getValue());
				entries.add(entry);
			}

			final JsonObject data = new JsonObject();

			data.addProperty("game", game);
			data.add("entries", entries);

			// Stats reported while nobody can carry the message (the last player just left) wait for the next player
			all = all.thenCombine(ProxyLink.request(Protocol.MINIGAME_STATS, data, list.get(from).getUuid(), true), (first, second) -> null);
		}

		return all;
	}

	public static CompletableFuture<Map<String, Long>> getStats(final String game, final UUID uuid) {
		final JsonObject data = new JsonObject();

		data.addProperty("game", game);
		data.addProperty("uuid", uuid.toString());

		return ProxyLink.request(Protocol.MINIGAME_STATS_GET, data, uuid, false).thenApply(reply -> {
			final JsonObject values = Json.getObject(reply, "stats");
			final Map<String, Long> stats = new LinkedHashMap<>();

			for (final Map.Entry<String, JsonElement> entry : values.entrySet())
				stats.put(entry.getKey(), Json.getLong(values, entry.getKey(), 0));

			return stats;
		});
	}

	public static CompletableFuture<List<MinigameTopEntry>> getTop(final String game, final String stat, final int limit, final boolean ascending) {
		final JsonObject data = new JsonObject();

		data.addProperty("game", game);
		data.addProperty("stat", stat);
		data.addProperty("limit", limit);
		data.addProperty("asc", ascending);

		return ProxyLink.request(Protocol.MINIGAME_TOP, data).thenApply(reply -> {
			final List<MinigameTopEntry> top = new ArrayList<>();

			for (final JsonElement element : Json.getArray(reply, "entries"))
				if (element.isJsonObject()) {
					final JsonObject entry = element.getAsJsonObject();
					final UUID uuid = Json.getUuid(entry, "uuid");

					if (uuid != null)
						top.add(new MinigameTopEntry(uuid, Json.getString(entry, "name", ""), Json.getLong(entry, "value", 0)));
				}

			return top;
		});
	}

	/* ------------------------------------------------------------------------------- */
	/* Cross-server joins */
	/* ------------------------------------------------------------------------------- */

	public static CompletableFuture<Boolean> queueJoin(final UUID uuid, final String game, final String server, final String arena, final String joinData,
			final int ttlSeconds, final boolean connect) {
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("game", game);
		data.addProperty("server", server);

		if (arena != null)
			data.addProperty("arena", arena);

		if (joinData != null)
			data.addProperty("data", joinData);

		data.addProperty("ttl", ttlSeconds);
		data.addProperty("connect", connect);

		return ProxyLink.request(Protocol.MINIGAME_JOIN_QUEUE, data, uuid, false)
				.thenApply(reply -> Json.getBoolean(reply, "connecting", false) || Json.getBoolean(reply, "delivered", false));
	}

	public static CompletableFuture<Boolean> cancelJoin(final UUID uuid) {
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());

		return ProxyLink.request(Protocol.MINIGAME_JOIN_CANCEL, data, uuid, false).thenApply(reply -> Json.getBoolean(reply, "cancelled", false));
	}
}
