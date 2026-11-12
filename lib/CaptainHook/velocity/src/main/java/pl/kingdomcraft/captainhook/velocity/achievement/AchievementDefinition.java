package pl.kingdomcraft.captainhook.velocity.achievement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.reward.Reward;

/**
 * One achievement of achievements.yml.
 *
 * @param id     key in the database (ch_achievements.achievement), never rename it once players have progress
 * @param icon   free text for whoever displays the achievement (website, menus), e.g. a material name
 * @param hidden true if the achievement should not be shown before it is unlocked
 * @param goal   progress needed to unlock
 * @param autoReward true to grant the reward the moment the achievement unlocks, false to wait until the player
 *                   claims it (on the website)
 */
public record AchievementDefinition(String id, String name, String description, String category, String icon, boolean hidden, long goal,
		Source source, Reward reward, boolean autoReward) {

	public static final String FILE = "achievements.yml";

	public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");
	private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

	/**
	 * Columns of ch_player_stats_total an achievement can follow.
	 */
	public static final Set<String> NETWORK_STATS = Set.of("playtime_seconds", "blocks_placed", "player_kills", "mob_kills", "deaths");

	public enum SourceType {
		/**
		 * Progress is only added through CaptainHookAPI.addAchievementProgress.
		 */
		MANUAL,
		/**
		 * Progress is the player's network-wide total of a stat.
		 */
		NETWORK_STAT,
		/**
		 * Progress is the player's value of a minigame stat.
		 */
		MINIGAME_STAT
	}

	/**
	 * Where progress comes from.
	 *
	 * @param game   the minigame, only for MINIGAME_STAT
	 * @param stat   the stat, null for MANUAL
	 * @param atMost only for MINIGAME_STAT, null otherwise: the achievement is a threshold for a stat where LOWER is
	 *               better (a best time kept with the "min" operation). It unlocks once the player HAS the stat and
	 *               its value is at most this number; progress is then the goal, before that 0.
	 */
	public record Source(SourceType type, String game, String stat, Long atMost) {

		public static final Source MANUAL = new Source(SourceType.MANUAL, null, null, null);

		/**
		 * @param value the player's value of the stat, null if the player does not have it (yet)
		 * @param goal  the goal of the achievement
		 * @return the progress this value means
		 */
		public long progressOf(final Long value, final long goal) {
			if (this.atMost == null)
				return value != null ? value : 0L;

			return value != null && value <= this.atMost ? goal : 0L;
		}
	}

	public boolean isManual() {
		return this.source.type() == SourceType.MANUAL;
	}

	/**
	 * @return all achievements of the file by ID, in file order
	 */
	public static Map<String, AchievementDefinition> parseAll(final YamlFile file) throws ConfigException {
		final Map<String, AchievementDefinition> definitions = new LinkedHashMap<>();
		final YamlFile achievements = file.section("achievements");
		final boolean autoReward = parseRewardMode(file, true);

		for (final String id : achievements.keys()) {
			if (!ID_PATTERN.matcher(id).matches())
				throw achievements.invalid(id, "is not a valid ID: use a-z, 0-9 and _ (at most 64 characters)");

			definitions.put(id, parse(id, achievements.section(id), autoReward));
		}

		return definitions;
	}

	/**
	 * @param fallback the mode when the key is missing, true = automatic
	 * @return true for "automatic", false for "claim"
	 */
	private static boolean parseRewardMode(final YamlFile section, final boolean fallback) throws ConfigException {
		final String mode = section.getString("reward-mode", fallback ? "automatic" : "claim");

		if (!mode.equals("automatic") && !mode.equals("claim"))
			throw section.invalid("reward-mode", "must be automatic or claim");

		return mode.equals("automatic");
	}

	private static AchievementDefinition parse(final String id, final YamlFile section, final boolean defaultAutoReward) throws ConfigException {
		final long goal = section.getLong("goal", 1);

		if (goal < 1)
			throw section.invalid("goal", "must be at least 1");

		return new AchievementDefinition(
				id,
				section.getString("name", id),
				section.getString("description", ""),
				section.getString("category", "general"),
				section.getString("icon", ""),
				section.getBoolean("hidden", false),
				goal,
				parseSource(section.section("source")),
				Reward.parse(section.section("rewards")),
				parseRewardMode(section, defaultAutoReward));
	}

	private static Source parseSource(final YamlFile section) throws ConfigException {
		final String type = section.getString("type", "manual");

		switch (type) {
			case "manual":
				return Source.MANUAL;

			case "network-stat": {
				final String stat = section.requireString("stat");

				if (!NETWORK_STATS.contains(stat))
					throw section.invalid("stat", "must be one of " + String.join(", ", NETWORK_STATS.stream().sorted().toList()));

				return new Source(SourceType.NETWORK_STAT, null, stat, null);
			}

			case "minigame-stat": {
				final String game = section.requireString("game");
				final String stat = section.requireString("stat");

				if (!pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi.GAME_PATTERN.matcher(game).matches())
					throw section.invalid("game", "may only contain a-z, 0-9, _ and - (at most 32 characters)");

				if (!KEY_PATTERN.matcher(stat).matches())
					throw section.invalid("stat", "may only contain a-z, 0-9 and _ (at most 32 characters)");

				Long atMost = null;

				if (section.has("at-most")) {
					atMost = section.getLong("at-most", 0);

					if (atMost < 0)
						throw section.invalid("at-most", "must be 0 or more");
				}

				return new Source(SourceType.MINIGAME_STAT, game, stat, atMost);
			}

			default:
				throw section.invalid("type", "must be manual, network-stat or minigame-stat");
		}
	}
}
