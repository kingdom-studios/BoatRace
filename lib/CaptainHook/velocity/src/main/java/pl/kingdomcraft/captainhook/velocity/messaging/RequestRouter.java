package pl.kingdomcraft.captainhook.velocity.messaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ServerConnection;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Message;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.ServiceException;
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements;
import pl.kingdomcraft.captainhook.velocity.coins.CoinAccount;
import pl.kingdomcraft.captainhook.velocity.coins.CoinResult;
import pl.kingdomcraft.captainhook.velocity.coins.CoinTransaction;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.coins.TransactionType;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi;
import pl.kingdomcraft.captainhook.velocity.mission.MissionAdmin;
import pl.kingdomcraft.captainhook.velocity.mission.MissionFilter;
import pl.kingdomcraft.captainhook.velocity.mission.Missions;
import pl.kingdomcraft.captainhook.velocity.mission.Objective;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameStore;
import pl.kingdomcraft.captainhook.velocity.minigame.StatOperation;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.reward.RewardDispatcher;
import pl.kingdomcraft.captainhook.velocity.stats.PlayerSnapshot;
import pl.kingdomcraft.captainhook.velocity.stats.StatsService;

/**
 * Turns the messages of the Paper servers into calls of the services and their results into replies.
 * Message types and payloads are specified in docs/NETWORK_PROTOCOL.md.
 */
public final class RequestRouter {

	private static final int MAX_HISTORY_PAGE = 50;

	/**
	 * A request with everything known about its sender.
	 *
	 * @param velocityServer name of the sending server on the proxy
	 * @param serverId       ID the sending server uses for itself (api.server-id)
	 */
	private record Request(String velocityServer, String serverId, JsonObject data) {
	}

	@FunctionalInterface
	private interface Handler {

		/**
		 * @return the payload of the reply; may throw or fail with {@link ServiceException} or IllegalArgumentException
		 */
		CompletableFuture<JsonObject> handle(Request request);
	}

	private final Map<String, Handler> handlers = new HashMap<>();

	private final BackendMessenger messenger;
	private final Database database;
	private final KingdomCoins coins;
	private final StatsService stats;
	private final SeasonPass seasonPass;
	private final Achievements achievements;
	private final MinigameApi minigames;
	private final RewardDispatcher rewards;
	private final Missions missions;
	private final MissionAdmin missionAdmin;
	private final Logger logger;
	private final String pluginVersion;

	public RequestRouter(final BackendMessenger messenger, final Database database, final KingdomCoins coins, final StatsService stats, final SeasonPass seasonPass,
			final Achievements achievements, final MinigameApi minigames, final RewardDispatcher rewards, final Missions missions, final MissionAdmin missionAdmin,
			final Logger logger, final String pluginVersion) {
		this.messenger = messenger;
		this.database = database;
		this.coins = coins;
		this.stats = stats;
		this.seasonPass = seasonPass;
		this.achievements = achievements;
		this.minigames = minigames;
		this.rewards = rewards;
		this.missions = missions;
		this.missionAdmin = missionAdmin;
		this.logger = logger;
		this.pluginVersion = pluginVersion;

		this.handlers.put(Protocol.STATUS, this::status);
		this.handlers.put(Protocol.PLAYER_READY, this::playerReady);
		this.handlers.put(Protocol.STATS_BATCH, this::statsBatch);
		this.handlers.put(Protocol.COINS_FIND, this::coinsFind);
		this.handlers.put(Protocol.COINS_HISTORY, this::coinsHistory);
		this.handlers.put(Protocol.COINS_CHANGE, this::coinsChange);
		this.handlers.put(Protocol.COINS_SET, this::coinsSet);
		this.handlers.put(Protocol.COINS_PAY, this::coinsPay);
		this.handlers.put(Protocol.PASS_ADD_XP, this::passAddXp);
		this.handlers.put(Protocol.PASS_GET, this::passGet);
		this.handlers.put(Protocol.ACHIEVEMENT_PROGRESS, this::achievementProgress);
		this.handlers.put(Protocol.ACHIEVEMENT_LIST, this::achievementList);
		this.handlers.put(Protocol.MINIGAME_STATS, this::minigameStats);
		this.handlers.put(Protocol.MINIGAME_STATS_GET, this::minigameStatsGet);
		this.handlers.put(Protocol.MINIGAME_TOP, this::minigameTop);
		this.handlers.put(Protocol.MINIGAME_JOIN_QUEUE, this::minigameJoinQueue);
		this.handlers.put(Protocol.MINIGAME_JOIN_CANCEL, this::minigameJoinCancel);
		this.handlers.put(Protocol.REWARD_ACK, this::rewardAck);
		this.handlers.put(Protocol.MISSION_PROGRESS, this::missionProgress);
		this.handlers.put(Protocol.MISSION_LIST, this::missionList);
		this.handlers.put(Protocol.MISSION_ADMIN, this::missionAdminRequest);
	}

