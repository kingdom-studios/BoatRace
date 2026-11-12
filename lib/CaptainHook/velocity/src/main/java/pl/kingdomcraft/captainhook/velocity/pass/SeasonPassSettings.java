package pl.kingdomcraft.captainhook.velocity.pass;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.reward.Reward;

/**
 * seasonpass.yml: one season with a fixed amount of XP per level and rewards on two tracks per tier.
 *
 * @param id       key of the season in the database; a new ID starts everyone from zero
 * @param startsAt Unix seconds, 0 when the season has no start date
 * @param endsAt   Unix seconds (exclusive), Long.MAX_VALUE when the season has no end date
 * @param xpPerLevel the XP of the first level (the base of the curve, see {@link #thresholds})
 * @param maxLevel the highest level a player can reach, XP keeps counting beyond it
 * @param tiers    rewards by level, sorted
 * @param thresholds cumulative XP needed to reach each level: index 0 is level 1. Level n costs the "xp" of tier n if
 *                 it sets one, otherwise xp-per-level + (n - 1) * xp-increase-per-level
 * @param claimGraceDays days after the end during which rewards can still be claimed (but no XP is earned)
 * @param premiumPrice KingdomCoins price of the premium track, 0 = it cannot be bought on the website
 */
public record SeasonPassSettings(boolean enabled, String id, String name, long startsAt, long endsAt, long xpPerLevel, int maxLevel, Map<Integer, Tier> tiers,
		List<Long> thresholds, int claimGraceDays, long premiumPrice) {

	public static final String FILE = "seasonpass.yml";

	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
	private static final int MAX_TIERS = 1000;

	/**
	 * @param xp what this level costs when it differs from the curve, 0 = follow the curve
	 */
	public record Tier(int level, Reward free, Reward premium, long xp) {

		public Reward reward(final Track track) {
			return track == Track.PREMIUM ? this.premium : this.free;
		}
	}

	public SeasonPassSettings {
		tiers = Collections.unmodifiableMap(new TreeMap<>(tiers));
		thresholds = List.copyOf(thresholds);
	}

	/**
	 * @return true if XP can be earned: the season runs
	 */

	public boolean isActive(final long nowSeconds) {
		return this.enabled && nowSeconds >= this.startsAt && nowSeconds < this.endsAt;
	}

	/**
	 * @return Unix seconds until which rewards can be claimed, Long.MAX_VALUE for a season without an end
	 */
	public long claimUntil() {
		return this.endsAt == Long.MAX_VALUE ? Long.MAX_VALUE : this.endsAt + this.claimGraceDays * 86_400L;
	}

	/**
	 * @return true if rewards can be claimed: while the season runs and during the grace period after it
	 */
	public boolean isClaimable(final long nowSeconds) {
		return this.enabled && nowSeconds >= this.startsAt && nowSeconds < this.claimUntil();
	}

	/**
	 * @return the level for this much XP, 0 up to {@link #maxLevel}
	 */
	public int levelOf(final long xp) {
		int level = 0;

		while (level < this.thresholds.size() && xp >= this.thresholds.get(level))
			level++;

		return level;
	}

	/**
	 * @return the total XP that reaches this level, 0 for level 0
	 */
	public long xpForLevel(final int level) {
		return level <= 0 ? 0 : this.thresholds.get(Math.min(level, this.thresholds.size()) - 1);
	}

	/**
	 * @return XP earned since the player reached their level, 0 at the highest level
	 */
	public long xpIntoLevel(final long xp) {
		final int level = this.levelOf(xp);

		return level >= this.maxLevel ? 0 : Math.max(0, xp) - this.xpForLevel(level);
	}

	/**
	 * @return XP between the player's level and the next one, 0 at the highest level
	 */
	public long xpForNextLevel(final long xp) {
		final int level = this.levelOf(xp);

		return level >= this.maxLevel ? 0 : this.xpForLevel(level + 1) - this.xpForLevel(level);
	}

	/**
	 * Builds the curve: level n costs the "xp" of tier n if it sets one, otherwise base + (n - 1) * increase.
	 */
	static List<Long> curve(final long base, final long increase, final int maxLevel, final Map<Integer, Tier> tiers) {
		final List<Long> thresholds = new ArrayList<>();
		long total = 0;

		for (int level = 1; level <= maxLevel; level++) {
			final Tier tier = tiers.get(level);

			total += tier != null && tier.xp() > 0 ? tier.xp() : base + (level - 1) * increase;
			thresholds.add(total);
		}

		return thresholds;
	}

	public static SeasonPassSettings parse(final YamlFile file) throws ConfigException {
		final YamlFile season = file.section("season");
		final String id = season.requireString("id");

		if (!ID_PATTERN.matcher(id).matches())
			throw season.invalid("id", "may only contain a-z, 0-9, _ and - (at most 32 characters)");

		final ZoneId zone;

		try {
			zone = ZoneId.of(season.getString("timezone", "Europe/Warsaw"));

		} catch (final RuntimeException ex) {
			throw season.invalid("timezone", "is not a time zone, e.g. Europe/Warsaw");
		}

		final LocalDate starts = parseDate(season, "starts");
		final LocalDate ends = parseDate(season, "ends");

		if (starts != null && ends != null && ends.isBefore(starts))
			throw season.invalid("ends", "must not be before starts");

		final long xpPerLevel = file.getLong("xp-per-level", 1000);

		if (xpPerLevel < 1)
			throw file.invalid("xp-per-level", "must be at least 1");

		final long xpIncrease = file.getLong("xp-increase-per-level", 0);

		if (xpIncrease < 0 || xpIncrease > 1_000_000)
			throw file.invalid("xp-increase-per-level", "must be between 0 and 1000000");

		final long graceDays = season.getLong("claim-grace-days", 7);

		if (graceDays < 0 || graceDays > 365)
			throw season.invalid("claim-grace-days", "must be between 0 and 365");

		final long premiumPrice = file.section("premium").getLong("price", 0);

		if (premiumPrice < 0 || premiumPrice > 1_000_000_000L)
			throw file.section("premium").invalid("price", "must be between 0 and 1000000000");

		final Map<Integer, Tier> tiers = new TreeMap<>();
		final YamlFile tiersSection = file.section("tiers");

		for (final String key : tiersSection.keys()) {
			final int level;

			try {
				level = Integer.parseInt(key);

			} catch (final NumberFormatException ex) {
				throw tiersSection.invalid(key, "must be a level number");
			}

			if (level < 1 || level > MAX_TIERS)
				throw tiersSection.invalid(key, "must be between 1 and " + MAX_TIERS);

			final YamlFile tier = tiersSection.section(key);

			final long cost = tier.getLong("xp", 0);

			if (cost < 0 || cost > 1_000_000_000L)
				throw tier.invalid("xp", "must be between 0 and 1000000000");

			tiers.put(level, new Tier(level, Reward.parse(tier.section("free")), Reward.parse(tier.section("premium")), cost));
		}

		final int highestTier = tiers.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		final long maxLevel = file.getLong("max-level", highestTier);

		if (maxLevel < highestTier || maxLevel > MAX_TIERS)
			throw file.invalid("max-level", "must be between the highest tier (" + highestTier + ") and " + MAX_TIERS);

		return new SeasonPassSettings(
				file.getBoolean("enabled", true),
				id,
				season.getString("name", id),
				starts != null ? starts.atStartOfDay(zone).toEpochSecond() : 0,
				ends != null ? ends.plusDays(1).atStartOfDay(zone).toEpochSecond() : Long.MAX_VALUE, // the end date is the last day of the season
				xpPerLevel,
				(int) maxLevel,
				tiers,
				curve(xpPerLevel, xpIncrease, (int) maxLevel, tiers),
				(int) graceDays,
				premiumPrice);
	}

	/**
	 * @return the date, or null when the key is missing or empty
	 */
	private static LocalDate parseDate(final YamlFile section, final String key) throws ConfigException {
		final Object raw = section.getRaw(key);

		// YAML turns an unquoted 2026-10-01 into a timestamp at midnight UTC
		if (raw instanceof Date)
			return ((Date) raw).toInstant().atOffset(ZoneOffset.UTC).toLocalDate();

		final String value = section.getString(key, "");

		if (value.isBlank())
			return null;

		try {
			return LocalDate.parse(value.trim());

		} catch (final DateTimeParseException ex) {
			throw section.invalid(key, "must be a date like 2026-10-01");
		}
	}
}
