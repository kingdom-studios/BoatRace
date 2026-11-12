package pl.kingdomcraft.captainhook.velocity.achievement;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.ServiceException;
import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition.SourceType;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameStore;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.reward.Rewards;
import pl.kingdomcraft.captainhook.velocity.stats.StatsStore;

/**
 * Network-wide achievements from achievements.yml.
 *
 * Progress comes from one of three sources: plugins adding it through the API (manual), the player's network
 * stats, or a minigame stat. The last two are followed automatically: the stats and minigame services call
 * {@link #evaluateNetworkStats} and {@link #evaluateMinigameStats} after they wrote new values.
 *
 * An unlock and its rewards happen in one transaction, guarded by "unlocked_at IS NULL", so an achievement
 * can never pay out twice. Players are told after the commit, see {@link #announce}.
 */
public final class Achievements {

	private final Database database;
	private final SeasonPass seasonPass;
	private final Network network;

	private volatile Map<String, AchievementDefinition> definitions;

	public Achievements(final Database database, final SeasonPass seasonPass, final Network network, final Map<String, AchievementDefinition> definitions) {
		this.database = database;
		this.seasonPass = seasonPass;
		this.network = network;
		this.definitions = Collections.unmodifiableMap(definitions);
	}

	public void reload(final Map<String, AchievementDefinition> definitions) {
		this.definitions = Collections.unmodifiableMap(definitions);
	}

	public Map<String, AchievementDefinition> getDefinitions() {
		return this.definitions;
	}

	/**
	 * A freshly unlocked achievement and what its reward changed.
	 */
	public record Unlock(UUID uuid, AchievementDefinition definition, SeasonPassSettings season, Rewards.Granted granted) {
	}

	/**
	 * A player's standing in one achievement.
	 *
	 * @param unlockedAt Unix seconds, null while locked
	 * @param claimedAt  Unix seconds the reward was granted, null until then
	 * @param newly      true if this very call unlocked it
	 */
	public record Progress(AchievementDefinition definition, long progress, Long unlockedAt, Long claimedAt, boolean newly) {

		/**
		 * @return true if the player can claim the reward now (on the website)
		 */
		public boolean isClaimable() {
			return this.unlockedAt != null && this.claimedAt == null && !this.definition.autoReward() && !this.definition.reward().isEmpty();
		}

		public JsonObject toJson() {
			final JsonObject json = new JsonObject();

			json.addProperty("id", this.definition.id());
			json.addProperty("name", this.definition.name());
			json.addProperty("progress", this.progress);
			json.addProperty("goal", this.definition.goal());
			json.addProperty("unlocked", this.unlockedAt != null);

			if (this.unlockedAt != null)
				json.addProperty("unlockedAt", this.unlockedAt);

			json.addProperty("claimed", this.claimedAt != null);
			json.addProperty("claimable", this.isClaimable());
			json.addProperty("newly", this.newly);
			return json;
		}
	}

	/* ------------------------------------------------------------------------------- */
	/* API */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Adds progress to a manual achievement, unlocking it when the goal is reached.
	 *
	 * Fails with {@link ServiceException}: unknown_achievement, or bad_request for an achievement that follows
	 * a stat (its progress is not ours to change).
	 */
	public CompletableFuture<Progress> addProgress(final UUID uuid, final String id, final long amount) {
		if (amount <= 0)
			throw new IllegalArgumentException("Progress must be positive, got " + amount);

		final AchievementDefinition definition = this.definitions.get(id);

		if (definition == null)
			throw new ServiceException(Protocol.ERROR_UNKNOWN_ACHIEVEMENT, "No achievement \"" + id + "\" in " + AchievementDefinition.FILE);

		if (!definition.isManual())
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "Achievement \"" + id + "\" follows a stat, its progress cannot be added manually");