	/**
	 * Handles a message from a Paper server and, if it is a request, answers it once the result is known.
	 */
	void handle(final ServerConnection connection, final String velocityServer, final String serverId, final Message message) {
		final Handler handler = this.handlers.get(message.getType());

		if (handler == null) {
			// A newer server may send types this proxy does not know yet: answer requests, ignore the rest
			if (message.isRequest())
				this.messenger.send(connection, Message.error(message.getId(), Protocol.ERROR_UNKNOWN_TYPE, "Unknown message type " + message.getType()));

			return;
		}

		CompletableFuture<JsonObject> result;

		try {
			result = handler.handle(new Request(velocityServer, serverId, message.getData()));

		} catch (final RuntimeException ex) {
			result = CompletableFuture.failedFuture(ex);
		}

		result.whenComplete((data, error) -> {
			if (error != null)
				this.logFailure(message, velocityServer, unwrap(error));

			if (message.isRequest())
				this.messenger.send(connection, error == null ? Message.reply(message.getId(), data) : toError(message.getId(), unwrap(error)));
		});
	}

	private static Throwable unwrap(final Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}

	private static Message toError(final long requestId, final Throwable error) {
		if (error instanceof ServiceException)
			return Message.error(requestId, ((ServiceException) error).getCode(), error.getMessage());

		if (error instanceof IllegalArgumentException)
			return Message.error(requestId, Protocol.ERROR_BAD_REQUEST, error.getMessage());

		// No details: SQL errors are for the proxy log, not for the servers
		return Message.error(requestId, Protocol.ERROR_DATABASE, null);
	}

	private void logFailure(final Message message, final String velocityServer, final Throwable error) {
		if (error instanceof ServiceException && Protocol.ERROR_NO_SEASON.equals(((ServiceException) error).getCode()))
			return; // routine: plugins keep reporting XP between seasons

		if (error instanceof ServiceException || error instanceof IllegalArgumentException)
			this.logger.warn("Rejected {} from {}: {}", message, velocityServer, error.getMessage());
		else
			this.logger.error("Could not handle {} from {}", message, velocityServer, error);
	}

	/* ------------------------------------------------------------------------------- */
	/* General */
	/* ------------------------------------------------------------------------------- */

	private CompletableFuture<JsonObject> status(final Request request) {
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();
		final JsonObject reply = new JsonObject();

		reply.addProperty("protocol", Protocol.VERSION);
		reply.addProperty("plugin", this.pluginVersion);
		reply.addProperty("database", this.database.isMySql() ? "MySQL" : "SQLite");
		reply.addProperty("server", request.velocityServer());
		reply.addProperty("achievements", this.achievements.getDefinitions().size());

		if (season != null)
			reply.addProperty("season", season.id());

		return CompletableFuture.completedFuture(reply);
	}

	/**
	 * A player arrived on a Paper server and can carry messages: send everything the server caches about them.
	 */
	private CompletableFuture<JsonObject> playerReady(final Request request) {
		final UUID uuid = requireUuid(request.data(), "uuid");
		final String name = requireName(request.data(), "name");
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();
		final JsonObject reply = new JsonObject();

		// Three small transactions, run in this order on the database thread
		final CompletableFuture<Long> balance = this.coins.load(uuid, name);
		final CompletableFuture<SeasonPass.Progress> pass = season != null ? this.seasonPass.getProgress(uuid) : CompletableFuture.completedFuture(null);

		return balance.thenCombine(pass, (coins, progress) -> {
			reply.addProperty("coins", coins);
			reply.add("top", this.coins.topToJson().get("entries"));

			if (progress != null)
				reply.add("pass", SeasonPass.toJson(progress.season(), progress.state()));

			return reply;

		}).thenCombine(this.minigames.takeJoin(uuid, request.velocityServer()), (ignored, join) -> {
			if (join != null)
				reply.add("join", join.toJson());

			// Rewards bound to this server (require-server) can go now, without waiting for the next round
			this.rewards.tick();
			return reply;
		});
	}

