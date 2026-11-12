package pl.kingdomcraft.captainhook.minigame;

import java.util.UUID;

/**
 * One line of a minigame leaderboard. Immutable.
 */
public final class MinigameTopEntry {

	private final UUID uuid;
	private final String name;
	private final long value;

	MinigameTopEntry(final UUID uuid, final String name, final long value) {
		this.uuid = uuid;
		this.name = name;
		this.value = value;
	}

	public UUID getUuid() {
		return this.uuid;
	}

	/**
	 * @return the player's last known name, empty if unknown
	 */
	public String getName() {
		return this.name;
	}

	public long getValue() {
		return this.value;
	}
}
