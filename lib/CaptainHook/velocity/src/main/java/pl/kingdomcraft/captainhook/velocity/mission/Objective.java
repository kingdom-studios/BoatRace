package pl.kingdomcraft.captainhook.velocity.mission;

/**
 * What a mission counts. Where the events come from:
 *
 * - LOGIN, DISTINCT_DAYS: the proxy, when a player connects (once per calendar day)
 * - VOTE: NuVotifier on the proxy
 * - MINIGAME_STAT: the proxy, from stat changes minigames report through CaptainHookAPI (only counters, "add")
 * - PLAYTIME_MINUTES, BLOCK_BREAK, MOB_KILL, ITEM_CRAFT: the Paper servers (mission.progress); playtime excludes AFK
 * - ROLEPLAY_QUEST_COMPLETE: whatever calls CaptainHookAPI.reportMissionProgress (or the proxy command) when a quest ends
 */
public enum Objective {
	LOGIN,
	DISTINCT_DAYS,
	VOTE,
	MINIGAME_STAT,
	PLAYTIME_MINUTES,
	BLOCK_BREAK,
	MOB_KILL,
	ITEM_CRAFT,
	ROLEPLAY_QUEST_COMPLETE;

	/**
	 * @return true if Paper servers may report it in mission.progress
	 */
	public boolean fromServers() {
		return this == PLAYTIME_MINUTES || this == BLOCK_BREAK || this == MOB_KILL || this == ITEM_CRAFT || this == ROLEPLAY_QUEST_COMPLETE;
	}

	/**
	 * @return the objective with this name (case-insensitive), or null
	 */
	public static Objective find(final String name) {
		for (final Objective objective : values())
			if (objective.name().equalsIgnoreCase(name))
				return objective;

		return null;
	}
}
