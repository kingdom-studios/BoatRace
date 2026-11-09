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
	/** accumulated time penalties (jumpstart) in ms, counted into the displayed clock from the start */
	long penaltyMs;
	boolean falstarted;

	int nextCheckpoint;
	long[] splits = new long[0];
	boolean leftFinishZone;

	/** personal best (at the moment the run started) used for checkpoint deltas; null when none */
	long[] bestSplits;
	long bestTotalMs = -1L;

	long finalMs;

	/** where the boat has to stay while the lights are on */
	Location holdPos;

	final List<BukkitTask> tasks = new ArrayList<>();
	final List<Location> lampLocations = new ArrayList<>();

	/** temporary action bar text with the checkpoint delta */
	String deltaText = "";
	long deltaUntilMs;

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
