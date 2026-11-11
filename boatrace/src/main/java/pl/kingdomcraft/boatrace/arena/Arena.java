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
	/**
	 * Segments of the start / finish line, in index order (1, 2, 3 ...). A line can be curved / slanted / arc-shaped:
	 * entering ANY segment counts as crossing it.
	 */
	private final List<Region> startSegments = new ArrayList<>();
	private final Zone startZone = new Zone(this.startSegments);
	/** null = the start line is also the finish line (circuit) */
	private Region finishLine;
	private Zone finishZone;
	private final List<Region> checkpoints = new ArrayList<>();
	/** F1 lamps in lighting order; every lamp may consist of several blocks */
	private final List<Region> lights = new ArrayList<>();
	/** blocks the boat must stay on; empty = the default list from config.yml */
	private final Set<Material> trackBlocks = EnumSet.noneOf(Material.class);

	/** closed for players by an admin (/bre maintenance): no joining, no random pick, shown disabled in the menu */
	private boolean maintenance;

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

	/** The start line (all segments), or null when no segment is defined. */
	public Zone getStartLine() {
		return this.startSegments.isEmpty() ? null : this.startZone;
	}

	/** Mutable, in index order: segment #1 is element 0. */
	public List<Region> getStartSegments() {
		return this.startSegments;
	}

	/** Replaces every segment with this one region (/bre setstart). */
	public void setStartLine(final Region startLine) {
		this.startSegments.clear();

		if (startLine != null)
			this.startSegments.add(startLine);
	}

	/** The configured finish line, or null when the start line doubles as the finish. */
	public Region getFinishLineRaw() {
		return this.finishLine;
	}

	/** The effective finish line (falls back to the - possibly multi-segment - start line on circuits). */
	public Zone getFinishLine() {
		return this.finishLine != null ? this.finishZone : this.getStartLine();
	}

	public void setFinishLine(final Region finishLine) {
		this.finishLine = finishLine;
		this.finishZone = finishLine == null ? null : Zone.of(finishLine);
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

	public boolean isMaintenance() {
		return this.maintenance;
	}

	public void setMaintenance(final boolean maintenance) {
		this.maintenance = maintenance;
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
		return !this.spawns.isEmpty() && !this.startSegments.isEmpty() && !this.checkpoints.isEmpty();
	}

	/** Human readable list of what is still missing, empty when ready. */
	public String missing() {
		final List<String> missing = new ArrayList<>();

		if (this.spawns.isEmpty())
			missing.add("spawn");

		if (this.startSegments.isEmpty())
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

		for (final Region segment : this.startSegments)
			copy.startSegments.add(segment.withWorld(world));

		copy.setFinishLine(this.finishLine == null ? null : this.finishLine.withWorld(world));

		for (final Region checkpoint : this.checkpoints)
			copy.checkpoints.add(checkpoint.withWorld(world));

		for (final Region lamp : this.lights)
			copy.lights.add(lamp.withWorld(world));

		copy.trackBlocks.addAll(this.trackBlocks);
		copy.laps = this.laps;
		copy.maintenance = this.maintenance;
		copy.rewardMode = this.rewardMode;
		copy.rewardCommands.addAll(this.rewardCommands);

		return copy;
	}
}
