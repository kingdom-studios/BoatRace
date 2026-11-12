package pl.kingdomcraft.boatrace.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Shared MariaDB storage (the same database the proxy uses), pooled with HikariCP.
 *
 * Tables (prefix "boatrace_"):
 *  - records    personal best per (arena, player) of the standard SINGLE-lap race incl. splits and the server it was
 *               driven on (server_name). Multi-lap races never touch it.
 *  - runs       every finished single-lap race (history for the web panel), with server_name / server_group
 *  - event_results  the total time of every finished MULTI-lap race (laps > 1): laps, total, penalty, finishing
 *               position / field size of events, server_name / server_group - the table for event leaderboards
 *  - blacklist  banned players (network wide)
 *  - rewards    one-time rewards already claimed
 *
 * Everything is cached in memory so lookups (PlaceholderAPI, rankings, checkpoint deltas) never touch the database.
 * All writes are queued onto a single IO thread (keeps their order), and the cache is reloaded every
 * records.refresh-seconds so times driven on other servers of the network show up too.
 * Writes are race-safe across servers: a best time is only ever replaced by a FASTER one (decided by the database).
 */
public final class RecordStorage {

	private interface SqlTask {
		void run(Connection connection) throws SQLException;
	}

	private static final String RECORDS = "boatrace_records";
	private static final String RUNS = "boatrace_runs";
	private static final String EVENT_RESULTS = "boatrace_event_results";

	/** Personal bests exist only for the standard single-lap race. */
	private static final int PB_LAPS = 1;
	private static final String BLACKLIST = "boatrace_blacklist";
	private static final String REWARDS = "boatrace_rewards";
	private static final String RATINGS = "boatrace_ratings";