	private CompletableFuture<JsonObject> statsBatch(final Request request) {
		final List<PlayerSnapshot> snapshots = new ArrayList<>();

		for (final JsonElement element : Json.getArray(request.data(), "players"))
			if (element.isJsonObject()) {
				final PlayerSnapshot snapshot = PlayerSnapshot.fromJson(element.getAsJsonObject());

				if (snapshot != null)
					snapshots.add(snapshot);
			}

		if (snapshots.isEmpty())
			return CompletableFuture.completedFuture(counted("saved", 0));

		return this.stats.save(request.serverId(), request.velocityServer(), snapshots).thenApply(saved -> counted("saved", saved));
	}

	/* ------------------------------------------------------------------------------- */
	/* KingdomCoins */
	/* ------------------------------------------------------------------------------- */

	private CompletableFuture<JsonObject> coinsFind(final Request request) {
		return this.coins.find(requireName(request.data(), "name")).thenApply(account -> {
			final JsonObject reply = new JsonObject();

			if (account != null)
				reply.add("account", KingdomCoins.toJson(account));

			return reply;
		});
	}

	private CompletableFuture<JsonObject> coinsHistory(final Request request) {
		final UUID uuid = requireUuid(request.data(), "uuid");
		final int limit = Math.max(1, Math.min(Json.getInt(request.data(), "limit", 10), MAX_HISTORY_PAGE));
		final int offset = Math.max(0, Json.getInt(request.data(), "offset", 0));

		return this.coins.getHistory(uuid, limit, offset).thenApply(history -> {
			final JsonArray entries = new JsonArray();

			for (final CoinTransaction transaction : history) {
				final JsonObject entry = new JsonObject();

				entry.addProperty("type", transaction.type());
				entry.addProperty("amount", transaction.amount());
				entry.addProperty("after", transaction.balanceAfter());
				entry.addProperty("actor", transaction.actor());
				entry.addProperty("server", transaction.server());

				if (transaction.reason() != null)
					entry.addProperty("reason", transaction.reason());

				entry.addProperty("at", transaction.createdAt());
				entries.add(entry);
			}

			final JsonObject reply = new JsonObject();

			reply.add("entries", entries);
			return reply;
		});
	}

	private CompletableFuture<JsonObject> coinsChange(final Request request) {
		final UUID uuid = requireUuid(request.data(), "uuid");
		final long amount = Json.getLong(request.data(), "amount", 0);
		final TransactionType type = TransactionType.find(Json.getString(request.data(), "type", ""));

		// The other types are written by the proxy itself (payments, SET, rewards) or by the website
		if (type != TransactionType.GIVE && type != TransactionType.TAKE && type != TransactionType.PLUGIN)
			throw new IllegalArgumentException("type must be GIVE, TAKE or PLUGIN");

		return this.coins.change(uuid, amount, type, requireActor(request.data()), request.serverId(), Json.getString(request.data(), "reason", null))
				.thenApply(RequestRouter::toJson);
	}

	private CompletableFuture<JsonObject> coinsSet(final Request request) {
		final long balance = Json.getLong(request.data(), "balance", -1);

		return this.coins.set(requireUuid(request.data(), "uuid"), balance, requireActor(request.data()), request.serverId(), Json.getString(request.data(), "reason", null))
				.thenApply(RequestRouter::toJson);
	}

	private CompletableFuture<JsonObject> coinsPay(final Request request) {
		return this.coins.pay(requireUuid(request.data(), "from"), requireUuid(request.data(), "to"), Json.getLong(request.data(), "amount", 0), request.serverId())
				.thenApply(RequestRouter::toJson);
	}

	private static JsonObject toJson(final CoinResult result) {
		final JsonObject json = new JsonObject();

		json.addProperty("status", result.status().name());
		json.addProperty("balance", result.balance());

		return json;
	}

	/* ------------------------------------------------------------------------------- */
	/* Season pass and achievements */
	/* ------------------------------------------------------------------------------- */

