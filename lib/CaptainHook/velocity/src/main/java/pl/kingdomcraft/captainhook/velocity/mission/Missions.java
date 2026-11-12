package pl.kingdomcraft.captainhook.velocity.mission;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.Network;
import pl.kingdomcraft.captainhook.velocity.ServiceException;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.pass.PassState;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassStore;

/**
 * Season pass missions: daily, weekly and season missions that give pass XP. The proxy is the only authority:
 *
 * - it draws the missions of every period (deterministically, from the season ID and the period) and stores them, so
 *   every player on every server has the same ones, and a restart or a second proxy cannot draw different ones
 * - Paper servers only report what players did (mission.progress); the proxy does the counting
 * - progress is buffered in memory and written every flush-interval-seconds and when a player leaves
 * - completing a mission is one transaction: progress, completed_at (set once), the XP ledger entry (unique per mission
 *   and player) and the XP itself. Whatever fails or repeats, the XP is granted exactly once.
 *
 * Missions only count while the season runs. XP of unlinked accounts is kept too: the proxy does not know which
 * Minecraft accounts are linked to a website account, and the XP is simply there once they link.
 */
public final class Missions {

	/**
	 * Most missions a player can finish within one flush of one player; far more than any pool holds.
	 */
	private static final int MAX_NOTIFICATIONS = 50;

	private final Database database;
	private final SeasonPass seasonPass;
	private final Network network;
	private final Logger logger;
	private final LongSupplier clock;

	private volatile MissionSettings settings;
	private volatile List<Assignment> active = List.of();
	private volatile String activeSeason = "";
	private volatile boolean dirty = true;

	/**
	 * Every assignment seen since the proxy started, so progress buffered just before a period ended is still counted
	 * towards the mission it was made on.
	 */
	private final Map<Long, Assignment> known = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Progress waiting to be written, by player and assignment.
	 */
	private final Object lock = new Object();
	private Map<Key, Long> buffer = new HashMap<>();

	private record Key(UUID uuid, long assignmentId) {
	}

	/**
	 * A mission a player has just finished.
	 */
	public record Completion(UUID uuid, Assignment assignment, SeasonPass.XpChange xp) {
	}

	public Missions(final Database database, final SeasonPass seasonPass, final Network network, final Logger logger, final MissionSettings settings) {
		this(database, seasonPass, network, logger, settings, () -> System.currentTimeMillis() / 1000);
	}

	/**
	 * @param clock Unix seconds, replaced in tests
	 */
	public Missions(final Database database, final SeasonPass seasonPass, final Network network, final Logger logger, final MissionSettings settings, final LongSupplier clock) {
		this.database = database;
		this.seasonPass = seasonPass;
		this.network = network;
		this.logger = logger;
		this.settings = settings;
		this.clock = clock;
	}

	public MissionSettings getSettings() {
		return this.settings;
	}

	/**
	 * Applies a reloaded missions.yml. Missions already assigned for the current periods stay as they are.
	 */
	public void reload(final MissionSettings settings) {
		this.settings = settings;
		this.dirty = true;
	}

	/**
	 * @return the missions that are active right now, all periods
	 */
	public List<Assignment> getActive() {
		return this.active;
	}

	/* ------------------------------------------------------------------------------- */
	/* Rotation: drawing the missions of a period */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Makes sure the missions of the current daily, weekly and season periods are drawn and stored, and refreshes
	 * the cache. Cheap when nothing changed, so it is called every few seconds; a period change (midnight, Monday)
	 * is picked up by the next call.
	 */
	public CompletableFuture<Void> rotate() {
		final MissionSettings config = this.settings;
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();
		final long now = this.clock.getAsLong();

		if (season == null || !config.enabled()) {
			this.active = List.of();
			this.activeSeason = "";

			return CompletableFuture.completedFuture(null);
		}

		if (!this.dirty && season.id().equals(this.activeSeason) && this.active.stream().allMatch(assignment -> assignment.isCurrent(now)) && this.hasAllPeriods(now))
			return CompletableFuture.completedFuture(null);

		this.dirty = false;

		return this.database.submit(connection -> {
			for (final MissionPeriodType type : MissionPeriodType.values())
				this.ensure(connection, config, season, this.periodOf(type, season, config, now), false);

			return latest(MissionStore.loadCurrent(connection, season.id(), now));

		}).thenAccept(assignments -> {
			this.use(assignments, season.id());

		}).whenComplete((ignored, error) -> {
			if (error != null) {
				this.dirty = true;
				this.logger.warn("Could not draw the missions: {}", error.toString());
			}
		});
	}

	private void use(final List<Assignment> assignments, final String seasonId) {
		assignments.forEach(assignment -> this.known.put(assignment.id(), assignment));
		this.active = assignments;
		this.activeSeason = seasonId;
	}

