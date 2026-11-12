package pl.kingdomcraft.captainhook.velocity.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and upgrades the tables. Tables are only ever extended, because the website reads them directly.
 */
public final class Schema {

	private Schema() {
	}

	public static void create(final Connection connection, final boolean sqlite) throws SQLException {
		final String autoIncrement = sqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
		final String nameColumn = sqlite ? "VARCHAR(16) NOT NULL COLLATE NOCASE" : "VARCHAR(16) NOT NULL";
		final String tableOptions = sqlite ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

		try (Statement statement = connection.createStatement()) {
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.COINS + " ("
					+ "uuid CHAR(36) NOT NULL PRIMARY KEY, "
					+ "name " + nameColumn + ", "
					+ "balance BIGINT NOT NULL DEFAULT 0, "
					+ "last_seen BIGINT NOT NULL)" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.COINS_LOG + " ("
					+ "id " + autoIncrement + ", "
					+ "uuid CHAR(36) NOT NULL, "
					+ "type VARCHAR(16) NOT NULL, "
					+ "amount BIGINT NOT NULL, "
					+ "balance_after BIGINT NOT NULL, "
					+ "actor VARCHAR(36) NOT NULL, "
					+ "server VARCHAR(64) NOT NULL, "
					+ "reason VARCHAR(255), "
					+ "created_at BIGINT NOT NULL)" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.STATS + " ("
					+ "server_id VARCHAR(64) NOT NULL, "
					+ "uuid CHAR(36) NOT NULL, "
					+ "nickname VARCHAR(16) NOT NULL, "
					+ "balance DECIMAL(20,2) NOT NULL DEFAULT 0, "
					+ "level INT NOT NULL DEFAULT 0, "
					+ "playtime_seconds BIGINT NOT NULL DEFAULT 0, "
					+ "blocks_placed BIGINT NOT NULL DEFAULT 0, "
					+ "player_kills INT NOT NULL DEFAULT 0, "
					+ "mob_kills INT NOT NULL DEFAULT 0, "
					+ "deaths INT NOT NULL DEFAULT 0, "
					+ "clan_tag VARCHAR(16), "
					+ "online BOOLEAN NOT NULL DEFAULT 0, "
					+ "vanished BOOLEAN NOT NULL DEFAULT 0, "
					+ "last_seen BIGINT NOT NULL, "
					+ "updated_at BIGINT NOT NULL, "
					+ "PRIMARY KEY (server_id, uuid))" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.STATS_TOTAL + " ("
					+ "uuid CHAR(36) NOT NULL PRIMARY KEY, "
					+ "nickname VARCHAR(16) NOT NULL, "
					+ "playtime_seconds BIGINT NOT NULL DEFAULT 0, "
					+ "blocks_placed BIGINT NOT NULL DEFAULT 0, "
					+ "player_kills BIGINT NOT NULL DEFAULT 0, "
					+ "mob_kills BIGINT NOT NULL DEFAULT 0, "
					+ "deaths BIGINT NOT NULL DEFAULT 0, "
					+ "online BOOLEAN NOT NULL DEFAULT 0, "
					+ "vanished BOOLEAN NOT NULL DEFAULT 0, "
					+ "last_seen BIGINT NOT NULL, "
					+ "updated_at BIGINT NOT NULL)" + tableOptions);

			// One row per (game, player, stat), so a new minigame or stat needs no schema change
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.MINIGAME_STATS + " ("
					+ "game VARCHAR(32) NOT NULL, "
					+ "uuid CHAR(36) NOT NULL, "
					+ "stat VARCHAR(32) NOT NULL, "
					+ "value BIGINT NOT NULL DEFAULT 0, "
					+ "updated_at BIGINT NOT NULL, "
					+ "PRIMARY KEY (game, uuid, stat))" + tableOptions);

