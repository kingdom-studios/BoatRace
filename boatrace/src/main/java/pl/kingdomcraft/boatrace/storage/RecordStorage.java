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
import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Flat-file SQLite storage (plugins/BoatRace/database.db).
 *
 * Everything is cached in memory so lookups (PlaceholderAPI, rankings, checkpoint deltas) never touch the disk.
 * All writes are queued onto a single IO thread, so the server thread never waits for SQLite.
 */
public final class RecordStorage {

	private interface SqlTask {
		void run(Connection connection) throws SQLException;
	}

	private final JavaPlugin plugin;
	private final File dbFile;
	private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "BoatRace-IO"));

	/** Only touched by open() (before any async task is queued) and then exclusively by the IO thread. */
	private Connection connection;

	// arenaKey -> (player -> record). Guarded by "this".
	private final Map<String, Map<UUID, RaceRecord>> records = new HashMap<>();
	private final Map<String, List<RaceRecord>> rankingCache = new HashMap<>();
	private final Set<UUID> blacklist = new HashSet<>();

	public RecordStorage(final JavaPlugin plugin) {
		this.plugin = plugin;
		this.dbFile = new File(plugin.getDataFolder(), "database.db");
	}

	private static String key(final String arena) {
		return arena.toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	public synchronized void open() throws SQLException, ClassNotFoundException {
		// The SQLite JDBC driver is shipped with Paper (server libraries).
		Class.forName("org.sqlite.JDBC");

		this.plugin.getDataFolder().mkdirs();
		this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile.getAbsolutePath());

		try (Statement st = this.connection.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS records ("
					+ "arena TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, best_ms INTEGER NOT NULL, "
					+ "splits TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL, PRIMARY KEY (arena, uuid))");
			st.execute("CREATE TABLE IF NOT EXISTS blacklist (uuid TEXT PRIMARY KEY, name TEXT NOT NULL)");
		}

		this.records.clear();
		this.blacklist.clear();
		this.rankingCache.clear();

		try (Statement st = this.connection.createStatement(); ResultSet rs = st.executeQuery("SELECT arena, uuid, name, best_ms, splits FROM records")) {
			while (rs.next())
				try {
					final String arena = rs.getString("arena");
					final UUID uuid = UUID.fromString(rs.getString("uuid"));
					final RaceRecord record = new RaceRecord(uuid, rs.getString("name"), rs.getLong("best_ms"), parseSplits(rs.getString("splits")));

					this.records.computeIfAbsent(arena, k -> new HashMap<>()).put(uuid, record);

				} catch (final IllegalArgumentException ex) {
					this.plugin.getLogger().warning("Skipping corrupted record row: " + ex.getMessage());
				}
		}

		try (Statement st = this.connection.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid FROM blacklist")) {
			while (rs.next())
				try {
					this.blacklist.add(UUID.fromString(rs.getString("uuid")));
				} catch (final IllegalArgumentException ignored) {
				}
		}
	}

	/** Flushes queued writes and closes the database. Call from onDisable. */
	public void close() {
		this.io.shutdown();

		try {
			if (!this.io.awaitTermination(15, TimeUnit.SECONDS))
				this.plugin.getLogger().warning("BoatRace database writes did not finish in time.");

		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}

		try {
			if (this.connection != null)
				this.connection.close();

		} catch (final SQLException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Could not close the database", ex);
		}
	}

	private void async(final SqlTask task) {
		if (this.io.isShutdown())
			return;

		this.io.execute(() -> {
			try {
				task.run(this.connection);

			} catch (final SQLException ex) {
				this.plugin.getLogger().log(Level.SEVERE, "BoatRace database error", ex);
			}
		});
	}

	// ------------------------------------------------------------------------------------------
	// Records
	// ------------------------------------------------------------------------------------------

	public synchronized RaceRecord getRecord(final String arena, final UUID uuid) {
		final Map<UUID, RaceRecord> map = this.records.get(key(arena));

		return map == null ? null : map.get(uuid);
	}

	/** Sorted (fastest first) ranking without blacklisted players. The returned list is a read-only snapshot. */
	public synchronized List<RaceRecord> getTop(final String arena) {
		final String arenaKey = key(arena);
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
	 * Saves the run if it is faster than the stored best (or there is none).
	 *
	 * @return true when it became the new personal best
	 */
	public synchronized boolean submitIfBetter(final String arena, final UUID uuid, final String name, final long timeMs, final long[] splits) {
		if (this.blacklist.contains(uuid))
			return false;

		final RaceRecord old = this.getRecord(arena, uuid);

		if (old != null && old.getBestMs() <= timeMs)
			return false;

		this.put(arena, new RaceRecord(uuid, name, timeMs, splits));

		return true;
	}

	/**
	 * Adds (or, with a negative amount, removes) time on the player's best times.
	 *
	 * @param arena null = all arenas
	 * @return how many records changed
	 */
	public synchronized int adjustTime(final UUID uuid, final String arena, final long deltaMs) {
		int changed = 0;

		for (final Map.Entry<String, Map<UUID, RaceRecord>> entry : new ArrayList<>(this.records.entrySet())) {
			if (arena != null && !entry.getKey().equals(key(arena)))
				continue;

			final RaceRecord old = entry.getValue().get(uuid);

			if (old == null)
				continue;

			this.put(entry.getKey(), old.withBestMs(Math.max(1L, old.getBestMs() + deltaMs)));
			changed++;
		}

		return changed;
	}

	/**
	 * Deletes the player's times.
	 *
	 * @param arena null = all arenas
	 * @return how many records were removed
	 */
	public synchronized int resetTimes(final UUID uuid, final String arena) {
		int removed = 0;

		for (final Map.Entry<String, Map<UUID, RaceRecord>> entry : new ArrayList<>(this.records.entrySet())) {
			if (arena != null && !entry.getKey().equals(key(arena)))
				continue;

			if (entry.getValue().remove(uuid) != null) {
				removed++;
				this.rankingCache.remove(entry.getKey());

				final String arenaKey = entry.getKey();

				this.async(connection -> {
					try (PreparedStatement ps = connection.prepareStatement("DELETE FROM records WHERE arena = ? AND uuid = ?")) {
						ps.setString(1, arenaKey);
						ps.setString(2, uuid.toString());
						ps.executeUpdate();
					}
				});
			}
		}

		return removed;
	}

	private void put(final String arenaKey, final RaceRecord record) {
		this.records.computeIfAbsent(arenaKey, k -> new HashMap<>()).put(record.getUuid(), record);
		this.rankingCache.remove(arenaKey);

		final String splits = joinSplits(record.getSplits());
		final long now = System.currentTimeMillis();

		this.async(connection -> {
			try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO records (arena, uuid, name, best_ms, splits, updated) VALUES (?, ?, ?, ?, ?, ?)")) {
				ps.setString(1, arenaKey);
				ps.setString(2, record.getUuid().toString());
				ps.setString(3, record.getName());
				ps.setLong(4, record.getBestMs());
				ps.setString(5, splits);
				ps.setLong(6, now);
				ps.executeUpdate();
			}
		});
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

		if (this.blacklist.remove(uuid)) {
			nowBlacklisted = false;

			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("DELETE FROM blacklist WHERE uuid = ?")) {
					ps.setString(1, uuid.toString());
					ps.executeUpdate();
				}
			});

		} else {
			nowBlacklisted = true;
			this.blacklist.add(uuid);

			this.async(connection -> {
				try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO blacklist (uuid, name) VALUES (?, ?)")) {
					ps.setString(1, uuid.toString());
					ps.setString(2, name);
					ps.executeUpdate();
				}
			});
		}

		this.rankingCache.clear();

		return nowBlacklisted;
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
