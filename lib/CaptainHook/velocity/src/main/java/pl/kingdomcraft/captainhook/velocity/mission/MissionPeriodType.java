package pl.kingdomcraft.captainhook.velocity.mission;

/**
 * How long a mission lasts. Stored in lower case in ch_mission_assignments.period_type.
 */
public enum MissionPeriodType {
	DAILY,
	WEEKLY,
	SEASON;

	public String key() {
		return this.name().toLowerCase();
	}

	/**
	 * @return the type with this key ("daily"), or null
	 */
	public static MissionPeriodType find(final String key) {
		for (final MissionPeriodType type : values())
			if (type.key().equalsIgnoreCase(key))
				return type;

		return null;
	}
}
