package pl.kingdomcraft.boatrace.race;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.scheduler.BukkitTask;

/** Mutable state of one player's current race. Main thread only. */
final class RaceSession {

	enum State {
		/** seated on the grid of an event lobby, waiting for the start command */
		LOBBY,
		/** seated, start lights running, not allowed to move */
		COUNTDOWN,
		/** lights out, the clock is running */
		RUNNING,
		/** crossed the finish line, waiting for the teleport to the hub */
		FINISHED
	}

	final UUID playerId;

	Heat heat;
	/** grid position (spawn index) of this racer */
	int slot;
	Boat boat;
	State state = State.COUNTDOWN;

	/** time penalties of THIS attempt in ms (jump start + off track), counted into the clock. Never carried over. */
	long penaltyMs;
	boolean falstarted;

	/** the racer crossed the start line after lights out - nothing else (checkpoints, finish) counts before that */
	boolean crossedStart;
	/** whether the boat was inside the start / finish zone after the previous move (edge detection) */
	boolean inStartZone;
	boolean inFinishZone;

	/** current lap, 1-based */
	int lap = 1;
	/** checkpoints per lap when the run started (an admin may add more while it is running) */
	int cpCount;
	int nextCheckpoint;
	/**
	 * Elapsed time at every checkpoint of every lap, plus every lap line except the last one (that is the finish):
	 * lap L checkpoint i -> (L-1) * (checkpoints + 1) + i, lap line after lap L -> (L-1) * (checkpoints + 1) + checkpoints.
	 */
	long[] splits = new long[0];

	/** personal best split times used for checkpoint deltas; null when none */
	long[] referenceSplits;
	long bestTotalMs = -1L;

	long finalMs;

	/** where the boat has to stay while waiting for lights out */
	Location holdPos;

	/** last boat position on a track block (target of TELEPORT_BACK and boat respawns in events) */
	Location lastTrackPos;
	int offTrackTicks;
	boolean offTrack;

	/** per-racer timers (e.g. the teleport after the finish) */
	final List<BukkitTask> tasks = new ArrayList<>();

	/** checkpoint delta shown permanently on the action bar until the next checkpoint overwrites it */
	String deltaText = "";

	boolean restartQueued;
	boolean disposed;
	/** true while the plugin itself teleports the player / boat, so the teleport listener ignores it */
	boolean internalTeleport;
	int restartFailures;
	long lastResetMs;

	RaceSession(final UUID playerId) {
		this.playerId = playerId;
	}
}
