package pl.kingdomcraft.boatrace.race;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import io.papermc.paper.entity.TeleportFlag;
import pl.kingdomcraft.boatrace.Settings;
import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.arena.Point;
import pl.kingdomcraft.boatrace.arena.Region;
import pl.kingdomcraft.boatrace.arena.Zone;
import pl.kingdomcraft.boatrace.storage.RaceRecord;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.Msg;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * The race engine: join / leave / reset, solo runs and events (multi-spawn grid), F1 start lights, start signal
 * blocks, dynamic jumpstart penalty, start line, invisible checkpoint zones, off-track detection, finish, rewards
 * and fallback maps.
 *
 * Every race runs in a {@link Heat} on one arena instance (arena + world). An instance is occupied by one solo racer
 * or one event; when it is busy, joining players are sent to a copy of the track in another world (fallback maps).
 *
 * Lap validation: a finish only counts when the race was started correctly (lights out), the racer crossed the
 * start line afterwards and then passed 100% of the checkpoints in order.
 *
 * Performance notes:
 *  - ONE repeating task (every tick, so the milliseconds run smoothly) updates the action bar of all racers and
 *    runs the off-track check every few ticks.
 *  - Zones are only evaluated inside VehicleMoveEvent of race boats (O(1) map lookup for any other vehicle).
 *  - Lamps / start signal are client-side block changes (unless start-signal.mode is WORLD), so the world is not modified.
 */
public final class RaceManager {

	/** Outcome of {@link #join(Player, Arena)}; the racer has already been told about failures. */
	public enum JoinResult {
		JOINED,
		BANNED,
		NOT_READY,
		/** the arena, all its copies (and - if enabled - every other arena) are occupied */
		BUSY,
		FAILED
	}

	/** Outcome of the event commands. */
	public enum EventResult {
		OPENED,
		STARTED,
		CLOSED,
		ALREADY_OPEN,
		ALREADY_STARTED,
		NO_EVENT,
		NOT_READY,
		BUSY,
		EMPTY
	}

	/** Read-only view of an event for /bre event status. */
	public record EventInfo(String world, String phase, int players, int capacity, int laps) {
	}

	private enum LeaveMode {
		/** player stays where they are (used when something else already moved them) */
		SILENT,
		/** run the configured hub command (/spawn) */
		HUB,
		/** teleport back to where the player joined from (quit / shutdown / reload) */
		ORIGIN
	}

	private final JavaPlugin plugin;
	private final Settings settings;
	private final ArenaManager arenas;
	private final RecordStorage storage;
	private final NamespacedKey itemKey;

	private final Map<UUID, RaceSession> sessions = new HashMap<>();
	private final Map<UUID, RaceSession> boats = new HashMap<>();
	/** occupied arena instances: "arena@world" -> heat */
	private final Map<String, Heat> heats = new LinkedHashMap<>();

	private BukkitTask ticker;
	private long tickCounter;

