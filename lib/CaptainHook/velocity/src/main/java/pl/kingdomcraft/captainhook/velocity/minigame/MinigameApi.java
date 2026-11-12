package pl.kingdomcraft.captainhook.velocity.minigame;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.ServiceException;
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.mission.Missions;

/**
 * The minigame API of the network: async stats for any minigame and cross-server pending joins, both stored
 * in the ch_minigame_* tables.
 *
 * Stats: a minigame reports changes ("add 1 to wins", "best time is at most 83"), the proxy applies each with
 * one atomic statement. Games and stats are just names, so a new minigame needs no change here.
 *
 * Pending joins: a server (usually the hub) queues "this player is joining arena X of game Y on server Z", the
 * proxy moves the player, and server Z receives the join as soon as the player has arrived.
 */
public final class MinigameApi {

	/**
	 * Names of games and stats: they are stored as keys and used in URLs of the website.
	 */
	public static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

	/**
	 * Games may also use hyphens ("house-of-terror"); stats may not.
	 */
	public static final Pattern GAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

	public static final int MAX_TOP = 100;
	public static final int MAX_TTL_SECONDS = 600;
	private static final int MAX_ARENA_LENGTH = 64;
	private static final int MAX_DATA_LENGTH = 4096;

	private final Database database;
	private final Achievements achievements;
	private final Network network;
	private final Logger logger;

	private volatile int defaultTtlSeconds;
	private volatile Missions missions;

	public MinigameApi(final Database database, final Achievements achievements, final Network network, final Logger logger, final int defaultTtlSeconds) {
		this.database = database;
		this.achievements = achievements;
		this.network = network;
		this.logger = logger;
		this.defaultTtlSeconds = defaultTtlSeconds;
	}

	/**
	 * Lets counters of any minigame feed MINIGAME_STAT missions.
	 */
	public void setMissions(final Missions missions) {
		this.missions = missions;
	}

	public void setDefaultTtlSeconds(final int defaultTtlSeconds) {
		this.defaultTtlSeconds = defaultTtlSeconds;
	}

	/* ------------------------------------------------------------------------------- */
	/* Stats */
	/* ------------------------------------------------------------------------------- */

	/**
	 * One reported change of a player's stat.
	 */
	public record StatChange(UUID uuid, String stat, StatOperation operation, long value) {
	}

	/**
	 * Applies the changes in one transaction, then updates achievements that follow this minigame.
	 *
	 * @return the number of applied changes; fails with SQLException if they could not be saved
	 */
	public CompletableFuture<Integer> recordStats(final String game, final List<StatChange> changes) {
		checkGame(game);

		final Set<UUID> uuids = new LinkedHashSet<>();

		for (final StatChange change : changes) {
			checkKey(change.stat(), "stat");
			uuids.add(change.uuid());
		}

		final boolean sqlite = this.database.isSqlite();
		final CompletableFuture<Integer> saved = this.database.submit(connection -> {
			for (final StatChange change : changes)
				MinigameStore.applyStat(connection, game, change.uuid(), change.stat(), change.operation(), change.value(), sqlite);

			return changes.size();
		});

		// Saved changes feed MINIGAME_STAT missions: counters (add) by their amount, any operation for missions with min-value
		final CompletableFuture<Integer> counted = saved.thenApply(count -> {
			final Missions current = this.missions;

			if (current != null)
				for (final StatChange change : changes)
					current.recordMinigameStat(change.uuid(), game, change.stat(), change.operation() == StatOperation.ADD, change.value());

			return count;
		});

		// A separate transaction, queued right behind the save: a failing achievement must not cost the stats
		if (this.achievements.followsMinigame(game))
			this.database.submit(connection -> this.achievements.evaluateMinigameStats(connection, game, uuids))
					.whenComplete((unlocks, error) -> {
						if (error != null)
							this.logger.warn("Could not update achievements after {} stats: {}", game, error.toString());
						else
							this.achievements.announce(unlocks);
					});

		return counted;
	}

	public CompletableFuture<Map<String, Long>> getStats(final String game, final UUID uuid) {
		checkGame(game);

		return this.database.submit(connection -> MinigameStore.getStats(connection, game, uuid));
	}