	/**
	 * @return false if a period that should have missions has none cached (e.g. after midnight)
	 */
	private boolean hasAllPeriods(final long now) {
		final MissionSettings config = this.settings;

		for (final MissionPeriodType type : MissionPeriodType.values())
			if (!config.pool(type).isEmpty() && config.count(type) > 0 && this.active.stream().noneMatch(assignment -> assignment.type() == type && assignment.isCurrent(now)))
				return false;

		return true;
	}

	private MissionPeriod periodOf(final MissionPeriodType type, final SeasonPassSettings season, final MissionSettings config, final long now) {
		return type == MissionPeriodType.SEASON ? MissionPeriod.ofSeason(season.startsAt(), season.endsAt()) : MissionPeriod.of(type, now, config.zone());
	}

	/**
	 * Draws and stores the missions of a period unless they exist (or, with force, draws a new set: a re-roll).
	 */
	private void ensure(final java.sql.Connection connection, final MissionSettings config, final SeasonPassSettings season, final MissionPeriod period, final boolean force) throws java.sql.SQLException {
		final List<MissionDefinition> pool = config.pool(period.type());

		if (pool.isEmpty() || config.count(period.type()) <= 0)
			return;

		final List<String> existing = MissionStore.periodKeys(connection, season.id(), period.type(), period.key());

		if (!existing.isEmpty() && !force)
			return;

		final int revision = existing.stream().mapToInt(MissionPeriod::revision).max().orElse(-1) + 1;
		final String key = revision == 0 ? period.key() : period.key() + "." + revision;
		final MissionPeriod drawn = new MissionPeriod(period.type(), key, period.startsAt(), period.endsAt());
		final List<MissionDefinition> missions = MissionSelector.select(pool, config.count(period.type()), MissionSelector.seed(season.id() + "|" + period.type().key() + "|" + key));

		MissionStore.insertAssignments(connection, season.id(), drawn, missions, this.database.isSqlite());
	}

	/**
	 * Of each period, only the newest re-roll counts.
	 */
	static List<Assignment> latest(final List<Assignment> assignments) {
		final Map<String, Integer> newest = new HashMap<>();

		for (final Assignment assignment : assignments)
			newest.merge(assignment.type().key() + "|" + MissionPeriod.baseKey(assignment.periodKey()), MissionPeriod.revision(assignment.periodKey()), Math::max);

		final List<Assignment> current = new ArrayList<>();

		for (final Assignment assignment : assignments)
			if (newest.get(assignment.type().key() + "|" + MissionPeriod.baseKey(assignment.periodKey())) == MissionPeriod.revision(assignment.periodKey()))
				current.add(assignment);

		return Collections.unmodifiableList(current);
	}

	/**
	 * Draws a new set of missions for the current daily or weekly period, replacing the active one (admin).
	 * Progress on the old missions stays in the database, it just stops counting.
	 */
	public CompletableFuture<Void> reroll(final MissionPeriodType type) {
		final MissionSettings config = this.settings;
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();

		if (season == null)
			throw new ServiceException(Protocol.ERROR_NO_SEASON, "No season pass is active");

		if (type == MissionPeriodType.SEASON)
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "Season missions last the whole season and cannot be re-rolled");

		final long now = this.clock.getAsLong();

