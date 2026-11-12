package pl.kingdomcraft.boatrace.captainhook;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import pl.kingdomcraft.boatrace.Settings;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * The safe doorway to CaptainHook, the network's core plugin (season pass, achievements, missions). CaptainHook is a
 * softdepend: this class never refers to CaptainHook's own types (only {@link CaptainHookGateway} does), so it always
 * loads. Without CaptainHook, or with captainhook.enabled false, every call is a no-op.
 * <p>
 * Reports are fire-and-forget: CaptainHook's client queues writes while no player can carry them to the proxy. A
 * failure is logged at most once a minute and never retried.
 * <p>
 * Main thread only.
 */
public final class CaptainHookHook {

	private static final Pattern GAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
	private static final int TRACK_MAX_LENGTH = 32 - "fastest_race_".length();
	private static final long LOG_INTERVAL_MS = 60_000L;

	private final JavaPlugin plugin;
	private final Settings settings;

	private CaptainHookGateway gateway;
	private long lastLog;
	private int suppressed;

	/** XP given today per player, for pass-xp.daily-limit */
	private final Map<UUID, Long> xpToday = new HashMap<>();
	private LocalDate xpDay = LocalDate.now();

	public CaptainHookHook(final JavaPlugin plugin, final Settings settings) {
		this.plugin = plugin;
		this.settings = settings;
	}

	/** Connects to CaptainHook if it is installed and enabled in config.yml. Safe to call again on reload. */
	public void init() {
		this.gateway = null;

		if (!this.settings.chEnabled) {
			this.plugin.getLogger().info("CaptainHook integration is off (captainhook.enabled is false).");
			return;
		}

		if (!GAME_PATTERN.matcher(this.settings.chGame).matches()) {
			this.plugin.getLogger().warning("captainhook.game '" + this.settings.chGame + "' is not valid (a-z, 0-9, _ and -, at most 32) - the CaptainHook integration is disabled.");
			return;
		}

		if (!Bukkit.getPluginManager().isPluginEnabled("CaptainHook")) {
			this.plugin.getLogger().info("CaptainHook not found - no season pass XP, achievements or missions for races.");
			return;
		}

		try {
			this.gateway = new CaptainHookGateway();
			this.plugin.getLogger().info("CaptainHook found - reporting races as game '" + this.settings.chGame + "'.");

		} catch (final Throwable t) {
			this.plugin.getLogger().log(Level.SEVERE, "Could not connect to CaptainHook (incompatible CaptainHook-Bukkit version?). The integration is disabled.", t);
		}
	}

	public void shutdown() {
		this.gateway = null;
	}

	public boolean isAvailable() {
		return this.gateway != null;
	}

	/**
	 * A finished race of one player, in the units of the website contract (see config.yml "captainhook").
	 *
	 * @param track        track id ({@link #trackId})
	 * @param standardLaps the race had the arena's own lap count (only then fastest_race_<track> is reported)
	 * @param timeMs       official time (penalties included)
	 * @param realMs       time actually sailed (without penalties), for the average speed
	 * @param lapMs        official time of every lap
	 * @param distance     blocks sailed in this race
	 * @param ranked       a ranked race (enough racers, see ranking.min-racers)
	 * @param position     finishing position in a ranked race, otherwise 0
	 * @param streak       ranked races in a row without being last, after this one (-1 = not ranked)
	 * @param rating       the player's ranking (XP multiplier)
	 * @param newBest      a new personal best time (single-lap races)
	 * @param xpBoost      factor of the race XP: competitive.xp-multiplier x same lineup bonus, 1.0 in casual races
	 */
	public record FinishReport(String track, int laps, boolean standardLaps, long timeMs, long realMs, long[] lapMs, double distance,
			boolean ranked, int position, int streak, int rating, boolean newBest, double xpBoost) {

		/** average speed in blocks per second x 100 */
		public long avgSpeed() {
			return this.realMs > 0L ? Math.round(this.distance * 100_000.0D / this.realMs) : 0L;
		}

		public long fastestLap() {
			long best = 0L;

			for (final long lap : this.lapMs)
				if (lap > 0L && (best == 0L || lap < best))
					best = lap;

			return best;
		}
	}