	private CompletableFuture<JsonObject> passAddXp(final Request request) {
		return this.seasonPass.addXp(requireUuid(request.data(), "uuid"), Json.getLong(request.data(), "amount", 0)).thenApply(change -> {
			final JsonObject reply = SeasonPass.toJson(change.season(), change.state());

			reply.addProperty("gained", change.state().xp() - change.xpBefore());
			reply.addProperty("levelBefore", change.levelBefore());

			return reply;
		});
	}

	private CompletableFuture<JsonObject> passGet(final Request request) {
		return this.seasonPass.getProgress(requireUuid(request.data(), "uuid")).thenApply(SeasonPass.Progress::toJson);
	}

	private CompletableFuture<JsonObject> achievementProgress(final Request request) {
		final String id = Json.getString(request.data(), "id", "");

		return this.achievements.addProgress(requireUuid(request.data(), "uuid"), id, Json.getLong(request.data(), "amount", 0))
				.thenApply(Achievements.Progress::toJson);
	}

	private CompletableFuture<JsonObject> achievementList(final Request request) {
		return this.achievements.list(requireUuid(request.data(), "uuid")).thenApply(list -> {
			final JsonArray entries = new JsonArray();

			for (final Achievements.Progress progress : list)
				entries.add(progress.toJson());

			final JsonObject reply = new JsonObject();

			reply.add("achievements", entries);
			return reply;
		});
	}

	/* ------------------------------------------------------------------------------- */
	/* Minigames */
	/* ------------------------------------------------------------------------------- */

	private CompletableFuture<JsonObject> minigameStats(final Request request) {
		final String game = Json.getString(request.data(), "game", null);
		final List<MinigameApi.StatChange> changes = new ArrayList<>();

		for (final JsonElement element : Json.getArray(request.data(), "entries")) {
			if (!element.isJsonObject())
				throw new IllegalArgumentException("entries must be objects");

			final JsonObject entry = element.getAsJsonObject();
			final StatOperation operation = StatOperation.find(Json.getString(entry, "op", "add"));

			if (operation == null)
				throw new IllegalArgumentException("op must be add, set, max or min");

			changes.add(new MinigameApi.StatChange(requireUuid(entry, "uuid"), Json.getString(entry, "stat", null), operation, Json.getLong(entry, "value", 0)));
		}

		if (changes.isEmpty())
			return CompletableFuture.completedFuture(counted("saved", 0));

		return this.minigames.recordStats(game, changes).thenApply(saved -> counted("saved", saved));
	}

	private CompletableFuture<JsonObject> minigameStatsGet(final Request request) {
		return this.minigames.getStats(Json.getString(request.data(), "game", null), requireUuid(request.data(), "uuid")).thenApply(stats -> {
			final JsonObject values = new JsonObject();

			stats.forEach(values::addProperty);

			final JsonObject reply = new JsonObject();

			reply.add("stats", values);
			return reply;
		});
	}

	private CompletableFuture<JsonObject> minigameTop(final Request request) {
		final JsonObject data = request.data();

		return this.minigames.getTop(Json.getString(data, "game", null), Json.getString(data, "stat", null), Json.getInt(data, "limit", 10), Json.getBoolean(data, "asc", false))
				.thenApply(top -> {
					final JsonArray entries = new JsonArray();

					for (final MinigameStore.TopEntry line : top) {
						final JsonObject entry = new JsonObject();

						entry.addProperty("uuid", line.uuid().toString());
						entry.addProperty("name", line.name());
						entry.addProperty("value", line.value());
						entries.add(entry);
					}

					final JsonObject reply = new JsonObject();

					reply.add("entries", entries);
					return reply;
				});
	}

	private CompletableFuture<JsonObject> minigameJoinQueue(final Request request) {
		final JsonObject data = request.data();

		return this.minigames.queueJoin(requireUuid(data, "uuid"), Json.getString(data, "game", null), Json.getString(data, "server", null),
				Json.getString(data, "arena", null), Json.getString(data, "data", null), Json.getInt(data, "ttl", 0), Json.getBoolean(data, "connect", true))
				.thenApply(result -> {
					final JsonObject reply = new JsonObject();

					reply.addProperty("connecting", result.connecting());
					reply.addProperty("delivered", result.delivered());

					return reply;
				});
	}

