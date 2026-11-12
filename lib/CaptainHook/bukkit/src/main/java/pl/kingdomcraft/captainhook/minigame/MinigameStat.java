package pl.kingdomcraft.captainhook.minigame;

import java.util.UUID;

/**
 * One change of a player's minigame stat, e.g. "add 1 to wins". Immutable.
 */
public final class MinigameStat {

	private final UUID uuid;
	private final String stat;
	private final StatOperation operation;
	private final long value;

	/**
	 * @param stat name of the stat: a-z, 0-9 and _, at most 32 characters
	 */
	public MinigameStat(final UUID uuid, final String stat, final StatOperation operation, final long value) {
		if (uuid == null || stat == null || operation == null)
			throw new IllegalArgumentException("uuid, stat and operation are required");

		this.uuid = uuid;
		this.stat = stat;
		this.operation = operation;
		this.value = value;
	}

	public static MinigameStat add(final UUID uuid, final String stat, final long amount) {
		return new MinigameStat(uuid, stat, StatOperation.ADD, amount);
	}

	public static MinigameStat set(final UUID uuid, final String stat, final long value) {
		return new MinigameStat(uuid, stat, StatOperation.SET, value);
	}

	public static MinigameStat max(final UUID uuid, final String stat, final long value) {
		return new MinigameStat(uuid, stat, StatOperation.MAX, value);
	}

	public static MinigameStat min(final UUID uuid, final String stat, final long value) {
		return new MinigameStat(uuid, stat, StatOperation.MIN, value);
	}

	public UUID getUuid() {
		return this.uuid;
	}

	public String getStat() {
		return this.stat;
	}

	public StatOperation getOperation() {
		return this.operation;
	}

	public long getValue() {
		return this.value;
	}
}
