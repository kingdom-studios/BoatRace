package pl.kingdomcraft.captainhook.velocity.mission;

/**
 * A mission that is active in one period, one row of ch_mission_assignments. The same for every player.
 *
 * @param endsAt Unix seconds (exclusive), 0 = no end
 */
public record Assignment(long id, String season, MissionPeriodType type, String periodKey, String missionKey, Objective objective, MissionFilter filter,
		long target, long xp, String icon, String unitEn, String unitPl, String titleEn, String titlePl, long startsAt, long endsAt) {

	public boolean isCurrent(final long nowSeconds) {
		return nowSeconds >= this.startsAt && (this.endsAt == 0 || nowSeconds < this.endsAt);
	}

	public String title(final String lang) {
		return "pl".equals(lang) ? this.titlePl : this.titleEn;
	}
}