	/** A racer crossed the finish line: one stats message for the website / achievements / missions, then the XP. */
	public void reportFinish(final Player player, final FinishReport race) {
		if (this.gateway == null)
			return;

		final String implausible = this.implausible(race);

		if (implausible != null) {
			this.plugin.getLogger().warning("Not reporting the race of " + player.getName() + " on " + race.track() + " to CaptainHook: " + implausible);
			return;
		}

		final UUID uuid = player.getUniqueId();
		final List<StatChange> changes = new ArrayList<>(12);
		final boolean win = race.ranked() && race.position() == 1;
		final boolean podium = race.ranked() && race.position() >= 1 && race.position() <= 3;
		final long blocks = Math.round(race.distance());

		changes.add(StatChange.add(uuid, "races", 1));
		changes.add(StatChange.add(uuid, "laps", race.laps()));

		if (win)
			changes.add(StatChange.add(uuid, "wins", 1));

		if (podium)
			changes.add(StatChange.add(uuid, "podiums", 1));

		changes.add(StatChange.add(uuid, "distance", blocks));
		changes.add(StatChange.add(uuid, "race_time_ms", race.realMs()));
		changes.add(StatChange.min(uuid, "fastest_lap", race.fastestLap()));
		changes.add(StatChange.min(uuid, "fastest_" + race.track(), race.fastestLap()));

		if (race.standardLaps())
			changes.add(StatChange.min(uuid, "fastest_race_" + race.track(), race.timeMs()));

		changes.add(StatChange.max(uuid, "top_avg_speed", race.avgSpeed()));

		if (race.streak() > 0)
			changes.add(StatChange.max(uuid, "best_streak", race.streak()));

		this.record(changes);

		long xp = this.settings.chXpFinish;

		if (win)
			xp += this.settings.chXpWin;
		else if (podium)
			xp += this.settings.chXpPodium;

		if (race.newBest())
			xp += this.settings.chXpPersonalBest;

		if (this.settings.chBlocksPerXp > 0)
			xp += blocks / this.settings.chBlocksPerXp;

		this.giveXp(uuid, player, Math.round(xp * this.settings.xpMultiplier(race.rating()) * race.xpBoost()));
	}

	/** A ranked race was settled: the new ratings of its players (stat "rating", set). */
	public void reportRatings(final Map<UUID, Integer> ratings) {
		final List<StatChange> changes = new ArrayList<>(ratings.size());

		for (final Map.Entry<UUID, Integer> entry : ratings.entrySet())
			changes.add(StatChange.set(entry.getKey(), "rating", entry.getValue()));

		this.record(changes);
	}

	/**
	 * A competitive match was settled: comp_races / comp_wins / comp_full_team for the members who FINISHED (website
	 * rule: nothing for a DNF), the new rating (set) of every rated member, and the win / full-team XP.
	 *
	 * @param finished    members who crossed the finish line
	 * @param winners     members of the winning team
	 * @param fullTeams   members of teams whose members ALL finished
	 * @param newRatings  rating after the match of every rated member (empty without ranking)
	 * @param ratings     every member's rating (XP multiplier)
	 * @param lineupBoost same lineup factor per member (1.0 = none)
	 */
	public void reportCompetitiveResult(final List<UUID> finished, final java.util.Set<UUID> winners, final java.util.Set<UUID> fullTeams,
			final Map<UUID, Integer> newRatings, final Map<UUID, Integer> ratings, final Map<UUID, Double> lineupBoost) {
		if (this.gateway == null)
			return;

		final List<StatChange> changes = new ArrayList<>();

		for (final UUID uuid : finished) {
			changes.add(StatChange.add(uuid, "comp_races", 1));

			if (winners.contains(uuid))
				changes.add(StatChange.add(uuid, "comp_wins", 1));

			if (fullTeams.contains(uuid))
				changes.add(StatChange.add(uuid, "comp_full_team", 1));
		}

		for (final Map.Entry<UUID, Integer> entry : newRatings.entrySet())
			changes.add(StatChange.set(entry.getKey(), "rating", entry.getValue()));

		this.record(changes);

		for (final UUID uuid : finished) {
			long xp = 0L;

			if (winners.contains(uuid))
				xp += this.settings.compXpWin;

			if (fullTeams.contains(uuid))
				xp += this.settings.compXpFullTeam;

			if (xp > 0L)
				this.giveXp(uuid, Bukkit.getPlayer(uuid), Math.round(xp * this.settings.compXpMultiplier * lineupBoost.getOrDefault(uuid, 1.0D)
						* this.settings.xpMultiplier(ratings.getOrDefault(uuid, this.settings.rankingStart))));
		}
	}