			// One pending join per player: where the proxy is sending them and what to do with them on arrival
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.MINIGAME_PENDING_JOINS + " ("
					+ "uuid CHAR(36) NOT NULL PRIMARY KEY, "
					+ "game VARCHAR(32) NOT NULL, "
					+ "server VARCHAR(64) NOT NULL, "
					+ "arena VARCHAR(64), "
					+ "data TEXT, "
					+ "created_at BIGINT NOT NULL, "
					+ "expires_at BIGINT NOT NULL)" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.ACHIEVEMENTS + " ("
					+ "uuid CHAR(36) NOT NULL, "
					+ "achievement VARCHAR(64) NOT NULL, "
					+ "progress BIGINT NOT NULL DEFAULT 0, "
					+ "unlocked_at BIGINT, "
					+ "updated_at BIGINT NOT NULL, "
					+ "PRIMARY KEY (uuid, achievement))" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.SEASON_PASS + " ("
					+ "season VARCHAR(32) NOT NULL, "
					+ "uuid CHAR(36) NOT NULL, "
					+ "xp BIGINT NOT NULL DEFAULT 0, "
					+ "premium BOOLEAN NOT NULL DEFAULT 0, "
					+ "updated_at BIGINT NOT NULL, "
					+ "PRIMARY KEY (season, uuid))" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.SEASON_PASS_CLAIMS + " ("
					+ "season VARCHAR(32) NOT NULL, "
					+ "uuid CHAR(36) NOT NULL, "
					+ "tier INT NOT NULL, "
					+ "track VARCHAR(8) NOT NULL, "
					+ "source VARCHAR(16) NOT NULL, "
					+ "claimed_at BIGINT NOT NULL, "
					+ "PRIMARY KEY (season, uuid, tier, track))" + tableOptions);

			// Rewards that must run on a Paper server (commands). Rows are written here, delivery is phase 2.
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.REWARD_QUEUE + " ("
					+ "id " + autoIncrement + ", "
					+ "uuid CHAR(36) NOT NULL, "
					+ "source VARCHAR(96) NOT NULL, "
					+ "commands TEXT NOT NULL, "
					+ "message VARCHAR(255), "
					+ "created_at BIGINT NOT NULL, "
					+ "delivered_at BIGINT, "
					+ "delivered_server VARCHAR(64))" + tableOptions);

			// The configuration files as data, rewritten by the proxy on start and reload (see DefinitionPublisher).
			// Rewards are kept as columns, commands as a JSON array.
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.ACHIEVEMENT_DEFINITIONS + " ("
					+ "id VARCHAR(64) NOT NULL PRIMARY KEY, "
					+ "name VARCHAR(128) NOT NULL, "
					+ "description VARCHAR(255) NOT NULL, "
					+ "category VARCHAR(64) NOT NULL, "
					+ "icon VARCHAR(64) NOT NULL, "
					+ "hidden BOOLEAN NOT NULL DEFAULT 0, "
					+ "goal BIGINT NOT NULL, "
					+ "source_type VARCHAR(16) NOT NULL, "
					+ "source_game VARCHAR(32), "
					+ "source_stat VARCHAR(32), "
					+ "auto_reward BOOLEAN NOT NULL DEFAULT 0, "
					+ "kingdomcoins BIGINT NOT NULL DEFAULT 0, "
					+ "pass_xp BIGINT NOT NULL DEFAULT 0, "
					+ "commands TEXT NOT NULL, "
					+ "message VARCHAR(255), "
					+ "sort_order INT NOT NULL DEFAULT 0, "
					+ "active BOOLEAN NOT NULL DEFAULT 1, "
					+ "updated_at BIGINT NOT NULL)" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.SEASON_PASS_SEASONS + " ("
					+ "season VARCHAR(32) NOT NULL PRIMARY KEY, "
					+ "name VARCHAR(128) NOT NULL, "
					+ "enabled BOOLEAN NOT NULL DEFAULT 1, "
					+ "starts_at BIGINT NOT NULL DEFAULT 0, "
					+ "ends_at BIGINT NOT NULL, "
					+ "xp_per_level BIGINT NOT NULL, "
					+ "max_level INT NOT NULL, "
					+ "is_current BOOLEAN NOT NULL DEFAULT 0, "
					+ "updated_at BIGINT NOT NULL)" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.SEASON_PASS_REWARDS + " ("
					+ "season VARCHAR(32) NOT NULL, "
					+ "tier INT NOT NULL, "
					+ "track VARCHAR(8) NOT NULL, "
					+ "kingdomcoins BIGINT NOT NULL DEFAULT 0, "
					+ "pass_xp BIGINT NOT NULL DEFAULT 0, "
					+ "commands TEXT NOT NULL, "
					+ "message VARCHAR(255), "
					+ "PRIMARY KEY (season, tier, track))" + tableOptions);

			// The missions of a period, drawn once for everybody by the proxy. The website reads them.
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.MISSION_ASSIGNMENTS + " ("
					+ "id " + autoIncrement + ", "
					+ "season VARCHAR(32) NOT NULL, "
					+ "period_type VARCHAR(8) NOT NULL, "
					+ "period_key VARCHAR(24) NOT NULL, "
					+ "mission_key VARCHAR(64) NOT NULL, "
					+ "objective VARCHAR(32) NOT NULL, "
					+ "filter_json TEXT, "
					+ "target BIGINT NOT NULL, "
					+ "xp BIGINT NOT NULL, "
					+ "icon VARCHAR(16) NOT NULL, "
					+ "title_en VARCHAR(160) NOT NULL, "
					+ "title_pl VARCHAR(160) NOT NULL, "
					+ "unit_en VARCHAR(16), "
					+ "unit_pl VARCHAR(16), "
					+ "starts_at BIGINT NOT NULL, "
					+ "ends_at BIGINT, "
					+ "UNIQUE (season, period_type, period_key, mission_key))" + tableOptions);

			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.MISSION_PROGRESS + " ("
					+ "uuid CHAR(36) NOT NULL, "
					+ "assignment_id BIGINT NOT NULL, "
					+ "progress BIGINT NOT NULL DEFAULT 0, "
					+ "completed_at BIGINT, "
					+ "updated_at BIGINT NOT NULL, "
					+ "PRIMARY KEY (uuid, assignment_id))" + tableOptions);

			// One row per player and calendar day they logged in: LOGIN and DISTINCT_DAYS count each day once
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.MISSION_DAYS + " ("
					+ "uuid CHAR(36) NOT NULL, "
					+ "day CHAR(10) NOT NULL, "
					+ "PRIMARY KEY (uuid, day))" + tableOptions);

			// Every XP grant that must happen exactly once; the unique key is what makes a retry harmless
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + Tables.XP_LEDGER + " ("
					+ "id " + autoIncrement + ", "
					+ "uuid CHAR(36) NOT NULL, "
					+ "season VARCHAR(32) NOT NULL, "
					+ "amount BIGINT NOT NULL, "
					+ "source VARCHAR(24) NOT NULL, "
					+ "source_id VARCHAR(64) NOT NULL, "
					+ "created_at BIGINT NOT NULL, "
					+ "UNIQUE (uuid, source, source_id))" + tableOptions);

			// Columns added after the first release (tables created earlier lack them)
			addColumn(statement, sqlite, Tables.STATS, "vanished BOOLEAN NOT NULL DEFAULT 0");
			addColumn(statement, sqlite, Tables.STATS_TOTAL, "vanished BOOLEAN NOT NULL DEFAULT 0");
			// Set when the reward of an unlocked achievement was granted (right away, or when claimed)
			addColumn(statement, sqlite, Tables.ACHIEVEMENTS, "claimed_at BIGINT");
			// Season curve and premium for the website (xp_thresholds is a JSON array of cumulative XP per level)
			addColumn(statement, sqlite, Tables.SEASON_PASS_SEASONS, "xp_thresholds TEXT");
			addColumn(statement, sqlite, Tables.SEASON_PASS_SEASONS, "claim_until BIGINT");
			addColumn(statement, sqlite, Tables.SEASON_PASS_SEASONS, "premium_price BIGINT NOT NULL DEFAULT 0");
			// Commands that may only run on one server (Velocity name); NULL = any server
			addColumn(statement, sqlite, Tables.REWARD_QUEUE, "require_server VARCHAR(64)");
			addColumn(statement, sqlite, Tables.SEASON_PASS_REWARDS, "require_server VARCHAR(64)");
			addColumn(statement, sqlite, Tables.ACHIEVEMENT_DEFINITIONS, "require_server VARCHAR(64)");

			createIndex(statement, sqlite, "idx_ch_coins_balance", Tables.COINS, "balance");
			createIndex(statement, sqlite, "idx_ch_coins_name", Tables.COINS, "name");
			createIndex(statement, sqlite, "idx_ch_coins_log_uuid", Tables.COINS_LOG, "uuid, id");
			createIndex(statement, sqlite, "idx_ch_stats_uuid", Tables.STATS, "uuid");

			// One index per leaderboard so the website can read any top list without scanning
			for (final String column : new String[] { "playtime_seconds", "blocks_placed", "player_kills", "mob_kills", "deaths", "updated_at" })
				createIndex(statement, sqlite, "idx_ch_stats_total_" + column, Tables.STATS_TOTAL, column);

			createIndex(statement, sqlite, "idx_ch_minigame_stats_top", Tables.MINIGAME_STATS, "game, stat, value");
			createIndex(statement, sqlite, "idx_ch_minigame_stats_updated", Tables.MINIGAME_STATS, "updated_at");
			createIndex(statement, sqlite, "idx_ch_minigame_joins_expires", Tables.MINIGAME_PENDING_JOINS, "expires_at");
			createIndex(statement, sqlite, "idx_ch_achievements_unlocked", Tables.ACHIEVEMENTS, "achievement, unlocked_at");
			createIndex(statement, sqlite, "idx_ch_seasonpass_xp", Tables.SEASON_PASS, "season, xp");
			createIndex(statement, sqlite, "idx_ch_reward_queue_pending", Tables.REWARD_QUEUE, "uuid, delivered_at");
			createIndex(statement, sqlite, "idx_ch_mission_assignments_period", Tables.MISSION_ASSIGNMENTS, "season, period_type, starts_at");
			createIndex(statement, sqlite, "idx_ch_mission_progress_assignment", Tables.MISSION_PROGRESS, "assignment_id");
			createIndex(statement, sqlite, "idx_ch_xp_ledger_player", Tables.XP_LEDGER, "uuid, season");
		}
	}

	/**
	 * Adds a column to an existing table, ignoring "duplicate column" (MySQL 1060 / SQLite) when it is already there.
	 */
	private static void addColumn(final Statement statement, final boolean sqlite, final String table, final String definition) throws SQLException {
		try {
			statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + definition);

		} catch (final SQLException ex) {
			final boolean duplicate = sqlite ? String.valueOf(ex.getMessage()).contains("duplicate column") : ex.getErrorCode() == 1060;

			if (!duplicate)
				throw ex;
		}
	}

	/**
	 * MySQL has no CREATE INDEX IF NOT EXISTS, so a duplicate index error (1061) is expected there.
	 */
	private static void createIndex(final Statement statement, final boolean sqlite, final String name, final String table, final String columns) throws SQLException {
		try {
			statement.executeUpdate("CREATE INDEX " + (sqlite ? "IF NOT EXISTS " : "") + name + " ON " + table + " (" + columns + ")");

		} catch (final SQLException ex) {
			if (sqlite || ex.getErrorCode() != 1061)
				throw ex;
		}
	}
}
