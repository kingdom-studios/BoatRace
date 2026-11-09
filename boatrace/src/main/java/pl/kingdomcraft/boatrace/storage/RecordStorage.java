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
 *  - records    best time per (arena, laps, player) incl. splits and the server it was driven on (server_name);
 *               a 3-lap race has its own leaderboard, separate from the 1-lap one
 *  - runs       every finished race (history for the web panel), with server_name / server_group
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
	private static final String BLACKLIST = "boatrace_blacklist";
	private static final String REWARDS = "boatrace_rewards";

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

			st.execute("CREATE TABLE IF NOT EXISTS " + BLACKLIST + " ("
					+ "uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, server_name VARCHAR(64) NOT NULL DEFAULT '', created BIGINT NOT NULL"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

			st.execute("CREATE TABLE IF NOT EXISTS " + REWARDS + " ("
					+ "arena VARCHAR(64) NOT NULL, uuid CHAR(36) NOT NULL, server_name VARCHAR(64) NOT NULL DEFAULT '', claimed BIGINT NOT NULL, "
					+ "PRIMARY KEY (arena, uuid)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

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

		synchronized (this) {
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

	public synchronized RaceRecord getRecord(final String arena, final int laps, final UUID uuid) {
		final Map<UUID, RaceRecord> map = this.records.get(boardKey(arena, laps));

		return map == null ? null : map.get(uuid);
	}

	/** Sorted (fastest first) ranking without blacklisted players. The returned list is a read-only snapshot. */
	public synchronized List<RaceRecord> getTop(final String arena, final int laps) {
		final String arenaKey = boardKey(arena, laps);
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
	public synchronized int getRank(final String arena, final int laps, final UUID uuid) {
		final List<RaceRecord> top = this.getTop(arena, laps);

		for (int i = 0; i < top.size(); i++)
			if (top.get(i).getUuid().equals(uuid))
				return i + 1;

		return -1;
	}

	/**
	 * Saves the run if it is faster than the stored best (or there is none). Every finished run (banned players
	 * excluded) is also written to the runs history.
	 *
	 * @return true when it became the new personal best
	 */
	public synchronized boolean submitRun(final String arena, final int laps, final UUID uuid, final String name, final long timeMs, final long penaltyMs, final long[] splits) {
		if (this.blacklist.contains(uuid))
			return false;

		final String arenaKey = key(arena);
		final String board = boardKey(arena, laps);
		final RaceRecord old = this.getRecord(arena, laps, uuid);
		final boolean newBest = old == null || timeMs < old.getBestMs();
		final String joinedSplits = joinSplits(splits);
		final long now = System.currentTimeMillis();
		final String server = this.serverName;
		final String group = this.serverGroup;

		if (newBest) {
			this.records.computeIfAbsent(board, k -> new HashMap<>()).put(uuid, new RaceRecord(uuid, name, timeMs, splits, server));
			this.rankingCache.remove(board);
		}

		this.async(connection -> {
			try (PreparedStatement ps = connection.prepareStatement(upsertBestSql())) {
				ps.setString(1, arenaKey);
				ps.setInt(2, laps);
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
				ps.setInt(3, laps);
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

		return nowBlacklisted;
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
