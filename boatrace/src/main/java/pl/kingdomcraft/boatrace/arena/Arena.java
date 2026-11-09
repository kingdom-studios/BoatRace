package pl.kingdomcraft.boatrace.arena;

import java.util.ArrayList;
import java.util.List;

/** A race track definition. Plain data, edited by admin commands. */
public final class Arena {

	private final String name;

	private Point spawn;
	private Point startBlock;
	private boolean lightsAlongX = true;
	private final List<Point> checkpoints = new ArrayList<>();
	private Point finish;

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

	public Point getStartBlock() {
		return this.startBlock;
	}

	public void setStartBlock(final Point startBlock) {
		this.startBlock = startBlock;
	}

	/** True when the row of start lamps extends along the X axis (race direction is then along Z). */
	public boolean isLightsAlongX() {
		return this.lightsAlongX;
	}

	public void setLightsAlongX(final boolean lightsAlongX) {
		this.lightsAlongX = lightsAlongX;
	}

	public List<Point> getCheckpoints() {
		return this.checkpoints;
	}

	public Point getFinish() {
		return this.finish;
	}

	public void setFinish(final Point finish) {
		this.finish = finish;
	}

	/** An arena is playable ("active") once it has a spawn and a finish. */
	public boolean isReady() {
		return this.spawn != null && this.finish != null;
	}
}
