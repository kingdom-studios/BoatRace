package pl.kingdomcraft.boatrace.race;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
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
import pl.kingdomcraft.boatrace.arena.Region;
import pl.kingdomcraft.boatrace.storage.RaceRecord;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.Msg;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * The race engine: join / leave / reset, F1 start lights, dynamic jumpstart penalty, start line, invisible
 * checkpoint zones, finish line and rewards.
 *
 * Lap validation: a finish only counts when the race was started correctly (lights out), the racer crossed the
 * start line afterwards and then passed 100% of the checkpoints in order. This makes tracks where the start and
 * finish line are the same zone safe - respawning in front of the line can never complete a lap.
 *
 * Performance notes:
 *  - ONE repeating task (every tick, so the milliseconds run smoothly) updates the action bar of all racers.
 *  - Zones are only evaluated inside VehicleMoveEvent of race boats (O(1) map lookup for any other vehicle).
 *  - Times use System.nanoTime(). Start lamps are client-side block changes, so the world is never modified.
 */
public final class RaceManager {

	/** Outcome of {@link #join(Player, Arena)}; the racer has already been told about failures. */
	public enum JoinResult {
		JOINED,
		BANNED,
		NOT_READY,
		FAILED
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

	private BukkitTask ticker;

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

	/** Server shutdown / plugin disable: remove every boat and give everybody their inventory back. */
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

	public Arena pickRandomReadyArena() {
		final List<Arena> ready = this.arenas.readyArenas();

		return ready.isEmpty() ? null : ready.get(ThreadLocalRandom.current().nextInt(ready.size()));
	}

	/**
	 * Joins the arena (or switches to it when the player already races). Silent on success: the player simply
	 * appears in a boat on the grid.
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

		if (existing != null) {
			existing.arena = arena;
			this.startRun(existing);

			return existing.disposed ? JoinResult.FAILED : JoinResult.JOINED;
		}

		// A snapshot left behind by a crash must be restored BEFORE we take a new one
		PlayerSnapshot.restore(this.plugin, player);

		if (!PlayerSnapshot.save(this.plugin, player)) {
			Msg.send(player, "race.inventory-save-failed");
			return JoinResult.FAILED;
		}

		final RaceSession session = new RaceSession(player.getUniqueId(), arena);

		this.sessions.put(player.getUniqueId(), session);

		player.getInventory().clear();
		player.setGameMode(GameMode.ADVENTURE);
		player.setFireTicks(0);

		this.giveHotbar(player);
		this.startRun(session);

		return session.disposed ? JoinResult.FAILED : JoinResult.JOINED;
	}

	/** /bre leave or the [HUB] button. Silent: no chat output at all. */
	public void leave(final Player player) {
		final RaceSession session = this.sessions.get(player.getUniqueId());

		if (session != null)
			this.dispose(session, LeaveMode.HUB);
	}

	/**
	 * /bre reset or the [RESET] button. Silent: no chat output at all.
	 * Starts a completely fresh attempt - a jumpstart penalty of the current attempt is wiped.
	 */
	public void reset(final Player player) {
		final RaceSession session = this.sessions.get(player.getUniqueId());

		if (session == null)
			return;

		final long now = System.currentTimeMillis();

		if (now - session.lastResetMs < 500L)
			return;

		session.lastResetMs = now;
		this.startRun(session);
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
	// Run start (join + reset share this)
	// ------------------------------------------------------------------------------------------

	private void startRun(final RaceSession s) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || !player.isOnline()) {
			this.dispose(s, LeaveMode.SILENT);
			return;
		}

		final Location spawn = s.arena.getSpawn() == null ? null : s.arena.getSpawn().toLocation();

		if (spawn == null) {
			Msg.send(player, "race.world-not-loaded");
			this.dispose(s, LeaveMode.HUB);
			return;
		}

		// 1) kill the old boat right away (no entity spam), cancel old timers, restore fake lamps
		this.cleanupRun(s);

		// 2) reset the run state - EVERYTHING, including any jumpstart penalty (it must never leak into a new attempt)
		s.state = RaceSession.State.COUNTDOWN;
		s.startNanos = 0L;
		s.penaltyMs = 0L;
		s.falstarted = false;
		s.litLamps = 0;
		s.lampCount = 0;
		s.crossedStart = false;
		s.nextCheckpoint = 0;
		s.splits = new long[s.arena.getCheckpoints().size()];
		s.finalMs = 0L;
		s.deltaText = "";
		s.restartQueued = false;

