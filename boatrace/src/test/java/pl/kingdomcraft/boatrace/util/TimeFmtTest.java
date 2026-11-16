package pl.kingdomcraft.boatrace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeFmtTest {

	@Test
	void agoIsShortAndNeverNegative() {
		assertEquals("0 s", TimeFmt.ago(-5_000L));
		assertEquals("0 s", TimeFmt.ago(0L));
		assertEquals("45 s", TimeFmt.ago(45_000L));
		assertEquals("119 s", TimeFmt.ago(119_999L));
		assertEquals("2 min", TimeFmt.ago(120_000L));
		assertEquals("59 min", TimeFmt.ago(59L * 60_000L + 59_000L));
		assertEquals("119 min", TimeFmt.ago(7_199_000L));
		assertEquals("2 h", TimeFmt.ago(7_200_000L));
		assertEquals("30 h", TimeFmt.ago(30L * 3_600_000L));
	}

	@Test
	void raceTimesStillFormatAsBefore() {
		assertEquals("01:23.456", TimeFmt.format(83_456L));
		assertEquals("0.32", TimeFmt.delta(-320L));
	}
}
