package pl.kingdomcraft.boatrace.race;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.scheduler.BukkitTask;

import pl.kingdomcraft.boatrace.arena.Arena;

/** Mutable state of one player's current race. Main thread only. */
final class RaceSession {

	enum State {
		/** seated, start lights running, not allowed to move */
		COUNTDOWN,
		/** lights out, the clock is running */
		RUNNING,
		/** crossed the finish line, waiting for RESET / HUB */
		FINISHED
	}

	final UUID playerId;

	Arena arena;
	Boat boat;
	State state = State.COUNTDOWN;

	/** System.nanoTime() at lights out */
	long startNanos;
	/** jumpstart penalty of THIS attempt in ms, counted into the displayed clock from the start. Never carried over. */
	long penaltyMs;
	boolean falstarted;

	/** how many lamps are currently lit (0..lampCount) - decides the jumpstart penalty */
	int litLamps;
	/** lamps of this run (arena lamps, or the configured amount when the arena has no lamp blocks) */
	int lampCount;

	/** the racer crossed the start line after lights out - nothing else (checkpoints, finish) counts before that */
	boolean crossedStart;
	/** whether the boat was inside the start / finish zone after the previous move (edge detection) */
	boolean inStartZone;
	boolean inFinishZone;

	int nextCheckpoint;
	long[] splits = new long[0];

	/** split times used for checkpoint deltas (personal best, or the track record); null when none */
	long[] referenceSplits;
	long bestTotalMs = -1L;

	long finalMs;

	/** where the boat has to stay while the lights are on */
	Location holdPos;

	final List<BukkitTask> tasks = new ArrayList<>();
	/** block locations of every lamp, in lighting order */
	final List<List<Location>> lampBlocks = new ArrayList<>();

	/** checkpoint delta shown permanently on the action bar until the next checkpoint overwrites it */
	String deltaText = "";

	boolean restartQueued;
	boolean disposed;
	/** true while the plugin itself teleports the player / boat, so the teleport listener ignores it */
	boolean internalTeleport;
	int restartFailures;
	long lastResetMs;

	RaceSession(final UUID playerId, final Arena arena) {
		this.playerId = playerId;
		this.arena = arena;
	}
}