		final Region start = s.arena.getStartLine();
		final Region finish = s.arena.getFinishLine();

		s.inStartZone = start != null && start.contains(spawn);
		s.inFinishZone = finish != null && finish.contains(spawn);

		final RaceRecord best = this.storage.getRecord(s.arena.getName(), s.playerId);

		s.bestTotalMs = best == null ? -1L : best.getBestMs();
		s.referenceSplits = best == null || best.getSplits().length == 0 ? null : best.getSplits();

		// 3) teleport the player to the start and seat them in a brand-new boat
		s.internalTeleport = true;

		try {
			player.teleport(spawn);
		} finally {
			s.internalTeleport = false;
		}

		final Entity spawned = spawn.getWorld().spawnEntity(spawn, this.settings.boatType);

		if (!(spawned instanceof Boat boat)) {
			spawned.remove();
			Msg.send(player, "race.boat-spawn-failed");
			this.dispose(s, LeaveMode.HUB);
			return;
		}

		boat.setInvulnerable(true);
		boat.setPersistent(false);

		s.boat = boat;
		s.holdPos = boat.getLocation().clone();
		this.boats.put(boat.getUniqueId(), s);

		boat.addPassenger(player);
		player.setFireTicks(0);

		// 4) F1 start lights
		this.beginCountdown(s);
	}

	/** Cancels timers, restores the fake lamp blocks and kills the boat. Does not touch the session maps. */
	private void cleanupRun(final RaceSession s) {
		for (final BukkitTask task : s.tasks)
			task.cancel();

		s.tasks.clear();

		this.revertLamps(s);

		if (s.boat != null) {
			// Removing it from the index first makes the VehicleExitEvent caused by remove() a no-op
			this.boats.remove(s.boat.getUniqueId());

			s.boat.remove();
			s.boat = null;
		}
	}

	private void dispose(final RaceSession s, final LeaveMode mode) {
		if (s.disposed)
			return;

		s.disposed = true;
		this.sessions.remove(s.playerId);
		this.cleanupRun(s);

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

	// ------------------------------------------------------------------------------------------
	// Start lights
	// ------------------------------------------------------------------------------------------

	private void beginCountdown(final RaceSession s) {
		for (final Region lamp : s.arena.getLights())
			s.lampBlocks.add(lamp.blocks());

		s.lampCount = s.lampBlocks.isEmpty() ? this.settings.lamps : s.lampBlocks.size();

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null)
			this.showLamps(player, s, 0); // all dark before the sequence starts

		long delay = this.settings.firstDelayTicks;

		for (int lit = 1; lit <= s.lampCount; lit++) {
			final int litLamps = lit;

			this.later(s, delay, () -> this.lampOn(s, litLamps));
			delay += this.settings.intervalTicks;
		}

		// "delay" now points one interval after the last lamp; the random hold starts right at the last lamp
		final long lastLamp = delay - this.settings.intervalTicks;
		final int hold = ThreadLocalRandom.current().nextInt(this.settings.holdMinTicks, this.settings.holdMaxTicks + 1);

		this.later(s, lastLamp + Math.max(this.settings.intervalTicks, hold), () -> this.lightsOut(s));
	}