	/** @return why the result cannot be true, or null if it is plausible */
	private String implausible(final FinishReport race) {
		if (race.realMs() <= 0L || race.lapMs().length == 0)
			return "no time";

		for (final long lap : race.lapMs())
			if (lap < this.settings.chMinLapMs)
				return "a lap of " + lap + " ms is shorter than captainhook.min-lap-seconds";

		if (race.avgSpeed() > Math.round(this.settings.chMaxAvgSpeed * 100.0D))
			return "an average speed of " + race.avgSpeed() / 100.0D + " blocks/s is above captainhook.max-avg-speed";

		return null;
	}

	/**
	 * The id of an arena in stat keys and on the website: the name in lower case, other characters than a-z, 0-9 and _
	 * become _, at most 19 characters ("fastest_race_" + id fits the 32 characters of a stat key).
	 */
	public static String trackId(final String arena) {
		final String id = arena.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");

		return id.length() > TRACK_MAX_LENGTH ? id.substring(0, TRACK_MAX_LENGTH) : id;
	}

	private void record(final List<StatChange> changes) {
		final CaptainHookGateway current = this.gateway;

		// the website shows values above 0 only: never send zeros or negative values
		changes.removeIf(change -> change.value() <= 0L);

		if (current == null || changes.isEmpty())
			return;

		try {
			this.watch(current.record(this.settings.chGame, changes), "race stats");

		} catch (final Throwable t) {
			this.logFailure("race stats", t);
		}
	}

	/** Gives season pass XP, capped by pass-xp.daily-limit, and tells the player. */
	private void giveXp(final UUID uuid, final Player player, final long requested) {
		final CaptainHookGateway current = this.gateway;

		if (current == null || requested <= 0L)
			return;

		final LocalDate today = LocalDate.now();

		if (!today.equals(this.xpDay)) {
			this.xpDay = today;
			this.xpToday.clear();
		}

		long amount = requested;

		if (this.settings.chDailyXpLimit > 0L) {
			final long given = this.xpToday.getOrDefault(uuid, 0L);

			amount = Math.min(amount, this.settings.chDailyXpLimit - given);

			if (amount <= 0L)
				return;

			this.xpToday.put(uuid, given + amount);
		}

		try {
			final long granted = amount;
			final CompletableFuture<?> future = current.addPassXp(uuid, amount);

			future.whenComplete((progress, error) -> {
				if (error == null) {
					final Player online = player != null && player.isOnline() ? player : Bukkit.getPlayer(uuid);

					if (online != null)
						Msg.send(online, "captainhook.pass-xp", "xp", granted);

				} else if (!"no_season".equals(current.errorCode(unwrap(error))))
					this.logFailure("season pass XP", error);
			});

		} catch (final Throwable t) {
			this.logFailure("season pass XP", t);
		}
	}

	private void watch(final CompletableFuture<?> future, final String what) {
		future.whenComplete((ignored, error) -> {
			if (error != null)
				this.logFailure(what, error);
		});
	}

	private void logFailure(final String what, final Throwable error) {
		final long now = System.currentTimeMillis();

		if (now - this.lastLog < LOG_INTERVAL_MS) {
			this.suppressed++;
			return;
		}

		final Throwable cause = unwrap(error);
		final CaptainHookGateway current = this.gateway;
		final String code = current != null ? current.errorCode(cause) : null;

		this.plugin.getLogger().warning("Could not send " + what + " to CaptainHook: " + (code != null ? code : cause.toString())
				+ (this.suppressed > 0 ? " (" + this.suppressed + " more failures in the last minute)" : ""));

		this.lastLog = now;
		this.suppressed = 0;
	}

	private static Throwable unwrap(final Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}
}
