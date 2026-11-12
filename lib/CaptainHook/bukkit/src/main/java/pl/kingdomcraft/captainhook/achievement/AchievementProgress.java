package pl.kingdomcraft.captainhook.achievement;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;

/**
 * A player's standing in one achievement of the network (achievements.yml on the proxy). Immutable.
 */
public final class AchievementProgress {

	private final String id;
	private final String name;
	private final long progress;
	private final long goal;
	private final boolean unlocked;
	private final long unlockedAt;
	private final boolean newlyUnlocked;

	private AchievementProgress(final String id, final String name, final long progress, final long goal, final boolean unlocked, final long unlockedAt, final boolean newlyUnlocked) {
		this.id = id;
		this.name = name;
		this.progress = progress;
		this.goal = goal;
		this.unlocked = unlocked;
		this.unlockedAt = unlockedAt;
		this.newlyUnlocked = newlyUnlocked;
	}

	static AchievementProgress fromJson(final JsonObject json) {
		final String id = Json.getString(json, "id", "");

		return new AchievementProgress(id, Json.getString(json, "name", id), Json.getLong(json, "progress", 0), Json.getLong(json, "goal", 1),
				Json.getBoolean(json, "unlocked", false), Json.getLong(json, "unlockedAt", 0), Json.getBoolean(json, "newly", false));
	}

	public String getId() {
		return this.id;
	}

	/**
	 * @return the name shown to players
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * @return progress towards the goal, never more than {@link #getGoal()}
	 */
	public long getProgress() {
		return this.progress;
	}

	public long getGoal() {
		return this.goal;
	}

	public boolean isUnlocked() {
		return this.unlocked;
	}

	/**
	 * @return Unix seconds of the unlock, 0 while locked
	 */
	public long getUnlockedAt() {
		return this.unlockedAt;
	}

	/**
	 * @return true if the call that returned this object is the one that unlocked the achievement
	 */
	public boolean isNewlyUnlocked() {
		return this.newlyUnlocked;
	}
}
