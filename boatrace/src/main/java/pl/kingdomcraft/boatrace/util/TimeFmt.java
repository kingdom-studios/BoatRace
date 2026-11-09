package pl.kingdomcraft.boatrace.util;

import java.util.Locale;

/** Formatting / parsing of race times (milliseconds). */
public final class TimeFmt {

	private TimeFmt() {
	}

	/** 83456 -> "1:23.456" */
	public static String format(final long ms) {
		final long safe = Math.max(0L, ms);
		final long minutes = safe / 60_000L;
		final long seconds = (safe / 1000L) % 60L;
		final long millis = safe % 1000L;

		return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, seconds, millis);
	}

	/** 320 -> "0.32s" (absolute value, two decimals). */
	public static String seconds2(final long ms) {
		return String.format(Locale.ROOT, "%.2fs", Math.abs(ms) / 1000.0D);
	}

	/** Signed delta, e.g. "-0.32s" / "+1.05s". */
	public static String signedDelta(final long deltaMs) {
		return (deltaMs <= 0 ? "-" : "+") + seconds2(deltaMs);
	}

	/**
	 * Parses admin input: "12.5", "12.5s" (seconds) or "1500ms" (milliseconds).
	 * A bare number is ALWAYS seconds.
	 *
	 * @throws NumberFormatException when the input is not a valid positive amount
	 */
	public static long parseMillis(final String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT).replace(',', '.');
		final long result;

		if (s.endsWith("ms"))
			result = Long.parseLong(s.substring(0, s.length() - 2).trim());

		else {
			if (s.endsWith("s"))
				s = s.substring(0, s.length() - 1).trim();

			result = Math.round(Double.parseDouble(s) * 1000.0D);
		}

		if (result <= 0)
			throw new NumberFormatException("amount must be positive");

		return result;
	}
}