	private final JavaPlugin plugin;
	private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "BoatRace-IO"));

	private HikariDataSource dataSource;
	private BukkitTask refreshTask;

	private String serverName = "";
	private String serverGroup = "";

	// arenaKey -> (player -> record). Guarded by "this".
	private Map<String, Map<UUID, RaceRecord>> records = new HashMap<>();
	private final Map<String, List<RaceRecord>> rankingCache = new HashMap<>();
	private Set<UUID> blacklist = new HashSet<>();
	/** "arenaKey|uuid" of players who already received the one-time reward of an arena. Guarded by "this". */
	private Set<String> claimedRewards = new HashSet<>();
	// player -> ranking. Guarded by "this".
	private Map<UUID, PlayerRating> ratings = new HashMap<>();
	/** ranking leaderboard, null = rebuild. Guarded by "this". */
	private List<PlayerRating> ratingTop;
	private int startRating = 1000;
	private int minRating = 100;
	private int leaderboardMinGames = 5;

	public RecordStorage(final JavaPlugin plugin) {
		this.plugin = plugin;
	}

	private static String key(final String arena) {
		return arena.toLowerCase(Locale.ROOT);
	}

	/** cache key of one leaderboard: "arena#laps" */
	private static String boardKey(final String arena, final int laps) {
		return key(arena) + "#" + Math.max(1, laps);
	}

	private static boolean isBoardOf(final String boardKey, final String arena) {
		return boardKey.startsWith(key(arena) + "#");
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	/**
	 * Connects to MariaDB (config sections Server + Database), creates / upgrades the tables, imports an old
	 * SQLite database.db once and fills the cache. Blocking - called once on startup.
	 */
	public void open(final ConfigurationSection config, final String serverName, final String serverGroup, final int refreshSeconds) throws SQLException {
		this.serverName = serverName == null ? "" : serverName;
		this.serverGroup = serverGroup == null ? "" : serverGroup;

		this.dataSource = new HikariDataSource(this.hikariConfig(config));

		try (Connection connection = this.dataSource.getConnection(); Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS " + RECORDS + " ("
					+ "arena VARCHAR(64) NOT NULL, laps INT NOT NULL DEFAULT 1, uuid CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
					+ "best_ms BIGINT NOT NULL, splits TEXT NOT NULL, server_name VARCHAR(64) NOT NULL DEFAULT '', updated BIGINT NOT NULL, "
					+ "PRIMARY KEY (arena, laps, uuid), KEY idx_arena_best (arena, laps, best_ms)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
			// tables created by an early version without the column
			st.execute("ALTER TABLE " + RECORDS + " ADD COLUMN IF NOT EXISTS server_name VARCHAR(64) NOT NULL DEFAULT '' AFTER splits");
			st.execute("ALTER TABLE " + RECORDS + " ADD COLUMN IF NOT EXISTS laps INT NOT NULL DEFAULT 1 AFTER arena");
			this.upgradeRecordsPrimaryKey(connection);

			st.execute("CREATE TABLE IF NOT EXISTS " + RUNS + " ("
					+ "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, arena VARCHAR(64) NOT NULL, uuid CHAR(36) NOT NULL, "
					+ "laps INT NOT NULL DEFAULT 1, name VARCHAR(32) NOT NULL, time_ms BIGINT NOT NULL, penalty_ms BIGINT NOT NULL DEFAULT 0, splits TEXT NOT NULL, "
					+ "personal_best TINYINT(1) NOT NULL DEFAULT 0, server_name VARCHAR(64) NOT NULL DEFAULT '', "
					+ "server_group VARCHAR(64) NOT NULL DEFAULT '', finished_at BIGINT NOT NULL, "
					+ "KEY idx_runs_arena_time (arena, time_ms), KEY idx_runs_uuid (uuid), KEY idx_runs_server (server_name)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

			st.execute("ALTER TABLE " + RUNS + " ADD COLUMN IF NOT EXISTS laps INT NOT NULL DEFAULT 1 AFTER uuid");

			st.execute("CREATE TABLE IF NOT EXISTS " + EVENT_RESULTS + " ("
					+ "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, arena VARCHAR(64) NOT NULL, laps INT NOT NULL, uuid CHAR(36) NOT NULL, "
					+ "name VARCHAR(32) NOT NULL, total_ms BIGINT NOT NULL, penalty_ms BIGINT NOT NULL DEFAULT 0, splits TEXT NOT NULL, "
					+ "finish_position INT NOT NULL DEFAULT 0, field_size INT NOT NULL DEFAULT 1, server_name VARCHAR(64) NOT NULL DEFAULT '', "
					+ "server_group VARCHAR(64) NOT NULL DEFAULT '', finished_at BIGINT NOT NULL, "
					+ "KEY idx_event_arena_total (arena, laps, total_ms), KEY idx_event_uuid (uuid), KEY idx_event_server (server_name)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

			st.execute("CREATE TABLE IF NOT EXISTS " + BLACKLIST + " ("
					+ "uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, server_name VARCHAR(64) NOT NULL DEFAULT '', created BIGINT NOT NULL"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

			st.execute("CREATE TABLE IF NOT EXISTS " + REWARDS + " ("
					+ "arena VARCHAR(64) NOT NULL, uuid CHAR(36) NOT NULL, server_name VARCHAR(64) NOT NULL DEFAULT '', claimed BIGINT NOT NULL, "
					+ "PRIMARY KEY (arena, uuid)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

			st.execute("CREATE TABLE IF NOT EXISTS " + RATINGS + " ("
					+ "uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, rating INT NOT NULL, games INT NOT NULL DEFAULT 0, "
					+ "wins INT NOT NULL DEFAULT 0, streak INT NOT NULL DEFAULT 0, best_streak INT NOT NULL DEFAULT 0, updated BIGINT NOT NULL, "
					+ "KEY idx_rating (rating)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
			// competitive counters (added later: existing rows keep everything)
			st.execute("ALTER TABLE " + RATINGS + " ADD COLUMN IF NOT EXISTS comp_games INT NOT NULL DEFAULT 0");
			st.execute("ALTER TABLE " + RATINGS + " ADD COLUMN IF NOT EXISTS comp_wins INT NOT NULL DEFAULT 0");

			this.importLegacySqlite(connection);
			this.loadCache(connection);
		}

		// periodic refresh: picks up times / bans / rewards from the other servers of the network
		final long period = Math.max(5, refreshSeconds) * 20L;

		this.refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, () -> this.async(this::loadCache), period, period);
	}

	/** Tables from the previous version had PRIMARY KEY (arena, uuid) - laps becomes part of it (existing rows = 1 lap). */
	private void upgradeRecordsPrimaryKey(final Connection connection) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' AND COLUMN_NAME = 'laps'")) {
			ps.setString(1, RECORDS);

			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next() && rs.getInt(1) > 0)
					return;
			}
		}

		try (Statement st = connection.createStatement()) {
			st.execute("ALTER TABLE " + RECORDS + " DROP PRIMARY KEY, ADD PRIMARY KEY (arena, laps, uuid)");
		}

		this.plugin.getLogger().info("Upgraded " + RECORDS + ": laps is now part of the primary key (separate leaderboards per lap count).");
	}

	private HikariConfig hikariConfig(final ConfigurationSection c) {
		final String host = c.getString("Database.Host", "localhost");
		final int port = c.getInt("Database.Port", 3306);
		final String database = c.getString("Database.Name", "minecraft");

		final int poolSize = Math.max(1, c.getInt("Database.Pool_Size", 6));
		final long timeout = Math.max(250L, c.getLong("Database.Connection_Timeout_Millis", 10_000L));
		long maxLifetime = c.getLong("Database.Max_Lifetime_Millis", 600_000L);
		long keepalive = c.getLong("Database.Keepalive_Millis", 60_000L);

		// Same rules HikariCP enforces, but with a clear message instead of silently changed values
		if (maxLifetime != 0L && maxLifetime < 30_000L) {
			this.plugin.getLogger().warning("Database.Max_Lifetime_Millis must be 0 or at least 30000 - using 600000.");
			maxLifetime = 600_000L;
		}

		if (keepalive != 0L && (keepalive < 30_000L || (maxLifetime != 0L && keepalive >= maxLifetime))) {
			this.plugin.getLogger().warning("Database.Keepalive_Millis must be 0 or 30000+ and below Max_Lifetime_Millis - keepalive disabled.");
			keepalive = 0L;
		}

		final HikariConfig config = new HikariConfig();

		config.setPoolName("BoatRace-MariaDB");
		config.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
		config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
		config.setUsername(c.getString("Database.User", "root"));
		config.setPassword(c.getString("Database.Password", ""));
		config.setMaximumPoolSize(poolSize);
		config.setMinimumIdle(Math.min(2, poolSize));
		config.setConnectionTimeout(timeout);
		config.setMaxLifetime(maxLifetime);
		config.setKeepaliveTime(keepalive);
		config.setIdleTimeout(maxLifetime == 0L ? 600_000L : Math.min(600_000L, Math.max(10_000L, maxLifetime - 1_000L)));

		return config;
	}

	/** Flushes queued writes and closes the pool. Call from onDisable. */
	public void close() {
		if (this.refreshTask != null)
			this.refreshTask.cancel();

		this.io.shutdown();

		try {
			if (!this.io.awaitTermination(15, TimeUnit.SECONDS))
				this.plugin.getLogger().warning("BoatRace database writes did not finish in time.");

		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}

		if (this.dataSource != null)
			this.dataSource.close();
	}

	private void async(final SqlTask task) {
		if (this.io.isShutdown() || this.dataSource == null)
			return;

		try {
			this.io.execute(() -> {
				try (Connection connection = this.dataSource.getConnection()) {
					task.run(connection);

				} catch (final SQLException ex) {
					this.plugin.getLogger().log(Level.SEVERE, "BoatRace database error", ex);
				}
			});

		} catch (final java.util.concurrent.RejectedExecutionException ignored) {
			// shutting down
		}
	}

	/** Reloads every table into fresh maps and swaps them in at once. */
	private void loadCache(final Connection connection) throws SQLException {
		final Map<String, Map<UUID, RaceRecord>> newRecords = new HashMap<>();
		final Set<UUID> newBlacklist = new HashSet<>();
		final Set<String> newRewards = new HashSet<>();

		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT arena, laps, uuid, name, best_ms, splits, server_name FROM " + RECORDS)) {
			while (rs.next())
				try {
					final UUID uuid = UUID.fromString(rs.getString("uuid"));
					final RaceRecord record = new RaceRecord(uuid, rs.getString("name"), rs.getLong("best_ms"),
							parseSplits(rs.getString("splits")), rs.getString("server_name"));

					newRecords.computeIfAbsent(boardKey(rs.getString("arena"), rs.getInt("laps")), k -> new HashMap<>()).put(uuid, record);

				} catch (final IllegalArgumentException ex) {
					this.plugin.getLogger().warning("Skipping corrupted record row: " + ex.getMessage());
				}
		}

		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid FROM " + BLACKLIST)) {
			while (rs.next())
				try {
					newBlacklist.add(UUID.fromString(rs.getString("uuid")));
				} catch (final IllegalArgumentException ignored) {
				}
		}

		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT arena, uuid FROM " + REWARDS)) {
			while (rs.next())
				newRewards.add(key(rs.getString("arena")) + "|" + rs.getString("uuid"));
		}

		final Map<UUID, PlayerRating> newRatings = new HashMap<>();

		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT uuid, name, rating, games, wins, streak, best_streak, comp_games, comp_wins FROM " + RATINGS)) {
			while (rs.next())
				try {
					final UUID uuid = UUID.fromString(rs.getString("uuid"));

					newRatings.put(uuid, new PlayerRating(uuid, rs.getString("name"), rs.getInt("rating"), rs.getInt("games"), rs.getInt("wins"),
							rs.getInt("streak"), rs.getInt("best_streak"), rs.getInt("comp_games"), rs.getInt("comp_wins")));

				} catch (final IllegalArgumentException ignored) {
				}
		}

		synchronized (this) {
			this.ratings = newRatings;
			this.ratingTop = null;
			this.records = newRecords;
			this.blacklist = newBlacklist;
			this.claimedRewards = newRewards;
			this.rankingCache.clear();
		}
	}

	/**
	 * One-time import of the SQLite database.db used by older versions. Existing (faster) MariaDB times win.
	 * The file is renamed to database.db.migrated afterwards.
	 */
	private void importLegacySqlite(final Connection maria) {
		final File file = new File(this.plugin.getDataFolder(), "database.db");

		if (!file.exists())
			return;

		try {
			Class.forName("org.sqlite.JDBC");

		} catch (final ClassNotFoundException ex) {
			this.plugin.getLogger().warning("Found an old database.db, but the SQLite driver is missing - it was not imported.");
			return;
		}

		int imported = 0;

		try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()); Statement st = sqlite.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT arena, uuid, name, best_ms, splits, updated FROM records");
					PreparedStatement ps = maria.prepareStatement(upsertBestSql())) {
				while (rs.next()) {
					ps.setString(1, key(rs.getString("arena")));
					ps.setInt(2, 1);
					ps.setString(3, rs.getString("uuid"));
					ps.setString(4, rs.getString("name"));
					ps.setLong(5, rs.getLong("best_ms"));
					ps.setString(6, rs.getString("splits") == null ? "" : rs.getString("splits"));
					ps.setString(7, this.serverName);
					ps.setLong(8, rs.getLong("updated"));
					ps.addBatch();
					imported++;
				}

				ps.executeBatch();
			}

			try (ResultSet rs = st.executeQuery("SELECT uuid, name FROM blacklist");
					PreparedStatement ps = maria.prepareStatement("INSERT IGNORE INTO " + BLACKLIST + " (uuid, name, server_name, created) VALUES (?, ?, ?, ?)")) {
				while (rs.next()) {
					ps.setString(1, rs.getString("uuid"));
					ps.setString(2, rs.getString("name"));
					ps.setString(3, this.serverName);
					ps.setLong(4, System.currentTimeMillis());
					ps.addBatch();
				}

				ps.executeBatch();
			}

			try (ResultSet rs = st.executeQuery("SELECT arena, uuid, claimed FROM rewards");
					PreparedStatement ps = maria.prepareStatement("INSERT IGNORE INTO " + REWARDS + " (arena, uuid, server_name, claimed) VALUES (?, ?, ?, ?)")) {
				while (rs.next()) {
					ps.setString(1, key(rs.getString("arena")));
					ps.setString(2, rs.getString("uuid"));
					ps.setString(3, this.serverName);
					ps.setLong(4, rs.getLong("claimed"));
					ps.addBatch();
				}

				ps.executeBatch();

			} catch (final SQLException ex) {
				// very old database.db files have no rewards table
			}

		} catch (final SQLException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Could not import the old database.db into MariaDB - it was left untouched.", ex);
			return;
		}

		final File done = new File(this.plugin.getDataFolder(), "database.db.migrated");

		if (!file.renameTo(done))
			this.plugin.getLogger().warning("Imported database.db but could not rename it - delete it to avoid a second import.");

		this.plugin.getLogger().info("Imported " + imported + " records from the old SQLite database.db into MariaDB.");
	}

	/** Insert, or replace the stored best ONLY when the new time is faster (decided in the database). */
	private static String upsertBestSql() {
		// MariaDB evaluates the assignments left to right, so best_ms must be updated last
		return "INSERT INTO " + RECORDS + " (arena, laps, uuid, name, best_ms, splits, server_name, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
				+ "ON DUPLICATE KEY UPDATE "
				+ "name = VALUES(name), "
				+ "splits = IF(VALUES(best_ms) < best_ms, VALUES(splits), splits), "
				+ "server_name = IF(VALUES(best_ms) < best_ms, VALUES(server_name), server_name), "
				+ "updated = IF(VALUES(best_ms) < best_ms, VALUES(updated), updated), "
				+ "best_ms = LEAST(best_ms, VALUES(best_ms))";
	}

	// ------------------------------------------------------------------------------------------
	// Records
	// ------------------------------------------------------------------------------------------

	/** The player's personal best (single-lap race). */
	public synchronized RaceRecord getRecord(final String arena, final UUID uuid) {
		final Map<UUID, RaceRecord> map = this.records.get(boardKey(arena, PB_LAPS));

		return map == null ? null : map.get(uuid);
	}

	/** Sorted (fastest first) ranking without blacklisted players. The returned list is a read-only snapshot. */
	public synchronized List<RaceRecord> getTop(final String arena) {
		final String arenaKey = boardKey(arena, PB_LAPS);
		List<RaceRecord> cached = this.rankingCache.get(arenaKey);

		if (cached == null) {
			final List<RaceRecord> list = new ArrayList<>();
			final Map<UUID, RaceRecord> map = this.records.get(arenaKey);

			if (map != null)
				for (final RaceRecord record : map.values())
					if (!this.blacklist.contains(record.getUuid()))
						list.add(record);

			list.sort(Comparator.comparingLong(RaceRecord::getBestMs));

			cached = Collections.unmodifiableList(list);
			this.rankingCache.put(arenaKey, cached);
		}

		return cached;
	}

	/** @return 1-based position or -1 when the player is not ranked */
	public synchronized int getRank(final String arena, final UUID uuid) {
		final List<RaceRecord> top = this.getTop(arena);

		for (int i = 0; i < top.size(); i++)
			if (top.get(i).getUuid().equals(uuid))
				return i + 1;

		return -1;
	}

	/**
	 * Stores a finished race.
	 *
	 * Single-lap race (laps = 1): saved as the personal best if it is faster than the stored one (or there is none),
	 * and written to the runs history.
	 * Multi-lap race (laps &gt; 1): only the TOTAL time is written, to the event results table - never a personal best,
	 * never the runs history.
	 * Banned players are not stored at all.
	 *
	 * @param finishPosition position in the event / party (0 = solo race)
	 * @param fieldSize      racers that started together (1 = solo race)
	 * @return true when it became a new personal best (always false for multi-lap races)
	 */
	public synchronized boolean submitRun(final String arena, final int laps, final UUID uuid, final String name, final long timeMs, final long penaltyMs,
			final long[] splits, final int finishPosition, final int fieldSize) {
		if (this.blacklist.contains(uuid))
			return false;

		final String arenaKey = key(arena);
		final String joinedSplits = joinSplits(splits);
		final long now = System.currentTimeMillis();
		final String server = this.serverName;
		final String group = this.serverGroup;

		if (laps > PB_LAPS) {
			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + EVENT_RESULTS
						+ " (arena, laps, uuid, name, total_ms, penalty_ms, splits, finish_position, field_size, server_name, server_group, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
					ps.setString(1, arenaKey);
					ps.setInt(2, laps);
					ps.setString(3, uuid.toString());
					ps.setString(4, name);
					ps.setLong(5, timeMs);
					ps.setLong(6, penaltyMs);
					ps.setString(7, joinedSplits);
					ps.setInt(8, finishPosition);
					ps.setInt(9, fieldSize);
					ps.setString(10, server);
					ps.setString(11, group);
					ps.setLong(12, now);
					ps.executeUpdate();
				}
			});

			return false;
		}

		final String board = boardKey(arena, PB_LAPS);
		final RaceRecord old = this.getRecord(arena, uuid);
		final boolean newBest = old == null || timeMs < old.getBestMs();

		if (newBest) {
			this.records.computeIfAbsent(board, k -> new HashMap<>()).put(uuid, new RaceRecord(uuid, name, timeMs, splits, server));
			this.rankingCache.remove(board);
		}

		this.async(connection -> {
			try (PreparedStatement ps = connection.prepareStatement(upsertBestSql())) {
				ps.setString(1, arenaKey);
				ps.setInt(2, PB_LAPS);
				ps.setString(3, uuid.toString());
				ps.setString(4, name);
				ps.setLong(5, timeMs);
				ps.setString(6, joinedSplits);
				ps.setString(7, server);
				ps.setLong(8, now);
				ps.executeUpdate();
			}

			try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + RUNS
					+ " (arena, uuid, laps, name, time_ms, penalty_ms, splits, personal_best, server_name, server_group, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				ps.setString(1, arenaKey);
				ps.setString(2, uuid.toString());
				ps.setInt(3, PB_LAPS);
				ps.setString(4, name);
				ps.setLong(5, timeMs);
				ps.setLong(6, penaltyMs);
				ps.setString(7, joinedSplits);
				ps.setBoolean(8, newBest);
				ps.setString(9, server);
				ps.setString(10, group);
				ps.setLong(11, now);
				ps.executeUpdate();
			}
		});

		return newBest;
	}

	/**
	 * Adds (or, with a negative amount, removes) time on the player's best times. Relative in the database, so it is
	 * safe even when another server changed the record meanwhile.
	 *
	 * @param arena null = all arenas (every lap count of the arena is affected)
	 * @return how many records changed
	 */
	public synchronized int adjustTime(final UUID uuid, final String arena, final long deltaMs) {
		int changed = 0;

		for (final Map.Entry<String, Map<UUID, RaceRecord>> entry : this.records.entrySet()) {
			if (arena != null && !isBoardOf(entry.getKey(), arena))
				continue;

			final RaceRecord old = entry.getValue().get(uuid);

			if (old == null)
				continue;

			entry.getValue().put(uuid, old.withBestMs(Math.max(1L, old.getBestMs() + deltaMs)));
			this.rankingCache.remove(entry.getKey());
			changed++;
		}

		if (changed > 0)
			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("UPDATE " + RECORDS + " SET best_ms = GREATEST(1, best_ms + ?), updated = ? WHERE uuid = ?"
						+ (arena == null ? "" : " AND arena = ?"))) {
					ps.setLong(1, deltaMs);
					ps.setLong(2, System.currentTimeMillis());
					ps.setString(3, uuid.toString());

					if (arena != null)
						ps.setString(4, key(arena));

					ps.executeUpdate();
				}
			});

		return changed;
	}

	/**
	 * Deletes the player's best times (the runs history is kept).
	 *
	 * @param arena null = all arenas (every lap count of the arena is affected)
	 * @return how many records were removed
	 */
	public synchronized int resetTimes(final UUID uuid, final String arena) {
		int removed = 0;

		for (final Map.Entry<String, Map<UUID, RaceRecord>> entry : this.records.entrySet()) {
			if (arena != null && !isBoardOf(entry.getKey(), arena))
				continue;

			if (entry.getValue().remove(uuid) != null) {
				removed++;
				this.rankingCache.remove(entry.getKey());
			}
		}

		if (removed > 0)
			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + RECORDS + " WHERE uuid = ?" + (arena == null ? "" : " AND arena = ?"))) {
					ps.setString(1, uuid.toString());

					if (arena != null)
						ps.setString(2, key(arena));

					ps.executeUpdate();
				}
			});

		return removed;
	}

	// ------------------------------------------------------------------------------------------
	// Blacklist
	// ------------------------------------------------------------------------------------------

	public synchronized boolean isBlacklisted(final UUID uuid) {
		return this.blacklist.contains(uuid);
	}

	/** @return true if the player is blacklisted AFTER the call, false if they were removed from it */
	public synchronized boolean toggleBlacklist(final UUID uuid, final String name) {
		final boolean nowBlacklisted;
		final String server = this.serverName;

		if (this.blacklist.remove(uuid)) {
			nowBlacklisted = false;

			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + BLACKLIST + " WHERE uuid = ?")) {
					ps.setString(1, uuid.toString());
					ps.executeUpdate();
				}
			});

		} else {
			nowBlacklisted = true;
			this.blacklist.add(uuid);

			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO " + BLACKLIST + " (uuid, name, server_name, created) VALUES (?, ?, ?, ?)")) {
					ps.setString(1, uuid.toString());
					ps.setString(2, name);
					ps.setString(3, server);
					ps.setLong(4, System.currentTimeMillis());
					ps.executeUpdate();
				}
			});
		}

		this.rankingCache.clear();
		this.ratingTop = null;

		return nowBlacklisted;
	}

	// ------------------------------------------------------------------------------------------
	// Ranking (ELO-like points of ranked races)
	// ------------------------------------------------------------------------------------------

	/** Values of config.yml "ranking", applied on load / reload. */
	public synchronized void configureRanking(final int startRating, final int minRating, final int leaderboardMinGames) {
		this.startRating = startRating;
		this.minRating = minRating;
		this.leaderboardMinGames = leaderboardMinGames;
		this.ratingTop = null;
	}

	/** The player's ranking; a player who never raced a ranked race has the start rating. Never null. */
	public synchronized PlayerRating getRating(final UUID uuid, final String name) {
		final PlayerRating rating = this.ratings.get(uuid);

		return rating != null ? rating : PlayerRating.fresh(uuid, name, this.startRating);
	}

	/** @return the cached ranking, or null if the player never raced a ranked race */
	public synchronized PlayerRating findRating(final UUID uuid) {
		return this.ratings.get(uuid);
	}

	/**
	 * Adds points (negative = takes them) to several players at once. The database update is relative
	 * (rating = rating + ?), so races settled on other servers at the same moment never overwrite each other.
	 *
	 * @param names player names by UUID (for the leaderboard)
	 */
	public synchronized void addRatings(final Map<UUID, Integer> deltas, final Map<UUID, String> names) {
		final int start = this.startRating;
		final int floor = this.minRating;
		final long now = System.currentTimeMillis();

		for (final Map.Entry<UUID, Integer> entry : deltas.entrySet()) {
			final UUID uuid = entry.getKey();
			final String name = names.getOrDefault(uuid, "?");
			final PlayerRating old = this.getRating(uuid, name);

			this.ratings.put(uuid, old.withRating(name, Math.max(floor, old.rating() + entry.getValue())));
		}

		this.ratingTop = null;

		final Map<UUID, Integer> copy = new HashMap<>(deltas);
		final Map<UUID, String> namesCopy = new HashMap<>(names);

		this.async(connection -> {
			try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + RATINGS + " (uuid, name, rating, updated) VALUES (?, ?, GREATEST(?, ?), ?) "
					+ "ON DUPLICATE KEY UPDATE name = VALUES(name), rating = GREATEST(?, rating + ?), updated = VALUES(updated)")) {
				for (final Map.Entry<UUID, Integer> entry : copy.entrySet()) {
					ps.setString(1, entry.getKey().toString());
					ps.setString(2, namesCopy.getOrDefault(entry.getKey(), "?"));
					ps.setInt(3, floor);
					ps.setInt(4, start + entry.getValue());
					ps.setLong(5, now);
					ps.setInt(6, floor);
					ps.setInt(7, entry.getValue());
					ps.addBatch();
				}

				ps.executeBatch();
			}
		});
	}

	/**
	 * Counts a ranked race of the player: games, wins and the "not last" streak.
	 *
	 * @param notLast finished and not on the last place (false for a last place and for leaving the race)
	 * @return the player's ranking after the race
	 */
	public synchronized PlayerRating recordRankedResult(final UUID uuid, final String name, final boolean notLast, final boolean win) {
		final PlayerRating old = this.getRating(uuid, name);
		final int streak = notLast ? old.streak() + 1 : 0;
		final PlayerRating updated = old.withRankedResult(name, win, streak);

		this.ratings.put(uuid, updated);
		this.ratingTop = null;

		final int start = this.startRating;
		final long now = System.currentTimeMillis();

		this.async(connection -> {
			// MariaDB assigns left to right: best_streak is computed from the OLD streak, then streak is changed
			try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + RATINGS + " (uuid, name, rating, games, wins, streak, best_streak, updated) "
					+ "VALUES (?, ?, ?, 1, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), games = games + 1, wins = wins + VALUES(wins), "
					+ "best_streak = GREATEST(best_streak, IF(?, streak + 1, 0)), streak = IF(?, streak + 1, 0), updated = VALUES(updated)")) {
				ps.setString(1, uuid.toString());
				ps.setString(2, name);
				ps.setInt(3, start);
				ps.setInt(4, win ? 1 : 0);
				ps.setInt(5, notLast ? 1 : 0);
				ps.setInt(6, notLast ? 1 : 0);
				ps.setLong(7, now);
				ps.setBoolean(8, notLast);
				ps.setBoolean(9, notLast);
				ps.executeUpdate();
			}
		});

		return updated;
	}

	/**
	 * Counts a competitive match (the rating itself changes through {@link #addRatings}).
	 *
	 * @param winners members of the winning team
	 */
	public synchronized void recordCompetitiveResult(final java.util.Collection<UUID> members, final Map<UUID, String> names, final Set<UUID> winners) {
		final int start = this.startRating;
		final long now = System.currentTimeMillis();

		for (final UUID uuid : members) {
			final String name = names.getOrDefault(uuid, "?");

			this.ratings.put(uuid, this.getRating(uuid, name).withCompetitiveResult(name, winners.contains(uuid)));
		}

		final List<UUID> copy = new ArrayList<>(members);
		final Map<UUID, String> namesCopy = new HashMap<>(names);
		final Set<UUID> winnersCopy = new HashSet<>(winners);

		this.async(connection -> {
			try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + RATINGS + " (uuid, name, rating, comp_games, comp_wins, updated) "
					+ "VALUES (?, ?, ?, 1, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), comp_games = comp_games + 1, "
					+ "comp_wins = comp_wins + VALUES(comp_wins), updated = VALUES(updated)")) {
				for (final UUID uuid : copy) {
					ps.setString(1, uuid.toString());
					ps.setString(2, namesCopy.getOrDefault(uuid, "?"));
					ps.setInt(3, start);
					ps.setInt(4, winnersCopy.contains(uuid) ? 1 : 0);
					ps.setLong(5, now);
					ps.addBatch();
				}

				ps.executeBatch();
			}
		});
	}

	/**
	 * Ranking leaderboard, best first: players with at least ranking.leaderboard-min-games ranked races, without the
	 * blacklisted ones.
	 */
	public synchronized List<PlayerRating> getRatingTop() {
		if (this.ratingTop == null)
			this.ratingTop = this.ratings.values().stream()
					.filter(rating -> rating.games() >= this.leaderboardMinGames && !this.blacklist.contains(rating.uuid()))
					.sorted(java.util.Comparator.comparingInt(PlayerRating::rating).reversed().thenComparing(PlayerRating::name, String.CASE_INSENSITIVE_ORDER))
					.toList();

		return this.ratingTop;
	}

	/** @return 1-based position on the ranking leaderboard, 0 if the player is not on it */
	public synchronized int getRatingRank(final UUID uuid) {
		final List<PlayerRating> top = this.getRatingTop();

		for (int i = 0; i < top.size(); i++)
			if (top.get(i).uuid().equals(uuid))
				return i + 1;

		return 0;
	}

	// ------------------------------------------------------------------------------------------
	// One-time rewards
	// ------------------------------------------------------------------------------------------

	/** Fast cache check, no database access. */
	public synchronized boolean hasClaimedReward(final String arena, final UUID uuid) {
		return this.claimedRewards.contains(key(arena) + "|" + uuid);
	}

	/**
	 * Claims the one-time reward of the arena. The database decides (INSERT IGNORE), so a player can never get it
	 * twice - not even when finishing on two servers of the network at the same moment.
	 *
	 * @param onClaimed run on the MAIN thread only when this call actually claimed it
	 */
	public void claimOnceReward(final String arena, final UUID uuid, final Runnable onClaimed) {
		final String arenaKey = key(arena);

		synchronized (this) {
			if (!this.claimedRewards.add(arenaKey + "|" + uuid))
				return;
		}

		final String server = this.serverName;

		this.async(connection -> {
			try (PreparedStatement ps = connection.prepareStatement("INSERT IGNORE INTO " + REWARDS + " (arena, uuid, server_name, claimed) VALUES (?, ?, ?, ?)")) {
				ps.setString(1, arenaKey);
				ps.setString(2, uuid.toString());
				ps.setString(3, server);
				ps.setLong(4, System.currentTimeMillis());

				if (ps.executeUpdate() > 0)
					this.mainThread(ignored -> onClaimed.run());
			}
		});
	}

	private void mainThread(final Consumer<Void> action) {
		if (this.plugin.isEnabled())
			Bukkit.getScheduler().runTask(this.plugin, () -> action.accept(null));
	}

	// ------------------------------------------------------------------------------------------
	// Splits (de)serialization
	// ------------------------------------------------------------------------------------------

	private static String joinSplits(final long[] splits) {
		final StringBuilder builder = new StringBuilder();

		for (int i = 0; i < splits.length; i++) {
			if (i > 0)
				builder.append(',');

			builder.append(splits[i]);
		}

		return builder.toString();
	}

	private static long[] parseSplits(final String raw) {
		if (raw == null || raw.isEmpty())
			return new long[0];

		final String[] parts = raw.split(",");
		final long[] result = new long[parts.length];

		for (int i = 0; i < parts.length; i++)
			result[i] = Long.parseLong(parts[i].trim());

		return result;
	}
}
