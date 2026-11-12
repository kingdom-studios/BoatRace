package pl.kingdomcraft.captainhook.velocity.pass;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.ServiceException;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.reward.Reward;
import pl.kingdomcraft.captainhook.velocity.reward.Rewards;

/**
 * The season pass: players earn XP anywhere on the network, every xp-per-level XP is one level, and each level
 * (tier) has a free and a premium reward that is claimed once.
 *
 * XP only changes through atomic relative updates and a claim is a primary key insert, so the game and the
 * website can both write without overwriting each other or claiming twice.
 */
public final class SeasonPass {

	private final Database database;
	private final KingdomCoins coins;
	private final Network network;

	private volatile SeasonPassSettings settings;

	public SeasonPass(final Database database, final KingdomCoins coins, final Network network, final SeasonPassSettings settings) {
		this.database = database;
		this.coins = coins;
		this.network = network;
		this.settings = settings;
	}

	public void reload(final SeasonPassSettings settings) {
		this.settings = settings;
	}

	public SeasonPassSettings getSettings() {
		return this.settings;
	}

	/**
	 * @return the season XP can be earned in right now, or null
	 */
	public SeasonPassSettings getActiveSeason() {
		final SeasonPassSettings settings = this.settings;

		return settings != null && settings.isActive(System.currentTimeMillis() / 1000) ? settings : null;
	}

	private SeasonPassSettings requireActiveSeason() {
		final SeasonPassSettings season = this.getActiveSeason();

		if (season == null)
			throw new ServiceException(Protocol.ERROR_NO_SEASON, "No season pass is active");

		return season;
	}

	/* ------------------------------------------------------------------------------- */
	/* XP */
	/* ------------------------------------------------------------------------------- */

	/**
	 * The outcome of an XP change.
	 *
	 * @param xpBefore XP before the change, so callers can tell which levels were gained
	 */
	public record XpChange(SeasonPassSettings season, PassState state, long xpBefore) {

		public int level() {
			return this.season.levelOf(this.state.xp());
		}

		public int levelBefore() {
			return this.season.levelOf(this.xpBefore);
		}
	}

	/**
	 * Adds XP and tells the player's server. Fails with {@link ServiceException} (no_season) when no season is active.
	 */
	public CompletableFuture<XpChange> addXp(final UUID uuid, final long amount) {
		if (amount <= 0 || amount > KingdomCoins.MAX_AMOUNT)
			throw new IllegalArgumentException("XP must be between 1 and " + KingdomCoins.MAX_AMOUNT + ", got " + amount);

		final SeasonPassSettings season = this.requireActiveSeason();

		return this.database.submit(connection -> SeasonPassStore.addXp(connection, season.id(), uuid, amount, this.database.isSqlite()))
				.thenApply(state -> {
					final XpChange change = new XpChange(season, state, state.xp() - amount);

					this.announce(uuid, change);
					return change;
				});
	}

	/**
	 * Sends pass.update to the player's server. Call after the change is committed.
	 */
	public void announce(final UUID uuid, final XpChange change) {
		final JsonObject data = toJson(change.season(), change.state());

		data.addProperty("uuid", uuid.toString());
		data.addProperty("gained", change.state().xp() - change.xpBefore());
		data.addProperty("levelBefore", change.levelBefore());

		this.network.notify(uuid, Protocol.PASS_UPDATE, data);
	}

	/**
	 * @return the season and the player's progress as sent to the Paper servers
	 */
	public static JsonObject toJson(final SeasonPassSettings season, final PassState state) {
		final JsonObject json = new JsonObject();

		json.addProperty("season", season.id());
		json.addProperty("name", season.name());
		json.addProperty("xp", state.xp());
		json.addProperty("level", season.levelOf(state.xp()));
		json.addProperty("maxLevel", season.maxLevel());
		json.addProperty("xpPerLevel", season.xpPerLevel());
		json.addProperty("xpIntoLevel", season.xpIntoLevel(state.xp()));
		json.addProperty("xpForNext", season.xpForNextLevel(state.xp()));
		json.addProperty("premium", state.premium());

		return json;
	}

	/* ------------------------------------------------------------------------------- */
	/* Reading */
	/* ------------------------------------------------------------------------------- */