	private void lampOn(final RaceSession s, final int lit) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || s.disposed || s.state != RaceSession.State.COUNTDOWN)
			return;

		s.litLamps = lit;
		this.showLamps(player, s, lit);

		final float pitch = 0.6F + 0.8F * ((float) lit / (float) s.lampCount);

		player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, pitch);
	}

	private void lightsOut(final RaceSession s) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || s.disposed || s.state != RaceSession.State.COUNTDOWN)
			return;

		// All lamps turn black = GO. They stay black (they are only reverted on reset / leave).
		this.showLamps(player, s, 0);

		s.state = RaceSession.State.RUNNING;
		s.startNanos = System.nanoTime();
		s.restartFailures = 0;

		// Fallback for a misconfigured spawn that lies inside the start line: the racer is already on the line
		if (s.inStartZone)
			s.crossedStart = true;

		player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.5F);
		Msg.title(player, "go");
	}

	/** Shows the first {@code lit} lamps lit and the rest dark - only to this player. */
	private void showLamps(final Player player, final RaceSession s, final int lit) {
		for (int i = 0; i < s.lampBlocks.size(); i++) {
			final BlockData data = i < lit ? this.settings.lampOn : this.settings.lampOff;

			for (final Location location : s.lampBlocks.get(i))
				player.sendBlockChange(location, data);
		}
	}

	private void revertLamps(final RaceSession s) {
		if (s.lampBlocks.isEmpty())
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null)
			for (final List<Location> lamp : s.lampBlocks)
				for (final Location location : lamp)
					player.sendBlockChange(location, location.getBlock().getBlockData());

		s.lampBlocks.clear();
	}

	private void later(final RaceSession s, final long delayTicks, final Runnable runnable) {
		s.tasks.add(Bukkit.getScheduler().runTaskLater(this.plugin, runnable, delayTicks));
	}

	// ------------------------------------------------------------------------------------------
	// Movement: jumpstart, start line, checkpoints, finish
	// ------------------------------------------------------------------------------------------

	public void handleVehicleMove(final Vehicle vehicle, final Location from, final Location to) {
		final RaceSession s = this.boats.get(vehicle.getUniqueId());

		if (s == null || s.disposed)
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null)
			return;

		switch (s.state) {
			case COUNTDOWN -> this.handleCountdownMove(s, player, from, to);
			case RUNNING -> this.handleRunningMove(s, player, from, to);
			default -> {
			}
		}
	}

	private void handleCountdownMove(final RaceSession s, final Player player, final Location from, final Location to) {
		if (s.holdPos == null || to.getWorld() != s.holdPos.getWorld())
			return;

		// crossing the start line before lights out
		final Region start = s.arena.getStartLine();

		if (start != null) {
			final boolean wasIn = s.inStartZone;

			s.inStartZone = start.contains(to);

			if (!wasIn && start.intersects(from, to))
				this.jumpstart(s, player);
		}

		final double dx = to.getX() - s.holdPos.getX();
		final double dz = to.getZ() - s.holdPos.getZ();
		final double distanceSquared = dx * dx + dz * dz;

		if (distanceSquared > 0.25D) // moved more than half a block
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
		if (s.state != RaceSession.State.COUNTDOWN || s.falstarted)
			return;

		final long penalty = this.settings.penaltyFor(s.litLamps, s.lampCount);

		s.falstarted = true;
		s.penaltyMs = penalty;

		Msg.title(player, "jumpstart", "penalty", TimeFmt.delta(penalty));
		player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
	}

	private void handleRunningMove(final RaceSession s, final Player player, final Location from, final Location to) {
		final Region start = s.arena.getStartLine();
		final Region finish = s.arena.getFinishLine();

		if (start == null || finish == null)
			return;

		// Edge detection: "entered" = was outside after the previous move and touches the zone during this one
		final boolean startEntered = this.entered(start, s.inStartZone, from, to);
		final boolean finishEntered = this.entered(finish, s.inFinishZone, from, to);

		s.inStartZone = start.contains(to);
		s.inFinishZone = finish.contains(to);

		// 1) nothing counts until the racer has crossed the start line after lights out
		if (!s.crossedStart) {
			if (startEntered)
				s.crossedStart = true;

			return;
		}

		// 2) next checkpoint in order (invisible - nothing is ever displayed in the world)
		final List<Region> checkpoints = s.arena.getCheckpoints();

		// The run only counts the checkpoints that existed when it started (an admin may add more while it is running)
		final int total = Math.min(checkpoints.size(), s.splits.length);

		if (s.nextCheckpoint < total && checkpoints.get(s.nextCheckpoint).intersects(from, to))
			this.checkpointReached(s, player, s.nextCheckpoint);

		// 3) finish - only with 100% of the checkpoints passed in order
		if (!finishEntered)
			return;

		if (total > 0 && s.nextCheckpoint >= total) {
			this.finish(s, player);
			return;
		}

		// Driving back over the line right after the start is harmless; skipping checkpoints is not
		if (s.nextCheckpoint > 0) {
			Msg.title(player, "missed-checkpoint", "checkpoint", s.nextCheckpoint + 1, "total", total);
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 0.8F);

			this.queueRestart(s);
		}
	}

	private boolean entered(final Region region, final boolean wasInside, final Location from, final Location to) {
		return !wasInside && region.intersects(from, to);
	}

	/**
	 * Silent: no chat and no checkpoint text. Only the delta to the personal best is updated - it stays on the
	 * action bar next to the running time until the next checkpoint replaces it.
	 */
	private void checkpointReached(final RaceSession s, final Player player, final int index) {
		final long elapsed = this.elapsedMs(s);

		s.splits[index] = elapsed;
		s.nextCheckpoint = index + 1;

		if (s.referenceSplits != null && index < s.referenceSplits.length && s.referenceSplits[index] > 0L)
			s.deltaText = deltaText(elapsed - s.referenceSplits[index]);

		player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8F, 1.4F);
	}

	private void finish(final RaceSession s, final Player player) {
		s.state = RaceSession.State.FINISHED;
		s.finalMs = this.elapsedMs(s);

		final String arena = s.arena.getName();
		final long previousBest = s.bestTotalMs;
		final boolean blacklisted = this.storage.isBlacklisted(s.playerId);
		final boolean newBest = this.storage.submitIfBetter(arena, s.playerId, player.getName(), s.finalMs, s.splits.clone());
		final boolean rewarded = !blacklisted && this.giveReward(s, player);

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
				"rank", Math.max(0, this.storage.getRank(arena, s.playerId)),
				"reward_note", rewarded ? Msg.text("finish.reward-note", "arena", arena) : "");

		// Shown on the action bar during the pause before the teleport
		s.deltaText = previousBest > 0L ? deltaText(deltaMs) : "";

		Msg.title(player, "finish", "time", TimeFmt.format(s.finalMs), "arena", arena);
		player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);

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

	// ------------------------------------------------------------------------------------------
	// Rewards
	// ------------------------------------------------------------------------------------------

	/** @return true when reward commands were executed */
	private boolean giveReward(final RaceSession s, final Player player) {
		final Arena arena = s.arena;
		final List<String> commands = arena.getRewardCommands();

		if (commands.isEmpty())
			return false;

		switch (arena.getRewardMode()) {
			case NONE:
				return false;

			case ONCE:
				if (!this.storage.claimOnceReward(arena.getName(), s.playerId))
					return false;

				break;

			case ALWAYS:
			default:
				break;
		}

		for (final String raw : commands) {
			String command = raw.trim();

			if (command.startsWith("/"))
				command = command.substring(1);

			command = command
					.replace("{player}", player.getName())
					.replace("{uuid}", player.getUniqueId().toString())
					.replace("{arena}", arena.getName())
					.replace("{time}", TimeFmt.format(s.finalMs));

			try {
				Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

			} catch (final Exception ex) {
				this.plugin.getLogger().warning("Reward command '" + command + "' of arena " + arena.getName() + " failed: " + ex.getMessage());
			}
		}

		return true;
	}

	private long elapsedMs(final RaceSession s) {
		return (System.nanoTime() - s.startNanos) / 1_000_000L + s.penaltyMs;
	}

	// ------------------------------------------------------------------------------------------
	// Boat exit / quit / foreign teleports
	// ------------------------------------------------------------------------------------------

	/** The racer left the boat: boat gets removed and the player is sent back to the start (fresh attempt). */
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
			if (s.disposed || this.sessions.get(s.playerId) != s)
				return;

			this.startRun(s);
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
	// Action bar ticker
	// ------------------------------------------------------------------------------------------

	private void tick() {
		if (this.sessions.isEmpty())
			return;

		for (final RaceSession s : new ArrayList<>(this.sessions.values())) {
			if (s.disposed)
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

			switch (s.state) {
				case COUNTDOWN -> {
					if (s.penaltyMs > 0L)
						Msg.actionBar(player, "actionbar.countdown-penalty", "time", TimeFmt.format(0L), "penalty", TimeFmt.delta(s.penaltyMs));
					else
						Msg.actionBar(player, "actionbar.countdown", "time", TimeFmt.format(0L));
				}
				case RUNNING -> {
					final String time = TimeFmt.format(this.elapsedMs(s));

					if (s.deltaText.isEmpty())
						Msg.actionBar(player, "actionbar.running", "time", time);
					else
						Msg.actionBar(player, "actionbar.running-delta", "time", time, "delta_text", s.deltaText);
				}
				case FINISHED -> Msg.actionBar(player, "actionbar.finished", "time", TimeFmt.format(s.finalMs), "delta_text", s.deltaText);
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
