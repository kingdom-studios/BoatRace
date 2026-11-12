package pl.kingdomcraft.captainhook.velocity.website;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;

import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition;
import pl.kingdomcraft.captainhook.velocity.database.Tables;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.pass.Track;
import pl.kingdomcraft.captainhook.velocity.reward.Reward;

/**
 * Copies achievements.yml and seasonpass.yml into the database (ch_achievement_definitions, ch_seasonpass_seasons,
 * ch_seasonpass_rewards) on every start and reload. The website reads them to show the season pass and the
 * achievements and to grant exactly the rewards configured here when a player claims one; it never reads the
 * YAML files.
 */
public final class DefinitionPublisher {

	private DefinitionPublisher() {
	}

	/**
	 * Call on the database thread, in one transaction.
	 */
	public static void publish(final Connection connection, final boolean sqlite, final SeasonPassSettings season,
			final Map<String, AchievementDefinition> achievements) throws SQLException {
		publishSeason(connection, sqlite, season);
		publishAchievements(connection, sqlite, achievements);
	}

	private static void publishSeason(final Connection connection, final boolean sqlite, final SeasonPassSettings season) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;

		try (PreparedStatement statement = connection.prepareStatement(upsert(Tables.SEASON_PASS_SEASONS,
				new String[] { "season", "name", "enabled", "starts_at", "ends_at", "xp_per_level", "max_level", "is_current", "updated_at",
						"xp_thresholds", "claim_until", "premium_price" }, 1, sqlite))) {
			statement.setString(1, season.id());
			statement.setString(2, truncate(season.name(), 128));
			statement.setBoolean(3, season.enabled());
			statement.setLong(4, season.startsAt());
			statement.setLong(5, season.endsAt());
			statement.setLong(6, season.xpPerLevel());
			statement.setInt(7, season.maxLevel());
			statement.setBoolean(8, true);
			statement.setLong(9, now);
			statement.setString(10, season.thresholds().toString().replace(" ", ""));
			statement.setLong(11, season.claimUntil());
			statement.setLong(12, season.premiumPrice());
			statement.executeUpdate();
		}

		// Exactly one current season: the one in seasonpass.yml
		try (PreparedStatement statement = connection.prepareStatement("UPDATE " + Tables.SEASON_PASS_SEASONS + " SET is_current = 0 WHERE season <> ?")) {
			statement.setString(1, season.id());
			statement.executeUpdate();
		}

		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + Tables.SEASON_PASS_REWARDS + " WHERE season = ?")) {
			statement.setString(1, season.id());
			statement.executeUpdate();
		}

		try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + Tables.SEASON_PASS_REWARDS
				+ " (season, tier, track, kingdomcoins, pass_xp, commands, message, require_server) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
			for (final SeasonPassSettings.Tier tier : season.tiers().values())
				for (final Track track : Track.values()) {
					final Reward reward = tier.reward(track);

					// A track without a reward has no row: nothing to show or claim
					if (reward.isEmpty())
						continue;

					statement.setString(1, season.id());
					statement.setInt(2, tier.level());
					statement.setString(3, track.key());
					statement.setLong(4, reward.kingdomCoins());
					statement.setLong(5, reward.passXp());
					statement.setString(6, commands(reward));
					statement.setString(7, reward.message());
					statement.setString(8, reward.requireServer());
					statement.addBatch();
				}

			statement.executeBatch();
		}
	}

	private static void publishAchievements(final Connection connection, final boolean sqlite, final Map<String, AchievementDefinition> achievements) throws SQLException {
		final long now = System.currentTimeMillis() / 1000;
		final String[] columns = { "id", "name", "description", "category", "icon", "hidden", "goal", "source_type", "source_game", "source_stat", "auto_reward",
				"kingdomcoins", "pass_xp", "commands", "message", "sort_order", "active", "updated_at", "require_server" };

		try (PreparedStatement statement = connection.prepareStatement(upsert(Tables.ACHIEVEMENT_DEFINITIONS, columns, 1, sqlite))) {
			int order = 0;

			for (final AchievementDefinition definition : achievements.values()) {
				final Reward reward = definition.reward();

				statement.setString(1, definition.id());
				statement.setString(2, truncate(definition.name(), 128));
				statement.setString(3, truncate(definition.description(), 255));
				statement.setString(4, truncate(definition.category(), 64));
				statement.setString(5, truncate(definition.icon(), 64));
				statement.setBoolean(6, definition.hidden());
				statement.setLong(7, definition.goal());
				statement.setString(8, definition.source().type().name().toLowerCase().replace('_', '-'));
				statement.setString(9, definition.source().game());
				statement.setString(10, definition.source().stat());
				statement.setBoolean(11, definition.autoReward());
				statement.setLong(12, reward.kingdomCoins());
				statement.setLong(13, reward.passXp());
				statement.setString(14, commands(reward));
				statement.setString(15, reward.message());
				statement.setInt(16, order++);
				statement.setBoolean(17, true);
				statement.setLong(18, now);
				statement.setString(19, reward.requireServer());
				statement.addBatch();
			}

			statement.executeBatch();
		}

		// Removed from the file: kept (players' rows point at it) but no longer shown or claimable
		final List<String> ids = new ArrayList<>(achievements.keySet());
		final String sql = "UPDATE " + Tables.ACHIEVEMENT_DEFINITIONS + " SET active = 0, updated_at = ?"
				+ (ids.isEmpty() ? "" : " WHERE id NOT IN (" + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")");

		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, now);

			for (int i = 0; i < ids.size(); i++)
				statement.setString(i + 2, ids.get(i));

			statement.executeUpdate();
		}
	}

	private static String commands(final Reward reward) {
		final JsonArray array = new JsonArray();

		reward.commands().forEach(array::add);
		return array.toString();
	}

	private static String truncate(final String value, final int length) {
		return value != null && value.length() > length ? value.substring(0, length) : value;
	}

	/**
	 * INSERT ... ON DUPLICATE KEY UPDATE (MySQL) / ON CONFLICT DO UPDATE (SQLite) over all columns, the first
	 * keyColumns being the primary key.
	 */
	private static String upsert(final String table, final String[] columns, final int keyColumns, final boolean sqlite) {
		final List<String> updates = new ArrayList<>();
		final List<String> keys = new ArrayList<>();

		for (int i = 0; i < columns.length; i++)
			if (i < keyColumns)
				keys.add(columns[i]);
			else
				updates.add(columns[i] + " = " + (sqlite ? "excluded." + columns[i] : "VALUES(" + columns[i] + ")"));

		return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", Collections.nCopies(columns.length, "?")) + ") "
				+ (sqlite ? "ON CONFLICT(" + String.join(", ", keys) + ") DO UPDATE SET " : "ON DUPLICATE KEY UPDATE ") + String.join(", ", updates);
	}
}