	public RaceManager(final JavaPlugin plugin, final Settings settings, final ArenaManager arenas, final RecordStorage storage) {
		this.plugin = plugin;
		this.settings = settings;
		this.arenas = arenas;
		this.storage = storage;
		this.itemKey = new NamespacedKey(plugin, "race_item");
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	public void start() {
		this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
	}

	/** Server shutdown / plugin disable: remove every boat, restore world blocks, give everybody their inventory back. */
	public void shutdown() {
		if (this.ticker != null)
			this.ticker.cancel();

		this.leaveAll(LeaveMode.ORIGIN);
	}

	/** Used by /bre reload. */
	public void leaveAllToHub() {
		this.leaveAll(LeaveMode.HUB);
	}

	private void leaveAll(final LeaveMode mode) {
		for (final RaceSession session : new ArrayList<>(this.sessions.values()))
			this.dispose(session, mode);

		// empty event lobbies (and anything left over) - restores WORLD start signals
		for (final Heat heat : new ArrayList<>(this.heats.values()))
			this.cleanupHeat(heat);

		this.heats.clear();
	}

	// ------------------------------------------------------------------------------------------
	// Public API (commands / listener / placeholders)
	// ------------------------------------------------------------------------------------------

	public boolean isRacing(final Player player) {
		return this.sessions.containsKey(player.getUniqueId());
	}

	public boolean isRaceBoat(final Entity entity) {
		return this.boats.containsKey(entity.getUniqueId());
	}

	public boolean isBoatOwner(final Entity boat, final Entity who) {
		final RaceSession session = this.boats.get(boat.getUniqueId());

		return session != null && session.playerId.equals(who.getUniqueId());
	}

	/** A random ready arena, preferring arenas that have a free instance right now. */
	public Arena pickRandomReadyArena() {
		final List<Arena> ready = this.arenas.readyArenas();
		final List<Arena> free = new ArrayList<>();

		for (final Arena arena : ready)
			if (this.freeInstanceWorld(arena, null) != null)
				free.add(arena);

		final List<Arena> pool = free.isEmpty() ? ready : free;

		return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}

	/**
	 * Joins the arena (or switches to it when the player already races). Silent on success: the player simply
	 * appears in a boat on the grid.
	 *
	 * Order: an open event lobby of the arena with a free grid position -> a free instance of the arena (its own
	 * world first, then the fallback worlds) -> a random other free arena (fallback.random-free-arena).
	 */
	public JoinResult join(final Player player, final Arena arena) {
		if (this.storage.isBlacklisted(player.getUniqueId())) {
			Msg.send(player, "race.banned");
			return JoinResult.BANNED;
		}

		if (!arena.isReady()) {
			Msg.send(player, "race.not-ready", "arena", arena.getName(), "missing", arena.missing());
			return JoinResult.NOT_READY;
		}

		final RaceSession existing = this.sessions.get(player.getUniqueId());

		// 1) event lobby
		final Heat lobby = this.findEvent(arena);

		if (lobby != null && lobby.phase == Heat.Phase.LOBBY && (lobby.freeSlot() != -1 || (existing != null && existing.heat == lobby))) {
			if (existing != null && existing.heat == lobby)
				return JoinResult.JOINED;

			final RaceSession session = existing != null ? existing : this.createSession(player);

			if (session == null)
				return JoinResult.FAILED;

			this.detach(session);
			this.attachToEvent(session, lobby);

			return session.disposed ? JoinResult.FAILED : JoinResult.JOINED;
		}

		// 2) free instance of this arena, 3) another free arena
		Arena target = arena;
		String world = this.freeInstanceWorld(arena, existing);

		if (world == null && this.settings.fallbackEnabled && this.settings.fallbackRandomArena) {
			final List<Arena> candidates = new ArrayList<>();

			for (final Arena other : this.arenas.readyArenas())
				if (other != arena && this.freeInstanceWorld(other, existing) != null)
					candidates.add(other);

			if (!candidates.isEmpty()) {
				target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
				world = this.freeInstanceWorld(target, existing);
			}
		}

		if (world == null) {
			Msg.send(player, "race.all-busy", "arena", arena.getName());
			return JoinResult.BUSY;
		}

		final RaceSession session = existing != null ? existing : this.createSession(player);

		if (session == null)
			return JoinResult.FAILED;

		this.detach(session);

		final Arena instance = world.equals(target.getWorldName()) ? target : target.inWorld(world);
		final Heat heat = new Heat(instanceKey(target, world), instance, false);

		this.heats.put(heat.key, heat);

		session.heat = heat;
		session.slot = instance.getPrimarySpawnIndex();
		heat.slots.put(session.slot, session);

		this.startSoloRun(session);

		if (session.disposed)
			return JoinResult.FAILED;

		if (target != arena || !world.equals(arena.getWorldName()))
			Msg.send(player, "race.redirected", "arena", target.getName(), "world", world, "requested", arena.getName());

		return JoinResult.JOINED;
	}

	/** /bre leave or the HUB item. Silent: no chat output at all. */
	public void leave(final Player player) {
		final RaceSession session = this.sessions.get(player.getUniqueId());

		if (session != null)
			this.dispose(session, LeaveMode.HUB);
	}

	/**
	 * /bre reset or the RESET item. Silent: no chat output at all.
	 * Solo: a completely fresh attempt (any penalty is wiped). Event: puts the boat back (grid / last track position),
	 * an event cannot be restarted by a single racer.
	 */
	public void reset(final Player player) {
		final RaceSession s = this.sessions.get(player.getUniqueId());

		if (s == null || s.heat == null)
			return;

		final long now = System.currentTimeMillis();

		if (now - s.lastResetMs < 500L)
			return;

		s.lastResetMs = now;

		if (!s.heat.event)
			this.startSoloRun(s);

		else if (s.state == RaceSession.State.LOBBY || s.state == RaceSession.State.RUNNING)
			this.rescue(s);
	}

	/** Runs the action of a hotbar button, returns false if the item is not a race button. */
	public boolean handleButton(final Player player, final ItemStack item) {
		final String action = this.itemAction(item);

		if (action == null || !this.isRacing(player))
			return false;

		if ("reset".equals(action))
			this.reset(player);

		else if ("hub".equals(action))
			this.leave(player);

		return true;
	}

	public boolean isRaceItem(final ItemStack item) {
		return this.itemAction(item) != null;
	}

	// ------------------------------------------------------------------------------------------
	// Events (multiplayer start)
	// ------------------------------------------------------------------------------------------

	/**
	 * Opens an event lobby on a free instance of the arena. Joining players then take grid positions 1, 2, 3 ...
	 *
	 * @param laps laps of the event, null = the arena's setting
	 */
	public EventResult openEvent(final Arena arena, final Integer laps) {
		if (this.findEvent(arena) != null)
			return EventResult.ALREADY_OPEN;

		if (!arena.isReady())
			return EventResult.NOT_READY;

		final String world = this.freeInstanceWorld(arena, null);

		if (world == null)
			return EventResult.BUSY;

		final Arena instance = world.equals(arena.getWorldName()) ? arena : arena.inWorld(world);
		final Heat heat = new Heat(instanceKey(arena, world), instance, true);

		if (laps != null)
			heat.laps = Math.max(1, laps);

		this.heats.put(heat.key, heat);
		this.loadVisuals(heat);

		return EventResult.OPENED;
	}

	/**
	 * /bre start <arena> [laps]: starts the lights of the event lobby.
	 *
	 * @param laps overrides the laps of the event, null = keep
	 */
	public EventResult startEvent(final Arena arena, final Integer laps) {
		final Heat heat = this.findEvent(arena);

		if (heat == null)
			return EventResult.NO_EVENT;

		if (heat.phase != Heat.Phase.LOBBY)
			return EventResult.ALREADY_STARTED;

		if (heat.slots.isEmpty())
			return EventResult.EMPTY;

		if (laps != null && laps != heat.laps) {
			heat.laps = Math.max(1, laps);

			// splits / personal bests depend on the lap count
			for (final RaceSession racer : heat.racers())
				this.resetRunState(racer);
		}

		this.startEventCountdown(heat);

		return EventResult.STARTED;
	}

	/** Sends every event racer to the hub and frees the instance. */
	public EventResult closeEvent(final Arena arena) {
		final Heat heat = this.findEvent(arena);

		if (heat == null)
			return EventResult.NO_EVENT;

		for (final RaceSession racer : heat.racers())
			this.dispose(racer, LeaveMode.HUB);

		this.cleanupHeat(heat);
		this.heats.remove(heat.key);

		return EventResult.CLOSED;
	}

	/** @return the current event of the arena, or null */
	public EventInfo eventInfo(final Arena arena) {
		final Heat heat = this.findEvent(arena);

		if (heat == null)
			return null;

		return new EventInfo(heat.arena.getWorldName(), heat.phase.name(), heat.slots.size(), heat.capacity(), heat.laps);
	}

	private Heat findEvent(final Arena arena) {
		for (final Heat heat : this.heats.values())
			if (heat.event && heat.arena.getName().equalsIgnoreCase(arena.getName()))
				return heat;

		return null;
	}

	private void attachToEvent(final RaceSession s, final Heat heat) {
		final int slot = heat.freeSlot();

		s.heat = heat;
		s.slot = slot;
		heat.slots.put(slot, s);

		this.resetRunState(s);
		s.state = RaceSession.State.LOBBY;

		if (!this.place(s, this.spawnOf(s)))
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null) {
			this.showLamps(player, heat, 0);
		}

		if (this.settings.eventAutoStartWhenFull && heat.freeSlot() == -1)
			this.startEventCountdown(heat);
	}

