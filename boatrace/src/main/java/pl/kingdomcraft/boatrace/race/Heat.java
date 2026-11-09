package pl.kingdomcraft.boatrace.race;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import pl.kingdomcraft.boatrace.arena.Arena;

/**
 * One race on one arena instance (arena + world): either a solo run (one racer) or an event (several racers on
 * the grid sharing one countdown). While a heat exists, its arena instance is occupied. Main thread only.
 */
final class Heat {

	enum Phase {
		/** event lobby: racers are seated on the grid, waiting for /bre start */
		LOBBY,
		/** start lights running */
		COUNTDOWN,
		/** lights out */
		RACING
	}

	/** "arena@world" - the occupied instance */
	final String key;
	/** the arena with every position moved into the instance world */
	final Arena arena;
	final boolean event;

	Phase phase;

	/** grid position -> racer */
	final TreeMap<Integer, RaceSession> slots = new TreeMap<>();

	/** System.nanoTime() at lights out */
	long startNanos;

	final List<BukkitTask> tasks = new ArrayList<>();
	/** block locations of every lamp, in lighting order */
	final List<List<Location>> lampBlocks = new ArrayList<>();
	int litLamps;
	int lampCount;

	/** start signal blocks of this instance */
	final List<Location> signalBlocks = new ArrayList<>();
	/** WORLD mode: real block data replaced by the go block, restored afterwards */
	final Map<Location, BlockData> signalOriginal = new LinkedHashMap<>();
	boolean signalGreen;

	/** laps of this race (arena setting, or the override given when the event was opened / started) */
	int laps;

	/** how many racers of an event crossed the finish line (for the finishing position) */
	int finishedCount;
	/** number of racers when the lights went out */
	int fieldSize;

	Heat(final String key, final Arena arena, final boolean event) {
		this.key = key;
		this.arena = arena;
		this.event = event;
		this.phase = event ? Phase.LOBBY : Phase.COUNTDOWN;
		this.laps = arena.getLaps();
	}

	List<RaceSession> racers() {
		return new ArrayList<>(this.slots.values());
	}

	/** lowest free grid position, or -1 when the grid is full */
	int freeSlot() {
		for (final Integer index : this.arena.getSpawns().keySet())
			if (!this.slots.containsKey(index))
				return index;

		return -1;
	}

	int capacity() {
		return this.arena.getSpawns().size();
	}
}
