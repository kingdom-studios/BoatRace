package pl.kingdomcraft.captainhook.pass;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;

/**
 * A player's progress in the current season of the season pass. Immutable.
 */
public final class PassProgress {

	private final String season;
	private final String seasonName;
	private final long xp;
	private final int level;
	private final int maxLevel;
	private final long xpPerLevel;
	private final boolean premium;
	private final long xpIntoLevel;
	private final long xpForNext;

	private PassProgress(final String season, final String seasonName, final long xp, final int level, final int maxLevel, final long xpPerLevel, final boolean premium,
			final long xpIntoLevel, final long xpForNext) {
		this.season = season;
		this.seasonName = seasonName;
		this.xp = xp;
		this.level = level;
		this.maxLevel = maxLevel;
		this.xpPerLevel = xpPerLevel;
		this.premium = premium;
		this.xpIntoLevel = xpIntoLevel;
		this.xpForNext = xpForNext;
	}

	static PassProgress fromJson(final JsonObject json) {
		final String season = Json.getString(json, "season", "");

		return new PassProgress(season, Json.getString(json, "name", season), Json.getLong(json, "xp", 0), Json.getInt(json, "level", 0),
				Json.getInt(json, "maxLevel", 0), Math.max(1, Json.getLong(json, "xpPerLevel", 1)), Json.getBoolean(json, "premium", false),
				Json.getLong(json, "xpIntoLevel", 0), Json.getLong(json, "xpForNext", 0));
	}

	/**
	 * @return ID of the season, the key in the database
	 */
	public String getSeason() {
		return this.season;
	}

	/**
	 * @return name of the season as shown to players
	 */
	public String getSeasonName() {
		return this.seasonName;
	}

	/**
	 * @return all XP earned this season; it keeps growing past the last level
	 */
	public long getXp() {
		return this.xp;
	}

	public int getLevel() {
		return this.level;
	}

	public int getMaxLevel() {
		return this.maxLevel;
	}

	public long getXpPerLevel() {
		return this.xpPerLevel;
	}

	/**
	 * @return XP earned towards the next level, 0 at the last level
	 */
	public long getXpIntoLevel() {
		return this.xpIntoLevel;
	}

	/**
	 * @return XP between this level and the next, 0 at the last level. Levels can cost different amounts of XP.
	 */
	public long getXpForNext() {
		return this.xpForNext;
	}

	/**
	 * @return true if the player owns the premium track
	 */
	public boolean isPremium() {
		return this.premium;
	}
}