	/**
	 * A player's progress with the rewards already claimed.
	 */
	public record Progress(SeasonPassSettings season, PassState state, List<SeasonPassStore.Claim> claims) {

		public JsonObject toJson() {
			final JsonObject json = SeasonPass.toJson(this.season, this.state);
			final JsonArray claimed = new JsonArray();

			for (final SeasonPassStore.Claim claim : this.claims) {
				final JsonObject entry = new JsonObject();

				entry.addProperty("tier", claim.tier());
				entry.addProperty("track", claim.track());
				claimed.add(entry);
			}

			json.add("claimed", claimed);
			return json;
		}
	}

	/**
	 * Fails with {@link ServiceException} (no_season) when no season is active.
	 */
	public CompletableFuture<Progress> getProgress(final UUID uuid) {
		final SeasonPassSettings season = this.requireActiveSeason();

		return this.database.submit(connection -> new Progress(season,
				SeasonPassStore.get(connection, season.id(), uuid),
				SeasonPassStore.getClaims(connection, season.id(), uuid)));
	}

	/* ------------------------------------------------------------------------------- */
	/* Claiming */
	/* ------------------------------------------------------------------------------- */

	public enum ClaimStatus {
		SUCCESS,
		NO_SEASON,
		UNKNOWN_TIER,
		LEVEL_TOO_LOW,
		PREMIUM_REQUIRED,
		ALREADY_CLAIMED
	}

	/**
	 * @param reward what was granted, null unless the status is SUCCESS
	 */
	public record ClaimResult(ClaimStatus status, Reward reward) {

		static ClaimResult failed(final ClaimStatus status) {
			return new ClaimResult(status, null);
		}
	}

	/**
	 * Claims the reward of a tier: checks the level and the premium track, records the claim and grants the
	 * reward in one transaction.
	 *
	 * @param source who claims, stored with the claim, e.g. "game" or "web"
	 */
	public CompletableFuture<ClaimResult> claim(final UUID uuid, final int tier, final Track track, final String source) {
		final SeasonPassSettings current = this.settings;
		// Rewards can still be claimed during the grace period after the season, but no XP is earned any more
		final SeasonPassSettings season = current != null && current.isClaimable(System.currentTimeMillis() / 1000) ? current : null;

		if (season == null)
			return CompletableFuture.completedFuture(ClaimResult.failed(ClaimStatus.NO_SEASON));

		final SeasonPassSettings.Tier definition = season.tiers().get(tier);

		if (definition == null)
			return CompletableFuture.completedFuture(ClaimResult.failed(ClaimStatus.UNKNOWN_TIER));

		final Reward reward = definition.reward(track);
		final boolean sqlite = this.database.isSqlite();

		return this.database.submit(connection -> {
			final PassState state = SeasonPassStore.get(connection, season.id(), uuid);

			if (season.levelOf(state.xp()) < tier)
				return new Claimed(ClaimResult.failed(ClaimStatus.LEVEL_TOO_LOW), null);

			if (track == Track.PREMIUM && !state.premium())
				return new Claimed(ClaimResult.failed(ClaimStatus.PREMIUM_REQUIRED), null);

			if (!SeasonPassStore.insertClaim(connection, season.id(), uuid, tier, track, source, sqlite))
				return new Claimed(ClaimResult.failed(ClaimStatus.ALREADY_CLAIMED), null);

			final Rewards.Granted granted = Rewards.grant(connection, sqlite, uuid, reward, "seasonpass:" + season.id() + ":" + tier + ":" + track.key(),
					season.isActive(System.currentTimeMillis() / 1000) ? season : null);

			return new Claimed(new ClaimResult(ClaimStatus.SUCCESS, reward), granted);

		}).thenApply(claimed -> {
			if (claimed.granted != null)
				this.announce(uuid, season, claimed.granted);

			return claimed.result;
		});
	}

	private record Claimed(ClaimResult result, Rewards.Granted granted) {
	}

	/**
	 * Tells the player's server about coins and pass XP of a granted reward. Call after the commit.
	 */
	public void announce(final UUID uuid, final SeasonPassSettings season, final Rewards.Granted granted) {
		if (granted.coinBalance() != null)
			this.coins.onBalanceChanged(uuid, granted.coinBalance());

		if (granted.pass() != null && season != null)
			this.announce(uuid, new XpChange(season, granted.pass(), granted.xpBefore()));
	}
}
