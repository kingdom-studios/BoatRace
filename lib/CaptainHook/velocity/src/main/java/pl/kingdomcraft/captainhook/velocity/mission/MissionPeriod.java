package pl.kingdomcraft.captainhook.velocity.mission;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * A stretch of time missions are assigned for.
 *
 * @param key      "2026-10-12" (daily), "2026-W42" (weekly), "season"; a re-rolled period gets ".1", ".2"… behind it
 * @param startsAt Unix seconds
 * @param endsAt   Unix seconds (exclusive), 0 = no end (a season without an end date)
 */
public record MissionPeriod(MissionPeriodType type, String key, long startsAt, long endsAt) {

	/**
	 * The daily or weekly period the instant falls in, in the given zone. Midnight is taken from the zone's rules,
	 * so a day with a clock change simply is 23 or 25 hours long.
	 */
	public static MissionPeriod of(final MissionPeriodType type, final long nowSeconds, final ZoneId zone) {
		final LocalDate today = java.time.Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate();

		switch (type) {
			case DAILY:
				return new MissionPeriod(type, today.toString(), today.atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond());

			case WEEKLY: {
				final LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

				return new MissionPeriod(type, String.format("%d-W%02d", monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)),
						monday.atStartOfDay(zone).toEpochSecond(), monday.plusWeeks(1).atStartOfDay(zone).toEpochSecond());
			}

			default:
				throw new IllegalArgumentException("A season period comes from the season, see ofSeason");
		}
	}

	public static MissionPeriod ofSeason(final long startsAt, final long endsAt) {
		return new MissionPeriod(MissionPeriodType.SEASON, "season", startsAt, endsAt == Long.MAX_VALUE ? 0 : endsAt);
	}

	/**
	 * @return the key without a re-roll suffix: "2026-10-12.2" is a re-roll of "2026-10-12"
	 */
	public static String baseKey(final String key) {
		final int dot = key.indexOf('.');

		return dot < 0 ? key : key.substring(0, dot);
	}

	/**
	 * @return how often the period was re-rolled, 0 for the original draw
	 */
	public static int revision(final String key) {
		final int dot = key.indexOf('.');

		try {
			return dot < 0 ? 0 : Integer.parseInt(key.substring(dot + 1));

		} catch (final NumberFormatException ex) {
			return 0;
		}
	}

	public boolean contains(final long nowSeconds) {
		return nowSeconds >= this.startsAt && (this.endsAt == 0 || nowSeconds < this.endsAt);
	}
}