		return this.database.submit(connection -> {
			final AchievementStore.Row row = AchievementStore.getRows(connection, uuid).get(id);

			if (row != null && row.isUnlocked())
				return new Added(new Progress(definition, row.progress(), row.unlockedAt(), row.claimedAt(), false), null);

			final long current = row != null ? row.progress() : 0;
			final long target = current >= definition.goal() ? definition.goal() : current + Math.min(amount, definition.goal() - current);
			final Unlock unlock = this.apply(connection, uuid, definition, target, row);

			final Long now = unlock != null ? System.currentTimeMillis() / 1000 : null;

			return new Added(new Progress(definition, target, now, definition.autoReward() ? now : null, unlock != null), unlock);

		}).thenApply(added -> {
			if (added.unlock != null)
				this.announce(Collections.singletonList(added.unlock));

			return added.progress;
		});
	}

	private record Added(Progress progress, Unlock unlock) {
	}

	/**
	 * @return the player's standing in every configured achievement, in file order
	 */
	public CompletableFuture<List<Progress>> list(final UUID uuid) {
		final Map<String, AchievementDefinition> definitions = this.definitions;

		return this.database.submit(connection -> {
			final Map<String, AchievementStore.Row> rows = AchievementStore.getRows(connection, uuid);
			final List<Progress> list = new ArrayList<>(definitions.size());

			for (final AchievementDefinition definition : definitions.values()) {
				final AchievementStore.Row row = rows.get(definition.id());

				list.add(new Progress(definition, row != null ? row.progress() : 0, row != null ? row.unlockedAt() : null, row != null ? row.claimedAt() : null, false));
			}

			return list;
		});
	}

	/* ------------------------------------------------------------------------------- */
	/* Following stats, on the database thread */
	/* ------------------------------------------------------------------------------- */

	public boolean followsNetworkStats() {
		return this.definitions.values().stream().anyMatch(definition -> definition.source().type() == SourceType.NETWORK_STAT);
	}

	public boolean followsMinigame(final String game) {
		return this.definitions.values().stream().anyMatch(definition -> definition.source().type() == SourceType.MINIGAME_STAT && game.equals(definition.source().game()));
	}

	/**
	 * Updates achievements that follow network stats for players whose totals were just refreshed.
	 *
	 * @return what was unlocked, pass it to {@link #announce} after the commit
	 */
	public List<Unlock> evaluateNetworkStats(final Connection connection, final Collection<UUID> uuids) throws SQLException {
		final List<Unlock> unlocks = new ArrayList<>();

		if (uuids.isEmpty() || !this.followsNetworkStats())
			return unlocks;

		final Map<UUID, Map<String, Long>> totals = StatsStore.getTotals(connection, uuids);
		final Map<UUID, Map<String, AchievementStore.Row>> rows = AchievementStore.getRows(connection, uuids);

		for (final Map.Entry<UUID, Map<String, Long>> player : totals.entrySet())
			for (final AchievementDefinition definition : this.definitions.values())
				if (definition.source().type() == SourceType.NETWORK_STAT) {
					final long value = player.getValue().getOrDefault(definition.source().stat(), 0L);
					final Unlock unlock = this.apply(connection, player.getKey(), definition, value,
							rows.getOrDefault(player.getKey(), Collections.emptyMap()).get(definition.id()));

					if (unlock != null)
						unlocks.add(unlock);
				}

		return unlocks;
	}

	/**
	 * Updates achievements that follow stats of this minigame for players whose stats were just written.
	 *
	 * @return what was unlocked, pass it to {@link #announce} after the commit
	 */
	public List<Unlock> evaluateMinigameStats(final Connection connection, final String game, final Collection<UUID> uuids) throws SQLException {
		final List<Unlock> unlocks = new ArrayList<>();

		if (uuids.isEmpty() || !this.followsMinigame(game))
			return unlocks;

		final Map<UUID, Map<String, AchievementStore.Row>> rows = AchievementStore.getRows(connection, uuids);

		for (final UUID uuid : uuids) {
			final Map<String, Long> stats = MinigameStore.getStats(connection, game, uuid);

			for (final AchievementDefinition definition : this.definitions.values())
				if (definition.source().type() == SourceType.MINIGAME_STAT && game.equals(definition.source().game())) {
					final Unlock unlock = this.apply(connection, uuid, definition, definition.source().progressOf(stats.get(definition.source().stat()), definition.goal()),
							rows.getOrDefault(uuid, Collections.emptyMap()).get(definition.id()));

					if (unlock != null)
						unlocks.add(unlock);
				}
		}

		return unlocks;
	}

	/**
	 * Stores the new absolute progress of a locked achievement and unlocks it once the goal is reached.
	 *
	 * @param existing the player's current row, or null
	 * @return the unlock, or null if the achievement stays locked (or was unlocked before)
	 */
	private Unlock apply(final Connection connection, final UUID uuid, final AchievementDefinition definition, final long value, final AchievementStore.Row existing) throws SQLException {
		if (existing != null && existing.isUnlocked())
			return null;

		final long target = Math.max(0, Math.min(value, definition.goal()));

		// No row for zero progress, and no write when nothing changed
		if (existing == null ? target == 0 : existing.progress() == target && target < definition.goal())
			return null;

		final boolean sqlite = this.database.isSqlite();

		AchievementStore.setProgress(connection, uuid, definition.id(), target, sqlite);

		if (target < definition.goal() || !AchievementStore.unlock(connection, uuid, definition.id(), definition.autoReward()))
			return null;

		// A claimed reward waits for the player, see claim()
		if (!definition.autoReward())
			return new Unlock(uuid, definition, null, Rewards.Granted.NOTHING);

		final SeasonPassSettings season = this.seasonPass.getActiveSeason();

		return new Unlock(uuid, definition, season, Rewards.grant(connection, sqlite, uuid, definition.reward(), source(definition), season));
	}

	/**
	 * @return what reward grants of this achievement are logged as
	 */
	public static String source(final AchievementDefinition definition) {
		return "achievement:" + definition.id();
	}

	public enum ClaimStatus {
		SUCCESS,
		UNKNOWN_ACHIEVEMENT,
		/**
		 * The reward is granted on unlock, there is nothing to claim.
		 */
		AUTOMATIC,
		NOT_UNLOCKED,
		ALREADY_CLAIMED
	}

	/**
	 * Grants the reward of an unlocked achievement whose reward-mode is "claim". The website does the same in its
	 * own transaction (the conditional update on claimed_at makes a double claim impossible either way).
	 */
	public CompletableFuture<ClaimStatus> claim(final UUID uuid, final String id) {
		final AchievementDefinition definition = this.definitions.get(id);

		if (definition == null)
			return CompletableFuture.completedFuture(ClaimStatus.UNKNOWN_ACHIEVEMENT);

		if (definition.autoReward())
			return CompletableFuture.completedFuture(ClaimStatus.AUTOMATIC);

		final boolean sqlite = this.database.isSqlite();
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();

		return this.database.submit(connection -> {
			final AchievementStore.Row row = AchievementStore.getRows(connection, uuid).get(id);

			if (row == null || !row.isUnlocked())
				return new Claimed(ClaimStatus.NOT_UNLOCKED, null);

			if (!AchievementStore.claim(connection, uuid, id))
				return new Claimed(ClaimStatus.ALREADY_CLAIMED, null);

			return new Claimed(ClaimStatus.SUCCESS, Rewards.grant(connection, sqlite, uuid, definition.reward(), source(definition), season));

		}).thenApply(claimed -> {
			if (claimed.granted != null)
				this.seasonPass.announce(uuid, season, claimed.granted);

			return claimed.status;
		});
	}

	private record Claimed(ClaimStatus status, Rewards.Granted granted) {
	}

	/**
	 * Tells the players' servers about unlocks and their rewards. Call after the transaction committed.
	 */
	public void announce(final List<Unlock> unlocks) {
		for (final Unlock unlock : unlocks) {
			final AchievementDefinition definition = unlock.definition();
			final JsonObject data = new JsonObject();

			data.addProperty("uuid", unlock.uuid().toString());
			data.addProperty("id", definition.id());
			data.addProperty("name", definition.name());
			data.addProperty("description", definition.description());
			data.addProperty("coins", unlock.granted().coinBalance() != null ? definition.reward().kingdomCoins() : 0);
			data.addProperty("passXp", unlock.granted().pass() != null ? definition.reward().passXp() : 0);
			data.addProperty("claimable", !definition.autoReward() && !definition.reward().isEmpty());

			this.network.notify(unlock.uuid(), Protocol.ACHIEVEMENT_UNLOCKED, data);
			this.seasonPass.announce(unlock.uuid(), unlock.season(), unlock.granted());
		}
	}
}
