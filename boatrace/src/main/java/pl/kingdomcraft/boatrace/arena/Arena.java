package pl.kingdomcraft.boatrace.arena;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.Material;

/** A race track definition. Plain data, edited by admin commands. */
public final class Arena {

	/** When the reward commands of an arena are executed. */
	public enum RewardMode {
		/** never */
		NONE,
		/** only for the first finished race of each player on this arena */
		ONCE,
		/** after every finished race */
		ALWAYS;

		public static RewardMode parse(final String raw) {
			if (raw == null)
				return null;

			try {
				return valueOf(raw.trim().toUpperCase(Locale.ROOT));

			} catch (final IllegalArgumentException ex) {
				return null;
			}
		}
	}

	private final String name;

	/** Grid positions: index (1, 2, 3 ... unlimited) -> spawn. Solo races use the lowest index (normally #1). */
	private final TreeMap<Integer, Point> spawns = new TreeMap<>();
	private Region startLine;
	/** null = the start line is also the finish line (circuit) */
	private Region finishLine;
	private final List<Region> checkpoints = new ArrayList<>();
	/** F1 lamps in lighting order; every lamp may consist of several blocks */
	private final List<Region> lights = new ArrayList<>();
	/** blocks the boat must stay on; empty = the default list from config.yml */
	private final Set<Material> trackBlocks = EnumSet.noneOf(Material.class);

	/** laps of a normal race on this arena (events may override it) */
	private int laps = 1;

	private RewardMode rewardMode = RewardMode.NONE;
	private final List<String> rewardCommands = new ArrayList<>();

	public Arena(final String name) {
		this.name = name;
	}

	public String getName() {
		return this.name;
	}

	// ------------------------------------------------------------------------------------------
	// Spawns (grid)
	// ------------------------------------------------------------------------------------------

	public TreeMap<Integer, Point> getSpawns() {
		return this.spawns;
	}

	/** The solo spawn: #1, or the lowest defined position when #1 was removed. */
	public Point getPrimarySpawn() {
		return this.spawns.isEmpty() ? null : this.spawns.firstEntry().getValue();
	}

	public int getPrimarySpawnIndex() {
		return this.spawns.isEmpty() ? 1 : this.spawns.firstKey();
	}

	/** The world the arena was built in (world of the primary spawn). */
	public String getWorldName() {
		final Point spawn = this.getPrimarySpawn();

		return spawn == null ? null : spawn.world();
	}

	// ------------------------------------------------------------------------------------------
	// Zones
	// ------------------------------------------------------------------------------------------

	public Region getStartLine() {
		return this.startLine;
	}

	public void setStartLine(final Region startLine) {
		this.startLine = startLine;
	}

	/** The configured finish line, or null when the start line doubles as the finish. */
	public Region getFinishLineRaw() {
		return this.finishLine;
	}

	/** The effective finish line (falls back to the start line on circuits). */
	public Region getFinishLine() {
		return this.finishLine != null ? this.finishLine : this.startLine;
	}

	public void setFinishLine(final Region finishLine) {
		this.finishLine = finishLine;
	}

	public List<Region> getCheckpoints() {
		return this.checkpoints;
	}

	public List<Region> getLights() {
		return this.lights;
	}

	public Set<Material> getTrackBlocks() {
		return this.trackBlocks;
	}

	public int getLaps() {
		return this.laps;
	}

	public void setLaps(final int laps) {
		this.laps = Math.max(1, laps);
	}

	// ------------------------------------------------------------------------------------------
	// Rewards
	// ------------------------------------------------------------------------------------------

	public RewardMode getRewardMode() {
		return this.rewardMode;
	}

	public void setRewardMode(final RewardMode rewardMode) {
		this.rewardMode = rewardMode == null ? RewardMode.NONE : rewardMode;
	}

	public List<String> getRewardCommands() {
		return this.rewardCommands;
	}

	// ------------------------------------------------------------------------------------------
	// State
	// ------------------------------------------------------------------------------------------

	/**
	 * An arena is playable once it has a spawn, a start line and at least one checkpoint.
	 * The checkpoint is mandatory: without one, crossing a shared start/finish line could complete a lap instantly.
	 */
	public boolean isReady() {
		return !this.spawns.isEmpty() && this.startLine != null && !this.checkpoints.isEmpty();
	}

	/** Human readable list of what is still missing, empty when ready. */
	public String missing() {
		final List<String> missing = new ArrayList<>();

		if (this.spawns.isEmpty())
			missing.add("spawn");

		if (this.startLine == null)
			missing.add("start line");

		if (this.checkpoints.isEmpty())
			missing.add("checkpoint");

		return String.join(", ", missing);
	}

	/**
	 * A copy of this arena with every position moved into {@code world} (same coordinates). Used for fallback maps:
	 * identical copies of a track in other worlds. Records / rewards stay shared because the name is the same.
	 */
	public Arena inWorld(final String world) {
		final Arena copy = new Arena(this.name);

		for (final Map.Entry<Integer, Point> entry : this.spawns.entrySet())
			copy.spawns.put(entry.getKey(), entry.getValue().withWorld(world));

		copy.startLine = this.startLine == null ? null : this.startLine.withWorld(world);
		copy.finishLine = this.finishLine == null ? null : this.finishLine.withWorld(world);

		for (final Region checkpoint : this.checkpoints)
			copy.checkpoints.add(checkpoint.withWorld(world));

		for (final Region lamp : this.lights)
			copy.lights.add(lamp.withWorld(world));

		copy.trackBlocks.addAll(this.trackBlocks);
		copy.laps = this.laps;
		copy.rewardMode = this.rewardMode;
		copy.rewardCommands.addAll(this.rewardCommands);

		return copy;
	}
}
