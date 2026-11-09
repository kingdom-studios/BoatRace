package pl.kingdomcraft.boatrace.storage;

import java.util.UUID;

/**
 * A player's best run on an arena (immutable). Splits are the elapsed times (ms) at each checkpoint of that run;
 * server is the Server.Name of the server the time was driven on.
 */
public final class RaceRecord {

	private final UUID uuid;
	private final String name;
	private final long bestMs;
	private final long[] splits;
	private final String server;

	public RaceRecord(final UUID uuid, final String name, final long bestMs, final long[] splits, final String server) {
		this.uuid = uuid;
		this.name = name;
		this.bestMs = bestMs;
		this.splits = splits == null ? new long[0] : splits.clone();
		this.server = server == null ? "" : server;
	}

	public UUID getUuid() {
		return this.uuid;
	}

	public String getName() {
		return this.name;
	}

	public long getBestMs() {
		return this.bestMs;
	}

	/** @return a copy of the checkpoint split times */
	public long[] getSplits() {
		return this.splits.clone();
	}

	public String getServer() {
		return this.server;
	}

	public RaceRecord withBestMs(final long newBestMs) {
		return new RaceRecord(this.uuid, this.name, newBestMs, this.splits, this.server);
	}
}