	private CompletableFuture<JsonObject> minigameJoinCancel(final Request request) {
		return this.minigames.cancelJoin(requireUuid(request.data(), "uuid")).thenApply(cancelled -> {
			final JsonObject reply = new JsonObject();

			reply.addProperty("cancelled", cancelled);
			return reply;
		});
	}

	/* ------------------------------------------------------------------------------- */
	/* Missions */
	/* ------------------------------------------------------------------------------- */

	private static final int MAX_PROGRESS_ENTRIES = 200;
	private static final long MAX_PROGRESS_DELTA = 1_000_000;

	/**
	 * What players did on a Paper server: {"entries": [{"uuid", "objective", "delta", "world"?, "material"?, "entity"?}]}.
	 * Only the objectives servers can see are accepted; the proxy knows LOGIN, votes and minigame stats itself.
	 */
	private CompletableFuture<JsonObject> missionProgress(final Request request) {
		final JsonArray entries = Json.getArray(request.data(), "entries");

		if (entries.size() > MAX_PROGRESS_ENTRIES)
			throw new IllegalArgumentException("at most " + MAX_PROGRESS_ENTRIES + " entries");

		int accepted = 0;

		for (final JsonElement element : entries) {
			if (!element.isJsonObject())
				throw new IllegalArgumentException("entries must be objects");

			final JsonObject entry = element.getAsJsonObject();
			final Objective objective = Objective.find(Json.getString(entry, "objective", ""));

			if (objective == null || !objective.fromServers())
				throw new IllegalArgumentException("objective " + Json.getString(entry, "objective", "") + " cannot be reported by a server");

			final long delta = Json.getLong(entry, "delta", 0);

			if (delta < 1 || delta > MAX_PROGRESS_DELTA)
				throw new IllegalArgumentException("delta must be between 1 and " + MAX_PROGRESS_DELTA);

			this.missions.record(requireUuid(entry, "uuid"), objective, new MissionFilter.Event(request.velocityServer(), shortText(entry, "world"),
					shortText(entry, "material"), shortText(entry, "entity"), null, null), delta);
			accepted++;
		}

		return CompletableFuture.completedFuture(counted("accepted", accepted));
	}

	private static String shortText(final JsonObject entry, final String key) {
		final String value = Json.getString(entry, key, null);

		return value != null && value.length() <= 64 ? value : null;
	}

	/**
	 * The player's missions of the current periods, for /karnet.
	 */
	private CompletableFuture<JsonObject> missionList(final Request request) {
		return this.missions.listFor(requireUuid(request.data(), "uuid"));
	}

	/**
	 * Admin actions (/karnet admin …): {"action", "name"?, "arg"?, "amount"?}.
	 */
	private CompletableFuture<JsonObject> missionAdminRequest(final Request request) {
		final JsonObject data = request.data();

		return this.missionAdmin.run(Json.getString(data, "action", ""), Json.getString(data, "name", null), Json.getString(data, "arg", null), Json.getLong(data, "amount", 0));
	}

	/* ------------------------------------------------------------------------------- */
	/* Rewards */
	/* ------------------------------------------------------------------------------- */

	/**
	 * A notification: the Paper server ran (or could not run) a reward.dispatch.
	 */
	private CompletableFuture<JsonObject> rewardAck(final Request request) {
		return this.rewards.acknowledge(request.velocityServer(), request.serverId(), request.data()).thenApply(marked -> new JsonObject());
	}

	/* ------------------------------------------------------------------------------- */
	/* Reading payloads */
	/* ------------------------------------------------------------------------------- */

	private static JsonObject counted(final String key, final int count) {
		final JsonObject reply = new JsonObject();

		reply.addProperty(key, count);
		return reply;
	}

	private static UUID requireUuid(final JsonObject data, final String key) {
		final UUID uuid = Json.getUuid(data, key);

		if (uuid == null)
			throw new IllegalArgumentException(key + " must be a UUID");

		return uuid;
	}

	private static String requireName(final JsonObject data, final String key) {
		final String name = Json.getString(data, key, "");

		if (name.isEmpty() || name.length() > 16)
			throw new IllegalArgumentException(key + " must be a player name");

		return name;
	}

	private static String requireActor(final JsonObject data) {
		final String actor = Json.getString(data, "actor", "");

		if (actor.isEmpty() || actor.length() > 36)
			throw new IllegalArgumentException("actor must be 1 to 36 characters");

		return actor;
	}
}