	private void startEventCountdown(final Heat heat) {
		if (heat.phase != Heat.Phase.LOBBY)
			return;

		heat.phase = Heat.Phase.COUNTDOWN;

		for (final RaceSession racer : heat.racers()) {
			racer.state = RaceSession.State.COUNTDOWN;

			final Player player = Bukkit.getPlayer(racer.playerId);

			if (player != null)
				Msg.title(player, "event-start");
		}

		this.beginCountdown(heat, this.settings.eventStartDelayTicks);
	}

	// ------------------------------------------------------------------------------------------
	// Instances (arena + world) / fallback maps
	// ------------------------------------------------------------------------------------------

	private static String instanceKey(final Arena arena, final String world) {
		return arena.getName().toLowerCase(Locale.ROOT) + "@" + world;
	}

	/** The arena's own world first, then the configured fallback worlds (only loaded worlds). */
	private List<String> instanceWorlds(final Arena arena) {
		final List<String> worlds = new ArrayList<>();
		final String own = arena.getWorldName();

		if (own != null && Bukkit.getWorld(own) != null)
			worlds.add(own);

		if (this.settings.fallbackEnabled)
			for (final String world : this.settings.fallbackWorldsOf(arena.getName()))
				if (!worlds.contains(world) && Bukkit.getWorld(world) != null)
					worlds.add(world);

		return worlds;
	}

	/**
	 * @param owner its own solo heat counts as free (switching / re-joining the same arena), may be null
	 * @return a world in which the arena is free, or null
	 */
	private String freeInstanceWorld(final Arena arena, final RaceSession owner) {
		for (final String world : this.instanceWorlds(arena)) {
			final Heat heat = this.heats.get(instanceKey(arena, world));

			if (heat == null || (owner != null && heat == owner.heat && !heat.event))
				return world;
		}

		return null;
	}

	// ------------------------------------------------------------------------------------------
	// Sessions
	// ------------------------------------------------------------------------------------------

	/** Saves the inventory, gives the race kit. @return null when the snapshot could not be written */
	private RaceSession createSession(final Player player) {
		// A snapshot left behind by a crash must be restored BEFORE we take a new one
		PlayerSnapshot.restore(this.plugin, player);

		if (!PlayerSnapshot.save(this.plugin, player)) {
			Msg.send(player, "race.inventory-save-failed");
			return null;
		}

		final RaceSession session = new RaceSession(player.getUniqueId());

		this.sessions.put(player.getUniqueId(), session);

		player.getInventory().clear();
		player.setGameMode(GameMode.ADVENTURE);
		player.setFireTicks(0);

		this.giveHotbar(player);

		return session;
	}

	/** Takes the racer out of its heat (boat, timers, client-side blocks). The heat is freed when it becomes empty. */
	private void detach(final RaceSession s) {
		final Heat heat = s.heat;

		this.cleanupSession(s);

		if (heat == null)
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null)
			this.revertVisuals(player, heat);

		heat.slots.remove(s.slot);
		s.heat = null;

