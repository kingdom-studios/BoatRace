package pl.kingdomcraft.captainhook.velocity.stats;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements;
import pl.kingdomcraft.captainhook.velocity.database.Database;

/**
 * Writes the stat snapshots the Paper servers send to the database, where the website reads them.
 *
 * The Paper servers know the numbers (they come from vanilla statistics), but the proxy knows who is online
 * where: it sees every switch and disconnect, also when a Paper server crashes or has nobody left to carry a
 * message. So the online flag is decided here.
 */
public final class StatsService {

	private final Database database;
	private final Achievements achievements;
	private final Network network;
	private final Logger logger;

	public StatsService(final Database database, final Achievements achievements, final Network network, final Logger logger) {
		this.database = database;
		this.achievements = achievements;
		this.network = network;
		this.logger = logger;
	}

	/**
	 * Saves a batch of snapshots of one server, then updates achievements that follow network stats.
	 *
	 * @param serverId       ID of the Paper server (api.server-id of its config), the server_id of the rows
	 * @param velocityServer name of that server on the proxy
	 * @return the number of saved snapshots; fails with SQLException if they could not be saved
	 */
	public CompletableFuture<Integer> save(final String serverId, final String velocityServer, final List<PlayerSnapshot> snapshots) {
		final List<PlayerSnapshot> checked = new ArrayList<>(snapshots.size());
		final Set<UUID> uuids = new LinkedHashSet<>();

		// A snapshot taken just before the player left may arrive after the proxy marked them offline
		for (final PlayerSnapshot snapshot : snapshots) {
			checked.add(snapshot.online() && !velocityServer.equals(this.network.currentServer(snapshot.uuid())) ? snapshot.offline() : snapshot);
			uuids.add(snapshot.uuid());
		}

		final boolean sqlite = this.database.isSqlite();
		final CompletableFuture<Integer> saved = this.database.submit(connection -> {
			StatsStore.save(connection, serverId, checked, sqlite);

			return checked.size();
		});

		// A separate transaction, queued right behind the save: a failing achievement must not cost the stats
		if (this.achievements.followsNetworkStats())
			this.database.submit(connection -> this.achievements.evaluateNetworkStats(connection, uuids))
					.whenComplete((unlocks, error) -> {
						if (error != null)
							this.logger.warn("Could not update achievements after a stats batch of {}: {}", serverId, error.toString());
						else
							this.achievements.announce(unlocks);
					});

		return saved;
	}

	/**
	 * Marks the player offline on the server they just left (switched away or disconnected).
	 */
	public void playerLeft(final String serverId, final UUID uuid) {
		final boolean sqlite = this.database.isSqlite();

		this.database.submit(connection -> {
			StatsStore.markOffline(connection, serverId, uuid, sqlite);

			return null;

		}).whenComplete((ignored, error) -> {
			if (error != null)
				this.logger.warn("Could not mark {} offline on {}: {}", uuid, serverId, error.toString());
		});
	}

	/**
	 * Marks everyone offline, see {@link StatsStore#markAllOffline}.
	 */
	public CompletableFuture<Integer> markAllOffline() {
		final boolean sqlite = this.database.isSqlite();

		return this.database.submit(connection -> StatsStore.markAllOffline(connection, sqlite));
	}
}
