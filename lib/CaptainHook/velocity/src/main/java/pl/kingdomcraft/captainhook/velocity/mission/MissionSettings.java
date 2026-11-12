package pl.kingdomcraft.captainhook.velocity.mission;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;

/**
 * missions.yml: the pools missions are drawn from and how often.
 *
 * @param enabled      false = no assignments are made and nothing is counted
 * @param zone         time zone of the resets: daily at 00:00, weekly on Monday at 00:00
 * @param dailyCount   how many daily missions are active per day (missions marked "always" included)
 * @param weeklyCount  the same per week
 * @param flushSeconds how often buffered progress is written to the database
 * @param pools        the missions by period; "season" ones are all active for the whole season
 */
public record MissionSettings(boolean enabled, ZoneId zone, int dailyCount, int weeklyCount, int flushSeconds, Map<MissionPeriodType, List<MissionDefinition>> pools) {

	public static final String FILE = "missions.yml";

	private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
	private static final int MAX_TITLE = 160;

	public MissionSettings {
		pools = Map.copyOf(pools);
	}

	public List<MissionDefinition> pool(final MissionPeriodType type) {
		return this.pools.getOrDefault(type, List.of());
	}

	public int count(final MissionPeriodType type) {
		return type == MissionPeriodType.DAILY ? this.dailyCount : type == MissionPeriodType.WEEKLY ? this.weeklyCount : Integer.MAX_VALUE;
	}

	public static MissionSettings load(final Path dataDirectory) throws ConfigException {
		return parse(YamlFile.load(dataDirectory, FILE));
	}

	public static MissionSettings parse(final YamlFile file) throws ConfigException {
		final ZoneId zone;

		try {
			zone = ZoneId.of(file.getString("timezone", "Europe/Warsaw"));

		} catch (final RuntimeException ex) {
			throw file.invalid("timezone", "is not a time zone, e.g. Europe/Warsaw");
		}

		final long daily = file.getLong("daily-count", 4);
		final long weekly = file.getLong("weekly-count", 4);
		final long flush = file.getLong("flush-interval-seconds", 45);

		if (daily < 0 || daily > 50)
			throw file.invalid("daily-count", "must be between 0 and 50");

		if (weekly < 0 || weekly > 50)
			throw file.invalid("weekly-count", "must be between 0 and 50");

		if (flush < 5 || flush > 600)
			throw file.invalid("flush-interval-seconds", "must be between 5 and 600");

		final Map<MissionPeriodType, List<MissionDefinition>> pools = new java.util.EnumMap<>(MissionPeriodType.class);
		final YamlFile poolsSection = file.section("pools");
		final Set<String> keys = new HashSet<>();

		for (final String name : poolsSection.keys()) {
			final MissionPeriodType type = MissionPeriodType.find(name);

			if (type == null)
				throw poolsSection.invalid(name, "must be daily, weekly or season");

			final List<MissionDefinition> pool = new ArrayList<>();

			for (final YamlFile section : poolsSection.getSectionList(name)) {
				final MissionDefinition mission = parseMission(section, type);

				if (!keys.add(mission.key()))
					throw section.invalid("key", "\"" + mission.key() + "\" is used twice");

				pool.add(mission);
			}

			pools.put(type, pool);
		}

		return new MissionSettings(file.getBoolean("enabled", true), zone, (int) daily, (int) weekly, (int) flush, pools);
	}

	private static MissionDefinition parseMission(final YamlFile section, final MissionPeriodType type) throws ConfigException {
		final String key = section.requireString("key");

		if (!KEY_PATTERN.matcher(key).matches())
			throw section.invalid("key", "may only contain a-z, 0-9, _ and - (at most 64 characters)");

		final Objective objective = Objective.find(section.requireString("objective"));

		if (objective == null)
			throw section.invalid("objective", "must be one of " + String.join(", ", java.util.Arrays.stream(Objective.values()).map(Enum::name).toList()));

		final long target = section.getLong("target", 1);
		final long xp = section.getLong("xp", 0);

		if (target < 1 || target > 100_000_000L)
			throw section.invalid("target", "must be between 1 and 100000000");

		if (xp < 0 || xp > 1_000_000_000L)
			throw section.invalid("xp", "must be between 0 and 1000000000");

		final String icon = section.getString("icon", "generic");

		if (!MissionDefinition.ICONS.contains(icon))
			throw section.invalid("icon", "must be one of " + String.join(", ", new java.util.TreeSet<>(MissionDefinition.ICONS)));

		final YamlFile title = section.section("title");
		final String titleEn = title.getString("en", null);
		final String titlePl = title.getString("pl", titleEn);

		if (titleEn == null || titleEn.isBlank())
			throw section.invalid("title.en", "is required (title.pl falls back to it)");

		if (titleEn.length() > MAX_TITLE || titlePl.length() > MAX_TITLE)
			throw section.invalid("title", "must be at most " + MAX_TITLE + " characters");

		final YamlFile unit = section.section("unit");
		final long weight = section.getLong("weight", 10);

		if (weight < 1 || weight > 1000)
			throw section.invalid("weight", "must be between 1 and 1000");

		final MissionFilter filter = MissionFilter.parse(section.section("filter"));

		if (objective == Objective.MINIGAME_STAT && (filter.game() == null || filter.stat() == null))
			throw section.invalid("filter", "needs game and stat for MINIGAME_STAT");

		if (filter.minValue() != null && objective != Objective.MINIGAME_STAT)
			throw section.invalid("filter.min-value", "is only for MINIGAME_STAT");

		return new MissionDefinition(key, type, section.getBoolean("always", false), objective, target, xp, icon,
				unit.getString("en", null), unit.getString("pl", unit.getString("en", null)), titleEn, titlePl, filter, (int) weight);
	}
}