	/**
	 * @param ascending true for stats where lower is better (times)
	 */
	public CompletableFuture<List<MinigameStore.TopEntry>> getTop(final String game, final String stat, final int limit, final boolean ascending) {
		checkGame(game);
		checkKey(stat, "stat");

		final int capped = Math.max(1, Math.min(limit, MAX_TOP));

		return this.database.submit(connection -> MinigameStore.getTop(connection, game, stat, capped, ascending));
	}

	/* ------------------------------------------------------------------------------- */
	/* Pending joins */
	/* ------------------------------------------------------------------------------- */

	/**
	 * @param connecting true if the proxy started moving the player to the target server
	 * @param delivered  true if the player was already there, so the join was handed over right away
	 */
	public record JoinResult(boolean connecting, boolean delivered) {
	}

	/**
	 * Queues a join and, if asked, sends the player to the target server.
	 *
	 * Fails with {@link ServiceException}: unknown_server if the proxy has no such server, player_offline if
	 * the player should be connected but is not on the network.
	 *
	 * @param server     Velocity name of the server running the minigame
	 * @param arena      which arena to join, null if the minigame decides
	 * @param data       free text for the minigame plugin, may be null
	 * @param ttlSeconds how long the join waits for the player, 0 for the configured default
	 * @param connect    true to also move the player to the server
	 */
	public CompletableFuture<JoinResult> queueJoin(final UUID uuid, final String game, final String server, final String arena, final String data,
			final int ttlSeconds, final boolean connect) {
		checkGame(game);

		if (arena != null && arena.length() > MAX_ARENA_LENGTH)
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "arena must be at most " + MAX_ARENA_LENGTH + " characters");

		if (data != null && data.length() > MAX_DATA_LENGTH)
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "data must be at most " + MAX_DATA_LENGTH + " characters");

		if (server == null || !this.network.serverExists(server))
			throw new ServiceException(Protocol.ERROR_UNKNOWN_SERVER, "The proxy has no server \"" + server + "\"");

		if (connect && !this.network.onlinePlayers().contains(uuid))
			throw new ServiceException(Protocol.ERROR_PLAYER_OFFLINE, "The player is not on the network");

		final long now = System.currentTimeMillis() / 1000;
		final int ttl = Math.max(1, Math.min(ttlSeconds > 0 ? ttlSeconds : this.defaultTtlSeconds, MAX_TTL_SECONDS));
		final PendingJoin join = new PendingJoin(uuid, game, server, arena, data, now, now + ttl);
		final boolean sqlite = this.database.isSqlite();

		return this.database.submit(connection -> {
			MinigameStore.putJoin(connection, join, sqlite);

			// Already there: nobody will report the player ready, so hand the join over now
			return server.equalsIgnoreCase(this.network.currentServer(uuid)) ? MinigameStore.takeJoin(connection, uuid, server) : null;

		}).thenApply(taken -> {
			if (taken != null) {
				this.network.notify(uuid, Protocol.MINIGAME_JOIN, taken.toJson());

				return new JoinResult(false, true);
			}

			return new JoinResult(connect && this.network.connect(uuid, server), false);
		});
	}

	/**
	 * Hands over the join waiting for a player who just arrived on the server.
	 *
	 * @param server Velocity name of the server the player is on
	 * @return the join, or null if nothing is waiting for the player on this server
	 */
	public CompletableFuture<PendingJoin> takeJoin(final UUID uuid, final String server) {
		return this.database.submit(connection -> MinigameStore.takeJoin(connection, uuid, server));
	}

	/**
	 * @return true if the player had a pending join
	 */
	public CompletableFuture<Boolean> cancelJoin(final UUID uuid) {
		return this.database.submit(connection -> MinigameStore.deleteJoin(connection, uuid));
	}

	/**
	 * Removes joins nobody collected (the player never arrived). Called periodically.
	 */
	public void purgeExpiredJoins() {
		this.database.submit(MinigameStore::purgeExpiredJoins).whenComplete((removed, error) -> {
			if (error != null)
				this.logger.warn("Could not remove expired minigame joins: {}", error.toString());
		});
	}

	private static void checkGame(final String game) {
		if (game == null || !GAME_PATTERN.matcher(game).matches())
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "game must match " + GAME_PATTERN.pattern() + ", got \"" + game + "\"");
	}

	private static void checkKey(final String key, final String what) {
		if (key == null || !KEY_PATTERN.matcher(key).matches())
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, what + " must match " + KEY_PATTERN.pattern() + ", got \"" + key + "\"");
	}
}
