package pl.kingdomcraft.boatrace.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

	private Point spawn;
	private Region startLine;
	/** null = the start line is also the finish line (circuit) */
	private Region finishLine;
	private final List<Region> checkpoints = new ArrayList<>();
	/** F1 lamps in lighting order; every lamp may consist of several blocks */
	private final List<Region> lights = new ArrayList<>();

	private RewardMode rewardMode = RewardMode.NONE;
	private final List<String> rewardCommands = new ArrayList<>();

	public Arena(final String name) {
		this.name = name;
	}

	public String getName() {
		return this.name;
	}

	public Point getSpawn() {
		return this.spawn;
	}

	public void setSpawn(final Point spawn) {
		this.spawn = spawn;
	}

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

	public RewardMode getRewardMode() {
		return this.rewardMode;
	}

	public void setRewardMode(final RewardMode rewardMode) {
		this.rewardMode = rewardMode == null ? RewardMode.NONE : rewardMode;
	}

	public List<String> getRewardCommands() {
		return this.rewardCommands;
	}

	/**
	 * An arena is playable once it has a spawn, a start line and at least one checkpoint.
	 * The checkpoint is mandatory: without one, crossing a shared start/finish line could complete a lap instantly.
	 */
	public boolean isReady() {
		return this.spawn != null && this.startLine != null && !this.checkpoints.isEmpty();
	}

	/** Human readable list of what is still missing, empty when ready. */
	public String missing() {
		final List<String> missing = new ArrayList<>();

		if (this.spawn == null)
			missing.add("spawn");

		if (this.startLine == null)
			missing.add("start line");

		if (this.checkpoints.isEmpty())
			missing.add("checkpoint");

		return String.join(", ", missing);
	}
}
