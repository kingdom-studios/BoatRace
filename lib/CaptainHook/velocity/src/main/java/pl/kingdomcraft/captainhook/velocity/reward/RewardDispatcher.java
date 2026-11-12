package pl.kingdomcraft.captainhook.velocity.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.database.Database;

/**
 * Delivers ch_reward_queue to the players: every rewards.dispatch-interval-seconds it looks up undelivered rows of
 * players who are on a server, sends each as reward.dispatch to that server, and marks the row delivered when the
 * server answers with reward.ack.
 *
 * A reward with a required server (require-server in the configuration) is only sent while the player is on that
 * server; elsewhere (e.g. in the hub) it simply stays queued.
 *
 * A dispatch without an ack is sent again after rewards.retry-after-seconds. The Paper server remembers what it
 * ran and only acknowledges a repeated dispatch, so a lost ack does not run the commands twice (unless that
 * server restarted in between).
 */
public final class RewardDispatcher {

	/**
	 * Rows per tick, also the most a single player can receive per tick.
	 */
	private static final int BATCH = 100;

	private final Database database;
	private final Network network;
	private final Logger logger;
	private final long retryMillis;

	/**
	 * Row ID to where and when it was sent, until acknowledged.
	 */
	private final Map<Long, InFlight> inFlight = new ConcurrentHashMap<>();
	private final AtomicBoolean ticking = new AtomicBoolean();

	private record InFlight(UUID uuid, String server, long sentAt) {
	}

	public RewardDispatcher(final Database database, final Network network, final Logger logger, final int retrySeconds) {
		this.database = database;
		this.network = network;
		this.logger = logger;
		this.retryMillis = retrySeconds * 1000L;
	}

	/**
	 * One round: loads and sends what is waiting for online players. Called periodically.
	 */
	public void tick() {
		final List<UUID> present = new ArrayList<>();

		for (final UUID uuid : this.network.onlinePlayers())
			if (this.network.currentServer(uuid) != null)
				present.add(uuid);

		if (present.isEmpty() || !this.ticking.compareAndSet(false, true))
			return;

		this.database.submit(connection -> RewardQueue.pending(connection, present, BATCH)).whenComplete((rewards, error) -> {
			this.ticking.set(false);

			if (error != null) {
				this.logger.warn("Could not read the reward queue: {}", error.toString());

				return;
			}

			rewards.forEach(this::dispatch);
		});
	}

	private void dispatch(final RewardQueue.PendingReward reward) {
		final long now = System.currentTimeMillis();
		final InFlight sent = this.inFlight.get(reward.id());

		if (sent != null && now - sent.sentAt() < this.retryMillis)
			return;

		final String server = this.network.currentServer(reward.uuid());

		// A reward for another server waits in the queue until the player is there
		if (server == null || !reward.allowedOn(server))
			return;

		if (reward.commands().isEmpty() || reward.commands().size() > Protocol.MAX_REWARD_COMMANDS
				|| reward.commands().stream().anyMatch(command -> command.length() > Protocol.MAX_REWARD_COMMAND_LENGTH)) {
			this.logger.warn("Reward #{} ({}) has no or too many/too long commands and is not sent; fix or delete the row in ch_reward_queue.", reward.id(), reward.source());
			this.inFlight.put(reward.id(), new InFlight(reward.uuid(), server, now + 3_600_000L)); // do not warn every tick

			return;
		}

		if (!reward.trusted()) {
			this.logger.warn("Reward #{} ({}) is NOT sent: its commands are not the ones configured for that source (changed config, or a forged row). "
					+ "Check the row in ch_reward_queue and deliver it by hand if it is genuine.", reward.id(), reward.source());
			this.inFlight.put(reward.id(), new InFlight(reward.uuid(), server, now + 3_600_000L));

			return;
		}

		final JsonArray commands = new JsonArray();

		reward.commands().forEach(commands::add);

		final JsonObject data = new JsonObject();

		data.addProperty("id", reward.id());
		data.addProperty("uuid", reward.uuid().toString());
		data.addProperty("source", reward.source());
		data.add("commands", commands);

		if (reward.message() != null)
			data.addProperty("message", reward.message());

		if (this.network.notify(reward.uuid(), Protocol.REWARD_DISPATCH, data))
			this.inFlight.put(reward.id(), new InFlight(reward.uuid(), server, now));
	}

	/**
	 * Handles reward.ack from a Paper server.
	 *
	 * @param velocityServer the server the ack came from, must be the one the reward was sent to
	 * @param serverId       its ID, stored as delivered_server
	 */
	public CompletableFuture<Boolean> acknowledge(final String velocityServer, final String serverId, final JsonObject data) {
		final long id = Json.getLong(data, "id", 0);
		final InFlight sent = this.inFlight.get(id);

		if (sent == null || !sent.server().equals(velocityServer)) {
			this.logger.warn("Ignoring reward.ack for #{} from {}: it was not sent there.", id, velocityServer);

			return CompletableFuture.completedFuture(false);
		}

		if (!Json.getBoolean(data, "executed", false)) {
			// Not run (e.g. the player left that server): sent again on the next tick wherever the player is
			this.inFlight.remove(id);

			return CompletableFuture.completedFuture(false);
		}

		final int failed = Json.getInt(data, "failed", 0);

		if (failed > 0)
			this.logger.warn("Reward #{}: {} command(s) failed on {}, see its console. The reward counts as delivered.", id, failed, velocityServer);

		return this.database.submit(connection -> RewardQueue.markDelivered(connection, id, sent.uuid(), serverId)).whenComplete((marked, error) -> {
			if (error != null)
				this.logger.warn("Could not mark reward #{} delivered (it may run again): {}", id, error.toString());
			else
				this.inFlight.remove(id);
		});
	}

	/**
	 * @return rewards sent and waiting for their ack
	 */
	public int getInFlightCount() {
		return this.inFlight.size();
	}
}