		// Pending progress belongs to the old set
		return this.flush().thenCompose(ignored -> this.database.submit(connection -> {
			this.ensure(connection, config, season, MissionPeriod.of(type, now, config.zone()), true);

			return latest(MissionStore.loadCurrent(connection, season.id(), now));

		})).thenAccept(assignments -> this.use(assignments, season.id()));
	}

	/* ------------------------------------------------------------------------------- */
	/* Counting */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Counts something a player did. Cheap: it only adds to an in-memory buffer.
	 */
	public void record(final UUID uuid, final Objective objective, final MissionFilter.Event event, final long delta) {
		if (delta <= 0 || this.seasonPass.getActiveSeason() == null)
			return;

		final long now = this.clock.getAsLong();

		for (final Assignment assignment : this.active)
			if (assignment.objective() == objective && assignment.isCurrent(now) && assignment.filter().matches(event))
				synchronized (this.lock) {
					this.buffer.merge(new Key(uuid, assignment.id()), delta, Long::sum);
				}
	}

	/**
	 * A minigame reported a stat change: feeds MINIGAME_STAT missions. Counters (add) add their amount; a mission
	 * with filter min-value counts 1 for any report whose value reaches it (see {@link MissionFilter#minigameDelta}).
	 */
	public void recordMinigameStat(final UUID uuid, final String game, final String stat, final boolean counter, final long value) {
		if (this.seasonPass.getActiveSeason() == null)
			return;

		final MissionFilter.Event event = new MissionFilter.Event(null, null, null, null, game, stat);
		final long now = this.clock.getAsLong();

		for (final Assignment assignment : this.active)
			if (assignment.objective() == Objective.MINIGAME_STAT && assignment.isCurrent(now) && assignment.filter().matches(event)) {
				final long delta = assignment.filter().minigameDelta(counter, value);

				if (delta > 0)
					synchronized (this.lock) {
						this.buffer.merge(new Key(uuid, assignment.id()), delta, Long::sum);
					}
			}
	}

	/**
	 * A player connected: counts LOGIN and DISTINCT_DAYS once per calendar day (in the missions' time zone).
	 */
	public CompletableFuture<Void> onLogin(final UUID uuid) {
		if (!this.settings.enabled() || this.seasonPass.getActiveSeason() == null)
			return CompletableFuture.completedFuture(null);

		final String day = Instant.ofEpochSecond(this.clock.getAsLong()).atZone(this.settings.zone()).toLocalDate().toString();
		final boolean sqlite = this.database.isSqlite();

		return this.database.submit(connection -> MissionStore.markDay(connection, uuid, day, sqlite)).thenAccept(first -> {
			if (first) {
				this.record(uuid, Objective.LOGIN, MissionFilter.Event.ofServer(null), 1);
				this.record(uuid, Objective.DISTINCT_DAYS, MissionFilter.Event.ofServer(null), 1);
			}
		});
	}

	/**
	 * Writes the buffered progress, completes missions and grants their XP. Called every flush-interval-seconds
	 * and when a player leaves or the proxy stops.
	 */
	public CompletableFuture<Void> flush() {
		final Map<Key, Long> drained;

		synchronized (this.lock) {
			if (this.buffer.isEmpty())
				return CompletableFuture.completedFuture(null);

			drained = this.buffer;
			this.buffer = new HashMap<>();
		}

		final Map<Long, Assignment> byId = this.known;
		final boolean sqlite = this.database.isSqlite();
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();

		if (season == null)
			return CompletableFuture.completedFuture(null);

		return this.database.submit(connection -> {
			final List<Completion> completions = new ArrayList<>();

			for (final Map.Entry<Key, Long> entry : drained.entrySet()) {
				final Assignment assignment = byId.get(entry.getKey().assignmentId());
				final UUID uuid = entry.getKey().uuid();

				if (assignment == null || !assignment.season().equals(season.id()))
					continue; // the period ended or was re-rolled in the meantime

				MissionStore.addProgress(connection, uuid, assignment, entry.getValue(), sqlite);

				if (!MissionStore.complete(connection, uuid, assignment))
					continue;

				// Both guards matter: completed_at is set once, and the ledger refuses a second grant of the same mission
				if (assignment.xp() > 0 && MissionStore.insertLedger(connection, uuid, season.id(), assignment.xp(), "mission", String.valueOf(assignment.id()), sqlite)) {
					final PassState state = SeasonPassStore.addXp(connection, season.id(), uuid, assignment.xp(), sqlite);

					completions.add(new Completion(uuid, assignment, new SeasonPass.XpChange(season, state, state.xp() - assignment.xp())));

				} else
					completions.add(new Completion(uuid, assignment, null));
			}

			return completions;

		}).handle((completions, error) -> {
			if (error != null) {
				// Nothing was committed: put the progress back for the next flush
				synchronized (this.lock) {
					drained.forEach((key, value) -> this.buffer.merge(key, value, Long::sum));
				}

				this.logger.warn("Could not save mission progress, will retry: {}", error.toString());
				return null;
			}

			this.announce(completions);
			return null;
		});
	}

	/**
	 * Tells the players about missions they finished, and about their new pass level.
	 */
	private void announce(final List<Completion> completions) {
		int sent = 0;

		for (final Completion completion : completions) {
			if (sent++ >= MAX_NOTIFICATIONS)
				break;

			final Assignment assignment = completion.assignment();
			final JsonObject data = new JsonObject();

			data.addProperty("uuid", completion.uuid().toString());
			data.addProperty("id", assignment.missionKey());
			data.addProperty("period", assignment.type().key());
			data.addProperty("titleEn", assignment.titleEn());
			data.addProperty("titlePl", assignment.titlePl());
			data.addProperty("xp", completion.xp() != null ? assignment.xp() : 0);

			this.network.notify(completion.uuid(), Protocol.MISSION_COMPLETED, data);

			if (completion.xp() != null)
				this.seasonPass.announce(completion.uuid(), completion.xp());
		}
	}

	/* ------------------------------------------------------------------------------- */
	/* Reading */
	/* ------------------------------------------------------------------------------- */

	/**
	 * The player's missions of the current periods, grouped as the website and /karnet show them.
	 */
	public CompletableFuture<JsonObject> listFor(final UUID uuid) {
		final List<Assignment> current = this.active;

		return this.database.submit(connection -> MissionStore.getProgress(connection, uuid, current.stream().map(Assignment::id).toList())).thenApply(progress -> {
			final Map<MissionPeriodType, List<Assignment>> byType = new EnumMap<>(MissionPeriodType.class);

			current.forEach(assignment -> byType.computeIfAbsent(assignment.type(), type -> new ArrayList<>()).add(assignment));

			final JsonObject json = new JsonObject();

			for (final MissionPeriodType type : MissionPeriodType.values()) {
				final List<Assignment> list = byType.getOrDefault(type, List.of());
				final JsonObject group = new JsonObject();
				final JsonArray items = new JsonArray();
				long resetsAt = 0;

				for (final Assignment assignment : list) {
					final MissionStore.Progress state = progress.getOrDefault(assignment.id(), MissionStore.Progress.NONE);
					final JsonObject item = new JsonObject();

					item.addProperty("id", assignment.missionKey());
					item.addProperty("titleEn", assignment.titleEn());
					item.addProperty("titlePl", assignment.titlePl());
					item.addProperty("unitEn", assignment.unitEn());
					item.addProperty("unitPl", assignment.unitPl());
					item.addProperty("progress", Math.min(state.progress(), assignment.target()));
					item.addProperty("target", assignment.target());
					item.addProperty("xp", assignment.xp());
					item.addProperty("completed", state.completedAt() != null);
					items.add(item);
					resetsAt = assignment.endsAt();
				}

				group.addProperty("resetsAt", resetsAt);
				group.add("items", items);
				json.add(type.key(), group);
			}

			return json;
		});
	}

	/* ------------------------------------------------------------------------------- */
	/* Admin */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Completes the current mission with this key for the player right away (admin).
	 *
	 * @return the mission, or fails with unknown_mission
	 */
	public CompletableFuture<Assignment> complete(final UUID uuid, final String missionKey) {
		final Assignment assignment = this.active.stream().filter(candidate -> candidate.missionKey().equalsIgnoreCase(missionKey)).findFirst().orElse(null);

		if (assignment == null)
			throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "No active mission \"" + missionKey + "\". Active: " + String.join(", ", this.active.stream().map(Assignment::missionKey).toList()));

		synchronized (this.lock) {
			this.buffer.merge(new Key(uuid, assignment.id()), assignment.target(), Long::sum);
		}

		return this.flush().thenApply(ignored -> assignment);
	}

	/**
	 * Adds (or sets) XP by hand. Goes through the ledger like everything else, so it is visible and never doubled.
	 *
	 * @param set true to set the total instead of adding
	 */
	public CompletableFuture<SeasonPass.XpChange> adminXp(final UUID uuid, final long amount, final boolean set) {
		final SeasonPassSettings season = this.seasonPass.getActiveSeason();

		if (season == null)
			throw new ServiceException(Protocol.ERROR_NO_SEASON, "No season pass is active");

		if (amount < 0 || amount > 1_000_000_000L)
			throw new IllegalArgumentException("XP must be between 0 and 1000000000");

		final boolean sqlite = this.database.isSqlite();

		return this.database.submit(connection -> {
			final PassState before = set ? SeasonPassStore.setXp(connection, season.id(), uuid, amount, sqlite) : SeasonPassStore.get(connection, season.id(), uuid);
			final PassState after = set ? SeasonPassStore.get(connection, season.id(), uuid) : SeasonPassStore.addXp(connection, season.id(), uuid, amount, sqlite);

			MissionStore.insertLedger(connection, uuid, season.id(), after.xp() - before.xp(), "admin", UUID.randomUUID().toString(), sqlite);
			return new SeasonPass.XpChange(season, after, before.xp());

		}).thenApply(change -> {
			this.seasonPass.announce(uuid, change);
			return change;
		});
	}

	/**
	 * @return the day a missions timestamp falls on, for tests and messages
	 */
	public LocalDate today() {
		return Instant.ofEpochSecond(this.clock.getAsLong()).atZone(this.settings.zone()).toLocalDate();
	}

	/**
	 * Groups the active missions by period for a status line.
	 */
	public Map<String, Integer> summary() {
		final Map<String, Integer> counts = new LinkedHashMap<>();

		for (final MissionPeriodType type : MissionPeriodType.values())
			counts.put(type.key(), (int) this.active.stream().filter(assignment -> assignment.type() == type).count());

		return counts;
	}
}