		// An empty event lobby stays open; everything else is freed
		if (heat.slots.isEmpty() && !(heat.event && heat.phase == Heat.Phase.LOBBY) && this.heats.get(heat.key) == heat) {
			this.cleanupHeat(heat);
			this.heats.remove(heat.key);
		}
	}

	private void dispose(final RaceSession s, final LeaveMode mode) {
		if (s.disposed)
			return;

		s.disposed = true;
		this.sessions.remove(s.playerId);
		this.detach(s);

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null)
			return;

		final Location origin = PlayerSnapshot.restore(this.plugin, player);

		if (mode == LeaveMode.ORIGIN) {
			if (origin != null)
				player.teleport(origin);

		} else if (mode == LeaveMode.HUB) {
			final String command = this.settings.hubCommand == null ? "" : this.settings.hubCommand.trim();
			boolean executed = false;

			if (!command.isEmpty())
				executed = Bukkit.dispatchCommand(player, command.startsWith("/") ? command.substring(1) : command);

			if (!executed && origin != null)
				player.teleport(origin);
		}
	}

	/** Cancels the racer's own timers and kills the boat. */
	private void cleanupSession(final RaceSession s) {
		for (final BukkitTask task : s.tasks)
			task.cancel();

		s.tasks.clear();
		this.removeBoat(s);
	}

	private void removeBoat(final RaceSession s) {
		if (s.boat != null) {
			// Removing it from the index first makes the VehicleExitEvent caused by remove() a no-op
			this.boats.remove(s.boat.getUniqueId());

			s.boat.remove();
			s.boat = null;
		}
	}

	private void resetRunState(final RaceSession s) {
		for (final BukkitTask task : s.tasks)
			task.cancel();

		s.tasks.clear();

		s.state = RaceSession.State.COUNTDOWN;
		s.penaltyMs = 0L;
		s.falstarted = false;
		s.crossedStart = false;
		s.lap = 1;
		s.nextCheckpoint = 0;
		s.cpCount = s.heat.arena.getCheckpoints().size();
		s.splits = new long[s.cpCount == 0 ? 0 : s.heat.laps * (s.cpCount + 1) - 1];
		s.finalMs = 0L;
		s.deltaText = "";
		s.restartQueued = false;
		s.lastTrackPos = null;
		s.offTrack = false;
		s.offTrackTicks = 0;

		final RaceRecord best = this.storage.getRecord(s.heat.arena.getName(), s.heat.laps, s.playerId);

		s.bestTotalMs = best == null ? -1L : best.getBestMs();
		s.referenceSplits = best == null || best.getSplits().length == 0 ? null : best.getSplits();
	}

	private Location spawnOf(final RaceSession s) {
		Point point = s.heat.arena.getSpawns().get(s.slot);

		if (point == null)
			point = s.heat.arena.getPrimarySpawn();

		return point == null ? null : point.toLocation();
	}

	// ------------------------------------------------------------------------------------------
	// Solo run (join + reset)
	// ------------------------------------------------------------------------------------------

	private void startSoloRun(final RaceSession s) {
		final Heat heat = s.heat;

		// cancel the old countdown, restore fake blocks, fresh state - including any penalty
		this.cleanupHeat(heat);
		this.resetRunState(s);

		if (!this.place(s, this.spawnOf(s)))
			return;

		this.loadVisuals(heat);
		this.beginCountdown(heat, 0);
	}

	/**
	 * Teleports the player to {@code location} and seats them in a brand-new boat (old boat removed first).
	 *
	 * @return false when the racer had to leave (offline, world not loaded, boat could not spawn)
	 */
	private boolean place(final RaceSession s, final Location location) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || !player.isOnline()) {
			this.dispose(s, LeaveMode.SILENT);
			return false;
		}

		if (location == null || location.getWorld() == null) {
			Msg.send(player, "race.world-not-loaded");
			this.dispose(s, LeaveMode.HUB);
			return false;
		}

		this.removeBoat(s);

		s.internalTeleport = true;

		try {
			player.teleport(location);
		} finally {
			s.internalTeleport = false;
		}

		final Entity spawned = location.getWorld().spawnEntity(location, this.settings.boatType);

		if (!(spawned instanceof Boat boat)) {
			spawned.remove();
			Msg.send(player, "race.boat-spawn-failed");
			this.dispose(s, LeaveMode.HUB);
			return false;
		}

		boat.setInvulnerable(true);
		boat.setPersistent(false);

		s.boat = boat;
		s.holdPos = boat.getLocation().clone();
		s.restartQueued = false;
		this.boats.put(boat.getUniqueId(), s);

		boat.addPassenger(player);
		player.setFireTicks(0);

		final Zone start = s.heat.arena.getStartLine();
		final Zone finish = s.heat.arena.getFinishLine();

		s.inStartZone = start != null && start.contains(location);
		s.inFinishZone = finish != null && finish.contains(location);

		return true;
	}

	/**
	 * Puts the boat back without touching the clock: on the grid while waiting for the start, otherwise on the last
	 * position on a track block. Used for events (which cannot be restarted) and for off-track TELEPORT_BACK.
	 */
	private void rescue(final RaceSession s) {
		final boolean waiting = s.state == RaceSession.State.LOBBY || s.state == RaceSession.State.COUNTDOWN;
		final Location target = !waiting && s.lastTrackPos != null ? s.lastTrackPos : this.spawnOf(s);

		this.place(s, target);
	}

	// ------------------------------------------------------------------------------------------
	// Start lights + start signal
	// ------------------------------------------------------------------------------------------

	/**
	 * Loads the lamp block locations of the heat's instance. The start signal IS the lamp structure: the same blocks
	 * light up red during the countdown, turn green at lights out and switch to the off block afterwards.
	 */
	private void loadVisuals(final Heat heat) {
		if (heat.lampBlocks.isEmpty())
			for (final Region lamp : heat.arena.getLights())
				heat.lampBlocks.add(lamp.blocks());

		heat.signalBlocks.clear();

		if (this.settings.signalEnabled)
			for (final List<Location> lamp : heat.lampBlocks)
				heat.signalBlocks.addAll(lamp);
	}

	private void beginCountdown(final Heat heat, final long extraDelay) {
		heat.phase = Heat.Phase.COUNTDOWN;
		heat.litLamps = 0;

		this.loadVisuals(heat);
		heat.lampCount = heat.lampBlocks.isEmpty() ? this.settings.lamps : heat.lampBlocks.size();

		for (final RaceSession racer : heat.racers()) {
			racer.state = RaceSession.State.COUNTDOWN;

			final Player player = Bukkit.getPlayer(racer.playerId);

			if (player != null) {
				this.showLamps(player, heat, 0); // all dark before the sequence starts
			}
		}

		long delay = extraDelay + this.settings.firstDelayTicks;

		for (int lit = 1; lit <= heat.lampCount; lit++) {
			final int litLamps = lit;

			this.later(heat, delay, () -> this.lampOn(heat, litLamps));
			delay += this.settings.intervalTicks;
		}

		// "delay" now points one interval after the last lamp; the random hold starts right at the last lamp
		final long lastLamp = delay - this.settings.intervalTicks;
		final int hold = ThreadLocalRandom.current().nextInt(this.settings.holdMinTicks, this.settings.holdMaxTicks + 1);

		this.later(heat, lastLamp + Math.max(this.settings.intervalTicks, hold), () -> this.lightsOut(heat));
	}

	private boolean alive(final Heat heat) {
		return this.heats.get(heat.key) == heat;
	}

	private void lampOn(final Heat heat, final int lit) {
		if (!this.alive(heat) || heat.phase != Heat.Phase.COUNTDOWN)
			return;

		heat.litLamps = lit;

		for (final RaceSession racer : heat.racers()) {
			final Player player = Bukkit.getPlayer(racer.playerId);

			if (player != null) {
				this.showLamps(player, heat, lit);
				this.settings.soundCountdown.play(player, this.settings.soundCountdownPitchStep * (lit - 1));
			}
		}
	}

	private void lightsOut(final Heat heat) {
		if (!this.alive(heat) || heat.phase != Heat.Phase.COUNTDOWN)
			return;

		heat.phase = Heat.Phase.RACING;
		heat.startNanos = System.nanoTime();
		heat.fieldSize = heat.slots.size();

		for (final RaceSession racer : heat.racers()) {
			if (racer.state != RaceSession.State.COUNTDOWN)
				continue;

			racer.state = RaceSession.State.RUNNING;
			racer.restartFailures = 0;

			// Fallback for a misconfigured spawn that lies inside the start line: the racer is already on the line
			if (racer.inStartZone)
				racer.crossedStart = true;

			final Player player = Bukkit.getPlayer(racer.playerId);

			if (player != null) {
				// All lamps go out = GO. With the start signal enabled they turn green right below and switch to the
				// off block after start-signal.duration-seconds; without it they stay dark.
				this.showLamps(player, heat, 0);
				this.settings.soundGo.play(player);
				Msg.title(player, "go");
			}
		}

		this.signalGreen(heat);
	}

	/** Shows the first {@code lit} lamps lit and the rest dark - only to this player. */
	private void showLamps(final Player player, final Heat heat, final int lit) {
		for (int i = 0; i < heat.lampBlocks.size(); i++) {
			final BlockData data = i < lit ? this.settings.lampOn : this.settings.lampOff;

			for (final Location location : heat.lampBlocks.get(i))
				player.sendBlockChange(location, data);
		}
	}

	/** Lights out: the start signal turns green, and back to "off" after the configured duration. */
	private void signalGreen(final Heat heat) {
		if (heat.signalBlocks.isEmpty())
			return;

		if (this.settings.signalMode == Settings.SignalMode.WORLD) {
			for (final Location location : heat.signalBlocks) {
				final Block block = location.getBlock();

				heat.signalOriginal.putIfAbsent(location, block.getBlockData());
				block.setBlockData(this.settings.signalGo, false);
			}

		} else
			for (final RaceSession racer : heat.racers()) {
				final Player player = Bukkit.getPlayer(racer.playerId);

				if (player != null)
					for (final Location location : heat.signalBlocks)
						player.sendBlockChange(location, this.settings.signalGo);
			}

		heat.signalGreen = true;
		this.later(heat, this.settings.signalDurationTicks, () -> this.signalOff(heat));
	}

	private void signalOff(final Heat heat) {
		if (!heat.signalGreen)
			return;

		heat.signalGreen = false;

		if (this.settings.signalMode == Settings.SignalMode.WORLD) {
			for (final Map.Entry<Location, BlockData> entry : heat.signalOriginal.entrySet())
				entry.getKey().getBlock().setBlockData(this.settings.signalOff != null ? this.settings.signalOff : entry.getValue(), false);

			heat.signalOriginal.clear();

		} else
			for (final RaceSession racer : heat.racers()) {
				final Player player = Bukkit.getPlayer(racer.playerId);

				if (player != null)
					for (final Location location : heat.signalBlocks)
						player.sendBlockChange(location, this.settings.signalOff != null ? this.settings.signalOff : location.getBlock().getBlockData());
			}
	}

	/** Shows the real blocks of the lamps (= start signal) again to this player. */
	private void revertVisuals(final Player player, final Heat heat) {
		for (final List<Location> lamp : heat.lampBlocks)
			for (final Location location : lamp)
				player.sendBlockChange(location, location.getBlock().getBlockData());
	}

	/** Cancels the countdown, restores every fake / changed block of the heat. Keeps the racers. */
	private void cleanupHeat(final Heat heat) {
		for (final BukkitTask task : heat.tasks)
			task.cancel();

		heat.tasks.clear();

		// WORLD mode: never leave the "go" blocks in the world
		if (heat.signalGreen && this.settings.signalMode == Settings.SignalMode.WORLD) {
			for (final Map.Entry<Location, BlockData> entry : heat.signalOriginal.entrySet())
				entry.getKey().getBlock().setBlockData(entry.getValue(), false);

			heat.signalOriginal.clear();
		}

		heat.signalGreen = false;

		for (final RaceSession racer : heat.racers()) {
			final Player player = Bukkit.getPlayer(racer.playerId);

			if (player != null)
				this.revertVisuals(player, heat);
		}

		heat.lampBlocks.clear();
		heat.litLamps = 0;
	}

	private void later(final Heat heat, final long delayTicks, final Runnable runnable) {
		heat.tasks.add(Bukkit.getScheduler().runTaskLater(this.plugin, runnable, delayTicks));
	}

	private void later(final RaceSession s, final long delayTicks, final Runnable runnable) {
		s.tasks.add(Bukkit.getScheduler().runTaskLater(this.plugin, runnable, delayTicks));
	}

	// ------------------------------------------------------------------------------------------
	// Movement: jumpstart, start line, checkpoints, finish
	// ------------------------------------------------------------------------------------------

	public void handleVehicleMove(final Vehicle vehicle, final Location from, final Location to) {
		final RaceSession s = this.boats.get(vehicle.getUniqueId());

		if (s == null || s.disposed || s.heat == null)
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null)
			return;

		switch (s.state) {
			case LOBBY, COUNTDOWN -> this.handleWaitingMove(s, player, from, to);
			case RUNNING -> this.handleRunningMove(s, player, from, to);
			default -> {
			}
		}
	}

	private void handleWaitingMove(final RaceSession s, final Player player, final Location from, final Location to) {
		if (s.holdPos == null || to.getWorld() != s.holdPos.getWorld())
			return;

		// a jump start is only possible once the lights sequence runs (not in an event lobby)
		final boolean countdown = s.state == RaceSession.State.COUNTDOWN;
		final Zone start = s.heat.arena.getStartLine();

		if (start != null) {
			final boolean wasIn = s.inStartZone;

			s.inStartZone = start.contains(to);

			if (countdown && !wasIn && start.intersects(from, to))
				this.jumpstart(s, player);
		}

		final double dx = to.getX() - s.holdPos.getX();
		final double dz = to.getZ() - s.holdPos.getZ();
		final double distanceSquared = dx * dx + dz * dz;

		if (countdown && distanceSquared > 0.25D) // moved more than half a block
			this.jumpstart(s, player);

		// keep the boat on the grid
		if (distanceSquared > 1.0D && s.boat != null) {
			s.internalTeleport = true;

			try {
				s.boat.teleport(s.holdPos, TeleportFlag.EntityState.RETAIN_PASSENGERS);
			} finally {
				s.internalTeleport = false;
			}

			if (start != null)
				s.inStartZone = start.contains(s.holdPos);
		}
	}

	/** Dynamic penalty: the earlier the jumpstart (fewer lamps lit), the higher the penalty. */
	private void jumpstart(final RaceSession s, final Player player) {
		if (s.state != RaceSession.State.COUNTDOWN || s.heat.phase != Heat.Phase.COUNTDOWN || s.falstarted)
			return;

		final long penalty = this.settings.penaltyFor(s.heat.litLamps, s.heat.lampCount);

		s.falstarted = true;
		s.penaltyMs = penalty;

		Msg.title(player, "jumpstart", "penalty", TimeFmt.delta(penalty));
		this.settings.soundJumpstart.play(player);
	}

	private void handleRunningMove(final RaceSession s, final Player player, final Location from, final Location to) {
		final Arena arena = s.heat.arena;
		final Zone start = arena.getStartLine();
		final Zone finish = arena.getFinishLine();

		if (start == null || finish == null)
			return;

		// Edge detection: "entered" = was outside after the previous move and touches the zone during this one
		final boolean startEntered = !s.inStartZone && start.intersects(from, to);
		final boolean finishEntered = !s.inFinishZone && finish.intersects(from, to);

		s.inStartZone = start.contains(to);
		s.inFinishZone = finish.contains(to);

		// 1) nothing counts until the racer has crossed the start line after lights out
		if (!s.crossedStart) {
			if (startEntered)
				s.crossedStart = true;

			return;
		}

		// 2) next checkpoint in order (invisible - nothing is ever displayed in the world)
		final List<Region> checkpoints = arena.getCheckpoints();

		// The run only counts the checkpoints that existed when it started (an admin may add more while it is running)
		final int total = Math.min(checkpoints.size(), s.cpCount);

		if (s.nextCheckpoint < total && checkpoints.get(s.nextCheckpoint).intersects(from, to))
			this.checkpointReached(s, player, s.nextCheckpoint);

		// 3) lap line / finish - only with 100% of the checkpoints of this lap passed in order
		if (!finishEntered)
			return;

		if (total > 0 && s.nextCheckpoint >= total) {
			if (s.lap < s.heat.laps)
				this.lapCompleted(s, player);
			else
				this.finish(s, player);

			return;
		}

		// Driving back over the line right after the start is harmless; skipping checkpoints is not
		if (s.nextCheckpoint > 0) {
			Msg.title(player, "missed-checkpoint", "checkpoint", s.nextCheckpoint + 1, "total", total);
			this.settings.soundMissedCheckpoint.play(player);

			if (s.heat.event)
				// an event cannot be restarted - back to the last checkpoint is not tracked, so the lap simply does not count
				return;

			this.queueRestart(s);
		}
	}

	/**
	 * Silent: no chat and no checkpoint text. Only the delta to the personal best is updated - it stays on the
	 * action bar next to the running time until the next checkpoint replaces it.
	 */
	private void checkpointReached(final RaceSession s, final Player player, final int index) {
		this.recordSplit(s, (s.lap - 1) * (s.cpCount + 1) + index);
		s.nextCheckpoint = index + 1;

		this.settings.soundCheckpoint.play(player);
	}

	/** All checkpoints of a lap passed and the line crossed, but laps remain: count the lap and go on. */
	private void lapCompleted(final RaceSession s, final Player player) {
		this.recordSplit(s, (s.lap - 1) * (s.cpCount + 1) + s.cpCount);

		s.lap++;
		s.nextCheckpoint = 0;

		Msg.title(player, s.lap == s.heat.laps ? "final-lap" : "new-lap", "lap", s.lap, "laps", s.heat.laps);
		this.settings.soundLap.play(player);
	}

	/** Stores the elapsed time at a split and updates the permanent action bar delta to the personal best. */
	private void recordSplit(final RaceSession s, final int splitIndex) {
		if (splitIndex < 0 || splitIndex >= s.splits.length)
			return;

		final long elapsed = this.elapsedMs(s);

		s.splits[splitIndex] = elapsed;

		if (s.referenceSplits != null && splitIndex < s.referenceSplits.length && s.referenceSplits[splitIndex] > 0L)
			s.deltaText = deltaText(elapsed - s.referenceSplits[splitIndex]);
	}

	/** "Lap 2/5 | " for multi-lap races, "" for single-lap ones (messages.yml actionbar.lap). */
	private static String lapText(final RaceSession s) {
		return s.heat.laps > 1 ? Msg.text("actionbar.lap", "lap", Math.min(s.lap, s.heat.laps), "laps", s.heat.laps) : "";
	}

	private void finish(final RaceSession s, final Player player) {
		s.state = RaceSession.State.FINISHED;
		s.finalMs = this.elapsedMs(s);

		final Heat heat = s.heat;
		final String arena = heat.arena.getName();
		final long previousBest = s.bestTotalMs;
		final boolean blacklisted = this.storage.isBlacklisted(s.playerId);
		final boolean newBest = this.storage.submitRun(arena, heat.laps, s.playerId, player.getName(), s.finalMs, s.penaltyMs, s.splits.clone());
		final boolean rewarded = !blacklisted && this.giveReward(s, player);
		final int position = heat.event ? ++heat.finishedCount : 0;

		final String summary;

		if (blacklisted)
			summary = "finish.banned";

		else if (newBest)
			summary = previousBest > 0L ? "finish.new-best" : "finish.first";

		else
			summary = "finish.normal";

		final long deltaMs = previousBest > 0L ? s.finalMs - previousBest : 0L;

		// The only chat message of a whole race
		Msg.raw(player, summary,
				"player", player.getName(),
				"arena", arena,
				"time", TimeFmt.format(s.finalMs),
				"penalty_note", s.penaltyMs > 0L ? Msg.text("finish.penalty-note", "penalty", TimeFmt.delta(s.penaltyMs)) : "",
				"best", previousBest > 0L ? TimeFmt.format(previousBest) : "-",
				"delta", TimeFmt.delta(deltaMs),
				"rank", Math.max(0, this.storage.getRank(arena, heat.laps, s.playerId)),
				"laps", heat.laps,
				"laps_note", heat.laps > 1 ? Msg.text("finish.laps-note", "laps", heat.laps) : "",
				"reward_note", rewarded ? Msg.text("finish.reward-note", "arena", arena) : "",
				"event_note", heat.event ? Msg.text("finish.event-note", "position", position, "racers", heat.fieldSize) : "");

		// Shown on the action bar during the pause before the teleport
		s.deltaText = previousBest > 0L ? deltaText(deltaMs) : "";

		Msg.title(player, "finish", "time", TimeFmt.format(s.finalMs), "arena", arena, "laps", heat.laps);
		this.settings.soundFinish.play(player);

		// Short pause with the result on screen, then back to the hub (the boat is removed on dispose)
		this.later(s, this.settings.finishDelayTicks, () -> {
			if (!s.disposed && s.state == RaceSession.State.FINISHED && this.sessions.get(s.playerId) == s)
				this.dispose(s, LeaveMode.HUB);
		});
	}

	/** "(+1.23s)" / "(-0.45s)" as configured in messages.yml. */
	private static String deltaText(final long deltaMs) {
		return Msg.text(deltaMs <= 0L ? "delta.faster" : "delta.slower", "delta", TimeFmt.delta(deltaMs), "delta_padded", TimeFmt.deltaPadded(deltaMs));
	}

	private long elapsedMs(final RaceSession s) {
		return (System.nanoTime() - s.heat.startNanos) / 1_000_000L + s.penaltyMs;
	}

	// ------------------------------------------------------------------------------------------
	// Off-track detection
	// ------------------------------------------------------------------------------------------

	private void checkOffTrack(final RaceSession s, final Player player) {
		final Set<Material> allowed = s.heat.arena.getTrackBlocks().isEmpty() ? this.settings.defaultTrackBlocks : s.heat.arena.getTrackBlocks();

		if (allowed.isEmpty() || s.boat == null)
			return;

		final Location location = s.boat.getLocation();
		final Material below = this.blockBelow(location);

		// airborne (jump, drop) - neither on nor off the track
		if (below == null)
			return;

		if (allowed.contains(below)) {
			s.offTrackTicks = 0;
			s.offTrack = false;
			s.lastTrackPos = location.clone();
			return;
		}

		s.offTrackTicks += this.settings.offTrackIntervalTicks;

		if (s.offTrack || s.offTrackTicks < this.settings.offTrackGraceTicks)
			return;

		s.offTrack = true;

		final long penalty = this.settings.offTrackPenaltyMs;
		Settings.OffTrackAction action = this.settings.offTrackAction;

		// an event cannot be restarted
		if (action == Settings.OffTrackAction.RESET && s.heat.event)
			action = Settings.OffTrackAction.TELEPORT_BACK;

		this.settings.soundOffTrack.play(player);

		switch (action) {
			case PENALTY -> {
				s.penaltyMs += penalty;

				Msg.title(player, "off-track", "penalty", TimeFmt.delta(penalty));
				Msg.send(player, "race.off-track", "penalty", TimeFmt.delta(penalty));
			}
			case TELEPORT_BACK -> {
				s.penaltyMs += penalty;

				Msg.title(player, "off-track-back", "penalty", TimeFmt.delta(penalty));
				Msg.send(player, "race.off-track-back", "penalty", TimeFmt.delta(penalty));

				this.rescue(s);
				s.offTrack = false;
				s.offTrackTicks = 0;
			}
			case RESET -> {
				Msg.title(player, "off-track-reset");
				Msg.send(player, "race.off-track-reset");

				this.queueRestart(s);
			}
		}
	}

	/** Material of the first non-air block under the boat (scan-depth blocks deep), or null when airborne. */
	private Material blockBelow(final Location location) {
		final World world = location.getWorld();

		if (world == null)
			return null;

		final int x = location.getBlockX();
		final int z = location.getBlockZ();
		final int top = (int) Math.floor(location.getY() - 0.1D);

		for (int depth = 0; depth < this.settings.offTrackScanDepth; depth++) {
			final Material type = world.getBlockAt(x, top - depth, z).getType();

			if (!type.isAir())
				return type;
		}

		return null;
	}

	// ------------------------------------------------------------------------------------------
	// Rewards
	// ------------------------------------------------------------------------------------------

	/**
	 * @return true when the player gets the reward (ALWAYS: commands already ran; ONCE: the commands run as soon as
	 *         the shared database confirms that this is really the first claim on the whole network)
	 */
	private boolean giveReward(final RaceSession s, final Player player) {
		final Arena arena = s.heat.arena;

		if (arena.getRewardCommands().isEmpty())
			return false;

		final List<String> commands = new ArrayList<>(arena.getRewardCommands());
		final String arenaName = arena.getName();
		final String playerName = player.getName();
		final UUID uuid = player.getUniqueId();
		final long time = s.finalMs;

		switch (arena.getRewardMode()) {
			case ONCE -> {
				if (this.storage.hasClaimedReward(arenaName, uuid))
					return false;

				this.storage.claimOnceReward(arenaName, uuid, () -> this.runRewardCommands(commands, arenaName, playerName, uuid, time));
				return true;
			}
			case ALWAYS -> {
				this.runRewardCommands(commands, arenaName, playerName, uuid, time);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private void runRewardCommands(final List<String> commands, final String arena, final String player, final UUID uuid, final long timeMs) {
		for (final String raw : commands) {
			String command = raw.trim();

			if (command.startsWith("/"))
				command = command.substring(1);

			command = command
					.replace("{player}", player)
					.replace("{uuid}", uuid.toString())
					.replace("{arena}", arena)
					.replace("{time}", TimeFmt.format(timeMs));

			try {
				Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

			} catch (final Exception ex) {
				this.plugin.getLogger().warning("Reward command '" + command + "' of arena " + arena + " failed: " + ex.getMessage());
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Boat exit / quit / foreign teleports
	// ------------------------------------------------------------------------------------------

	/** The racer left the boat: solo = fresh attempt, event = a new boat without touching the clock. */
	public void handleVehicleExit(final Vehicle vehicle, final Entity exited) {
		final RaceSession s = this.boats.get(vehicle.getUniqueId());

		if (s == null || s.disposed || !s.playerId.equals(exited.getUniqueId()))
			return;

		// after the finish the player is about to be sent to the hub anyway
		if (s.state == RaceSession.State.FINISHED)
			return;

		this.queueRestart(s);
	}

	private void queueRestart(final RaceSession s) {
		if (s.restartQueued || s.disposed)
			return;

		s.restartQueued = true;

		// next tick - never remove / teleport inside the event that triggered this
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			if (s.disposed || this.sessions.get(s.playerId) != s || s.heat == null)
				return;

			s.restartQueued = false;

			if (s.heat.event)
				this.rescue(s);
			else
				this.startSoloRun(s);
		});
	}

	public void handleQuit(final Player player) {
		final RaceSession s = this.sessions.get(player.getUniqueId());

		if (s != null)
			this.dispose(s, LeaveMode.ORIGIN);
	}

	/** Another plugin / command (e.g. /spawn, /home) moved a racer away -> they have left the race. */
	public void handleForeignTeleport(final Player player) {
		final RaceSession s = this.sessions.get(player.getUniqueId());

		if (s == null || s.internalTeleport)
			return;

		// next tick, so the teleport that is currently being processed can finish first
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			if (!s.disposed && this.sessions.get(s.playerId) == s)
				this.dispose(s, LeaveMode.SILENT);
		});
	}

	public boolean isInternalTeleport(final Player player) {
		final RaceSession s = this.sessions.get(player.getUniqueId());

		return s != null && s.internalTeleport;
	}

	/** Called a tick after a join: gives back an inventory that was left over from a crash. */
	public void handleJoin(final Player player) {
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			if (player.isOnline() && !this.isRacing(player)) {
				final Location origin = PlayerSnapshot.restore(this.plugin, player);

				if (origin != null)
					player.teleport(origin);
			}
		});
	}

	// ------------------------------------------------------------------------------------------
	// Ticker: action bar (every tick) + off-track check
	// ------------------------------------------------------------------------------------------

	private void tick() {
		if (this.sessions.isEmpty())
			return;

		final boolean offTrackTick = this.settings.offTrackEnabled && ++this.tickCounter % this.settings.offTrackIntervalTicks == 0;

		for (final RaceSession s : new ArrayList<>(this.sessions.values())) {
			if (s.disposed || s.heat == null)
				continue;

			final Player player = Bukkit.getPlayer(s.playerId);

			if (player == null)
				continue;

			// Safety net: boat gone or the player is somehow no longer in it
			if (!s.restartQueued && s.boat != null && s.state != RaceSession.State.FINISHED) {
				final Entity vehicle = player.getVehicle();
				final boolean seated = vehicle != null && vehicle.getUniqueId().equals(s.boat.getUniqueId());

				if (!s.boat.isValid() || !seated) {
					if (++s.restartFailures > 5) {
						Msg.send(player, "race.seat-failed");
						this.dispose(s, LeaveMode.HUB);
						continue;
					}

					this.queueRestart(s);
					continue;
				}
			}

			if (offTrackTick && s.state == RaceSession.State.RUNNING && s.crossedStart && !s.restartQueued)
				this.checkOffTrack(s, player);

			if (s.disposed || s.heat == null)
				continue;

			switch (s.state) {
				case LOBBY -> Msg.actionBar(player, "actionbar.lobby", "time", TimeFmt.format(0L), "lap_text", lapText(s), "laps", s.heat.laps,
						"players", s.heat.slots.size(), "slots", s.heat.capacity(), "position", s.slot);
				case COUNTDOWN -> {
					if (s.penaltyMs > 0L)
						Msg.actionBar(player, "actionbar.countdown-penalty", "time", TimeFmt.format(0L), "penalty", TimeFmt.delta(s.penaltyMs), "lap_text", lapText(s));
					else
						Msg.actionBar(player, "actionbar.countdown", "time", TimeFmt.format(0L), "lap_text", lapText(s));
				}
				case RUNNING -> {
					final String time = TimeFmt.format(this.elapsedMs(s));

					if (s.deltaText.isEmpty())
						Msg.actionBar(player, "actionbar.running", "time", time, "lap_text", lapText(s), "lap", s.lap, "laps", s.heat.laps);
					else
						Msg.actionBar(player, "actionbar.running-delta", "time", time, "delta_text", s.deltaText,
								"lap_text", lapText(s), "lap", s.lap, "laps", s.heat.laps);
				}
				case FINISHED -> Msg.actionBar(player, "actionbar.finished", "time", TimeFmt.format(s.finalMs), "delta_text", s.deltaText,
						"lap_text", lapText(s), "lap", s.heat.laps, "laps", s.heat.laps);
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Hotbar buttons
	// ------------------------------------------------------------------------------------------

	private void giveHotbar(final Player player) {
		if (!this.settings.hotbarEnabled)
			return;

		player.getInventory().setItem(this.settings.resetSlot, this.button(Material.CLOCK, Msg.text("items.reset"), "reset"));
		player.getInventory().setItem(this.settings.hubSlot, this.button(Material.RED_BED, Msg.text("items.hub"), "hub"));
	}

	private ItemStack button(final Material material, final String name, final String action) {
		final ItemStack item = new ItemStack(material);
		final ItemMeta meta = item.getItemMeta();

		meta.displayName(Msg.item(name));
		meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.STRING, action);
		item.setItemMeta(meta);

		return item;
	}

	private String itemAction(final ItemStack item) {
		if (item == null || !item.hasItemMeta())
			return null;

		return item.getItemMeta().getPersistentDataContainer().get(this.itemKey, PersistentDataType.STRING);
	}
}
