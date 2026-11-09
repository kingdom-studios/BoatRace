package pl.kingdomcraft.boatrace.race;

import java.time.Duration;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import pl.kingdomcraft.boatrace.Settings;
import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.arena.Point;
import pl.kingdomcraft.boatrace.storage.RaceRecord;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.Msg;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * The race engine: join / leave / reset, start lights, jumpstart detection, invisible checkpoints, finish.
 *
 * Performance notes:
 *  - ONE repeating task (every 2 ticks) updates the action bar of all racers, no per-player tasks.
 *  - Checkpoints are only evaluated inside VehicleMoveEvent of race boats (O(1) map lookup for any other vehicle).
 *  - Times use System.nanoTime(). Start lamps are client-side block changes, so the world is never modified.
 */
public final class RaceManager {

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
		this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 2L, 2L);
	}

	/** Server shutdown / plugin disable: remove every boat and give everybody their inventory back. */
	public void shutdown() {
		if (this.ticker != null)
			this.ticker.cancel();

		this.leaveAll(LeaveMode.ORIGIN);
	}

	/** Used by /br reload. */
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

	/** Joins the arena (or switches to it when the player already races). */
	public void join(final Player player, final Arena arena) {
		if (this.storage.isBlacklisted(player.getUniqueId())) {
			Msg.send(player, "&cZostałeś wykluczony z wyścigów.");
			return;
		}

		if (!arena.isReady()) {
			Msg.send(player, "&cArena &f" + arena.getName() + " &cnie jest jeszcze gotowa (brak spawnu lub mety).");
			return;
		}

		final RaceSession existing = this.sessions.get(player.getUniqueId());

		if (existing != null) {
			existing.arena = arena;
			existing.penaltyMs = 0L;
			this.startRun(existing, false);

			Msg.send(player, "Przeniesiono na arenę &f" + arena.getName() + "&7.");
			return;
		}

		// A snapshot left behind by a crash must be restored BEFORE we take a new one
		PlayerSnapshot.restore(this.plugin, player);

		if (!PlayerSnapshot.save(this.plugin, player)) {
			Msg.send(player, "&cNie udało się bezpiecznie zapisać Twojego ekwipunku - dołączenie anulowane.");
			return;
		}

		final RaceSession session = new RaceSession(player.getUniqueId(), arena);

		this.sessions.put(player.getUniqueId(), session);

		player.getInventory().clear();
		player.setGameMode(GameMode.ADVENTURE);
		player.setFireTicks(0);

		this.giveHotbar(player);
		this.startRun(session, false);

		if (session.disposed)
			return;

		Msg.send(player, "Dołączyłeś do areny &f" + arena.getName() + "&7. Powodzenia!");
		player.sendMessage(this.buttons());
	}

	/** /br leave or the [HUB] button. */
	public void leave(final Player player) {
		final RaceSession session = this.sessions.get(player.getUniqueId());

		if (session == null) {
			Msg.send(player, "&cNie bierzesz udziału w wyścigu.");
			return;
		}

		this.dispose(session, LeaveMode.HUB);
	}

	/** /br reset or the [RESET] button. */
	public void reset(final Player player) {
		final RaceSession session = this.sessions.get(player.getUniqueId());

		if (session == null) {
			Msg.send(player, "&cNie bierzesz udziału w wyścigu.");
			return;
		}

		final long now = System.currentTimeMillis();

		if (now - session.lastResetMs < 500L)
			return;

		session.lastResetMs = now;

		// A jumpstart penalty must not be washed away by resetting before the lights went out
		this.startRun(session, session.state == RaceSession.State.COUNTDOWN);
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

	private void startRun(final RaceSession s, final boolean carryPenalty) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || !player.isOnline()) {
			this.dispose(s, LeaveMode.SILENT);
			return;
		}

		final Location spawn = s.arena.getSpawn() == null ? null : s.arena.getSpawn().toLocation();

		if (spawn == null) {
			Msg.send(player, "&cŚwiat areny nie jest załadowany.");
			this.dispose(s, LeaveMode.HUB);
			return;
		}

		// 1) kill the old boat right away (no entity spam), cancel old timers, restore fake lamps
		this.cleanupRun(s);

		// 2) reset the run state
		final long keptPenalty = carryPenalty ? s.penaltyMs : 0L;

		s.state = RaceSession.State.COUNTDOWN;
		s.startNanos = 0L;
		s.penaltyMs = keptPenalty;
		s.falstarted = false;
		s.nextCheckpoint = 0;
		s.splits = new long[s.arena.getCheckpoints().size()];
		s.leftFinishZone = false;
		s.finalMs = 0L;
		s.deltaText = "";
		s.deltaUntilMs = 0L;
		s.restartQueued = false;

		final RaceRecord best = this.storage.getRecord(s.arena.getName(), s.playerId);

		s.bestSplits = best == null ? null : best.getSplits();
		s.bestTotalMs = best == null ? -1L : best.getBestMs();

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
			Msg.send(player, "&cNie można utworzyć łódki - sprawdź 'boat-type' w config.yml.");
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
		s.lampLocations.addAll(this.lampLocations(s.arena));

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null)
			this.showLamps(player, s, 0);

		long delay = this.settings.firstDelayTicks;

		for (int lit = 1; lit <= this.settings.lamps; lit++) {
			final int litLamps = lit;

			this.later(s, delay, () -> this.lampOn(s, litLamps));
			delay += this.settings.intervalTicks;
		}

		final int hold = ThreadLocalRandom.current().nextInt(this.settings.holdMinTicks, this.settings.holdMaxTicks + 1);

		this.later(s, delay + hold, () -> this.lightsOut(s));
	}

	private void lampOn(final RaceSession s, final int lit) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || s.disposed || s.state != RaceSession.State.COUNTDOWN)
			return;

		this.showLamps(player, s, lit);

		final float pitch = 0.6F + 0.8F * ((float) lit / (float) this.settings.lamps);

		player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, pitch);
	}

	private void lightsOut(final RaceSession s) {
		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null || s.disposed || s.state != RaceSession.State.COUNTDOWN)
			return;

		this.showLamps(player, s, 0); // all dark = GO

		s.state = RaceSession.State.RUNNING;
		s.startNanos = System.nanoTime();
		s.restartFailures = 0;

		player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.5F);
		player.showTitle(Title.title(Msg.c("&a&lSTART!"), Component.empty(),
				Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(250))));

		// the dark lamps stay visible for a moment, then the real blocks come back
		this.later(s, this.settings.lingerTicks, () -> this.revertLamps(s));
	}

	private List<Location> lampLocations(final Arena arena) {
		final List<Location> result = new ArrayList<>();
		final Point startBlock = arena.getStartBlock();

		if (startBlock == null)
			return result;

		final Location base = startBlock.toLocation();

		if (base == null)
			return result;

		final Location center = base.getBlock().getLocation().add(0, this.settings.lampHeight, 0);
		final int half = (this.settings.lamps - 1) / 2;

		for (int i = 0; i < this.settings.lamps; i++) {
			final int offset = i - half;

			result.add(center.clone().add(arena.isLightsAlongX() ? offset : 0, 0, arena.isLightsAlongX() ? 0 : offset));
		}

		return result;
	}

	/** Shows the first {@code lit} lamps red and the rest dark - only to this player. */
	private void showLamps(final Player player, final RaceSession s, final int lit) {
		for (int i = 0; i < s.lampLocations.size(); i++)
			player.sendBlockChange(s.lampLocations.get(i), i < lit ? this.settings.lampOn : this.settings.lampOff);
	}

	private void revertLamps(final RaceSession s) {
		if (s.lampLocations.isEmpty())
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null)
			for (final Location location : s.lampLocations) {
				final BlockData real = location.getBlock().getBlockData();

				player.sendBlockChange(location, real);
			}

		s.lampLocations.clear();
	}

	private void later(final RaceSession s, final long delayTicks, final Runnable runnable) {
		s.tasks.add(Bukkit.getScheduler().runTaskLater(this.plugin, runnable, delayTicks));
	}

	// ------------------------------------------------------------------------------------------
	// Movement: jumpstart, checkpoints, finish
	// ------------------------------------------------------------------------------------------

	public void handleVehicleMove(final Vehicle vehicle, final Location from, final Location to) {
		final RaceSession s = this.boats.get(vehicle.getUniqueId());

		if (s == null || s.disposed)
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player == null)
			return;

		switch (s.state) {
			case COUNTDOWN -> this.handleCountdownMove(s, player, to);
			case RUNNING -> this.handleRunningMove(s, player, from, to);
			default -> {
			}
		}
	}

	private void handleCountdownMove(final RaceSession s, final Player player, final Location to) {
		if (s.holdPos == null || to.getWorld() != s.holdPos.getWorld())
			return;

		final double dx = to.getX() - s.holdPos.getX();
		final double dz = to.getZ() - s.holdPos.getZ();
		final double distanceSquared = dx * dx + dz * dz;

		if (distanceSquared > 0.25D) // moved more than half a block
			this.jumpstart(s, player, "ruszyłeś przed zgaszeniem świateł");

		// keep the boat on the grid
		if (distanceSquared > 1.0D && s.boat != null) {
			s.internalTeleport = true;

			try {
				s.boat.teleport(s.holdPos, TeleportFlag.EntityState.RETAIN_PASSENGERS);
			} finally {
				s.internalTeleport = false;
			}
		}
	}

	private void jumpstart(final RaceSession s, final Player player, final String reason) {
		if (s.state != RaceSession.State.COUNTDOWN || s.falstarted)
			return;

		s.falstarted = true;
		s.penaltyMs += this.settings.penaltyMs;

		Msg.send(player, "&c&lFALSTART! &7(" + reason + ") &c+" + TimeFmt.seconds2(this.settings.penaltyMs) + " kary");
		player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
	}

	private void handleRunningMove(final RaceSession s, final Player player, final Location from, final Location to) {
		final List<Point> checkpoints = s.arena.getCheckpoints();

		// The run only counts the checkpoints that existed when it started (an admin may add more while it is running)
		final int total = Math.min(checkpoints.size(), s.splits.length);

		// 1) next checkpoint in order (invisible - nothing is ever displayed in the world)
		if (s.nextCheckpoint < total) {
			final Location center = checkpoints.get(s.nextCheckpoint).toLocation();

			if (center != null && passesThrough(from, to, center, this.settings.checkpointRadius))
				this.checkpointReached(s, player, s.nextCheckpoint);
		}

		// 2) finish
		final Point finishPoint = s.arena.getFinish();
		final Location finish = finishPoint == null ? null : finishPoint.toLocation();

		if (finish == null)
			return;

		final boolean inFinish = passesThrough(from, to, finish, this.settings.finishRadius);

		if (!inFinish) {
			s.leftFinishZone = true; // start and finish may be close together on circuits
			return;
		}

		if (!s.leftFinishZone)
			return;

		if (s.nextCheckpoint < total) {
			Msg.send(player, "&cPominąłeś checkpoint &f" + (s.nextCheckpoint + 1) + "&7/&f" + total + "&c - bieg nie został zaliczony.");
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 0.8F);

			this.queueRestart(s);
			return;
		}

		this.finish(s, player);
	}

	private void checkpointReached(final RaceSession s, final Player player, final int index) {
		final long elapsed = this.elapsedMs(s);
		final int total = s.splits.length;

		s.splits[index] = elapsed;
		s.nextCheckpoint = index + 1;

		final String label = "&7Checkpoint &f" + (index + 1) + "&7/&f" + total + " &8» ";

		if (s.bestSplits != null && index < s.bestSplits.length && s.bestSplits[index] > 0L) {
			final long delta = elapsed - s.bestSplits[index];
			final String colored = (delta <= 0L ? "&a" : "&c") + TimeFmt.signedDelta(delta);

			Msg.raw(player, label + colored);

			s.deltaText = colored;
			s.deltaUntilMs = System.currentTimeMillis() + 2500L;

		} else
			Msg.raw(player, label + "&f" + TimeFmt.format(elapsed));

		player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8F, 1.4F);
	}

	private void finish(final RaceSession s, final Player player) {
		s.state = RaceSession.State.FINISHED;
		s.finalMs = this.elapsedMs(s);

		final long previousBest = s.bestTotalMs;
		final boolean blacklisted = this.storage.isBlacklisted(s.playerId);
		final boolean newBest = this.storage.submitIfBetter(s.arena.getName(), s.playerId, player.getName(), s.finalMs, s.splits.clone());

		Msg.raw(player, "&8&m                                        ");

		String line = "&a&lMETA! &7Czas: &f" + TimeFmt.format(s.finalMs);

		if (s.penaltyMs > 0L)
			line += " &c(w tym +" + TimeFmt.seconds2(s.penaltyMs) + " kary)";

		Msg.raw(player, line);

		if (blacklisted)
			Msg.raw(player, "&cJesteś wykluczony - wynik nie został zapisany.");

		else if (newBest) {
			String record = "&6&lNOWY REKORD OSOBISTY!";

			if (previousBest > 0L)
				record += " &a" + TimeFmt.signedDelta(s.finalMs - previousBest);

			Msg.raw(player, record);

			final int rank = this.storage.getRank(s.arena.getName(), s.playerId);

			if (rank > 0)
				Msg.raw(player, "&7Pozycja w rankingu: &e#" + rank);

		} else
			Msg.raw(player, "&7Twój rekord: &f" + TimeFmt.format(previousBest) + " &c" + TimeFmt.signedDelta(s.finalMs - previousBest));

		Msg.raw(player, "&8&m                                        ");
		player.sendMessage(this.buttons());

		player.showTitle(Title.title(Msg.c("&a&lMETA"), Msg.c("&f" + TimeFmt.format(s.finalMs)),
				Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
		player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
	}

	private long elapsedMs(final RaceSession s) {
		return (System.nanoTime() - s.startNanos) / 1_000_000L + s.penaltyMs;
	}

	/** True when the segment from -> to comes within {@code radius} of {@code center} (so fast boats cannot skip a zone). */
	private static boolean passesThrough(final Location from, final Location to, final Location center, final double radius) {
		if (to.getWorld() != center.getWorld())
			return false;

		final double ax = from.getX(), ay = from.getY(), az = from.getZ();
		final double dx = to.getX() - ax, dy = to.getY() - ay, dz = to.getZ() - az;
		final double lengthSquared = dx * dx + dy * dy + dz * dz;

		double t = 0.0D;

		if (lengthSquared > 1.0E-9D) {
			t = ((center.getX() - ax) * dx + (center.getY() - ay) * dy + (center.getZ() - az) * dz) / lengthSquared;
			t = Math.max(0.0D, Math.min(1.0D, t));
		}

		final double px = ax + dx * t - center.getX();
		final double py = ay + dy * t - center.getY();
		final double pz = az + dz * t - center.getZ();

		return px * px + py * py + pz * pz <= radius * radius;
	}

	// ------------------------------------------------------------------------------------------
	// Boat exit / quit / foreign teleports
	// ------------------------------------------------------------------------------------------

	/** The racer left the boat: boat gets removed and the player is sent back to the start (restart). */
	public void handleVehicleExit(final Vehicle vehicle, final Entity exited) {
		final RaceSession s = this.boats.get(vehicle.getUniqueId());

		if (s == null || s.disposed || !s.playerId.equals(exited.getUniqueId()))
			return;

		final Player player = Bukkit.getPlayer(s.playerId);

		if (player != null && s.state == RaceSession.State.COUNTDOWN)
			this.jumpstart(s, player, "opuściłeś łódkę przed startem");

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

			this.startRun(s, s.state == RaceSession.State.COUNTDOWN);
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

		final long now = System.currentTimeMillis();

		for (final RaceSession s : new ArrayList<>(this.sessions.values())) {
			if (s.disposed)
				continue;

			final Player player = Bukkit.getPlayer(s.playerId);

			if (player == null)
				continue;

			// Safety net: boat gone or the player is somehow no longer in it
			if (!s.restartQueued && s.boat != null) {
				final Entity vehicle = player.getVehicle();
				final boolean seated = vehicle != null && vehicle.getUniqueId().equals(s.boat.getUniqueId());

				if (!s.boat.isValid() || !seated) {
					if (++s.restartFailures > 5) {
						Msg.send(player, "&cNie udało się posadzić Cię w łódce - opuszczasz wyścig.");
						this.dispose(s, LeaveMode.HUB);
						continue;
					}

					this.queueRestart(s);
					continue;
				}
			}

			switch (s.state) {
				case COUNTDOWN -> {
					String text = "&7Czekaj na zgaszenie świateł...";

					if (s.penaltyMs > 0L)
						text += " &c(+" + TimeFmt.seconds2(s.penaltyMs) + ")";

					Msg.actionBar(player, text);
				}
				case RUNNING -> {
					final String time = "&eCzas: &f" + TimeFmt.format(this.elapsedMs(s));

					if (now < s.deltaUntilMs)
						Msg.actionBar(player, s.deltaText + "  " + time);

					else if (s.penaltyMs > 0L)
						Msg.actionBar(player, time + " &c(+" + TimeFmt.seconds2(s.penaltyMs) + ")");

					else
						Msg.actionBar(player, time);
				}
				case FINISHED -> Msg.actionBar(player, "&aMeta! &fCzas: " + TimeFmt.format(s.finalMs));
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Hotbar buttons
	// ------------------------------------------------------------------------------------------

	private void giveHotbar(final Player player) {
		if (!this.settings.hotbarEnabled)
			return;

		player.getInventory().setItem(this.settings.resetSlot, this.button(Material.CLOCK, "&e&lRESET", "reset"));
		player.getInventory().setItem(this.settings.hubSlot, this.button(Material.RED_BED, "&c&lHUB &7(powrót na spawn)", "hub"));
	}

	private ItemStack button(final Material material, final String name, final String action) {
		final ItemStack item = new ItemStack(material);
		final ItemMeta meta = item.getItemMeta();

		meta.displayName(Msg.c(name).decoration(TextDecoration.ITALIC, false));
		meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.STRING, action);
		item.setItemMeta(meta);

		return item;
	}

	private String itemAction(final ItemStack item) {
		if (item == null || !item.hasItemMeta())
			return null;

		return item.getItemMeta().getPersistentDataContainer().get(this.itemKey, PersistentDataType.STRING);
	}

	/** Clickable [RESET] [HUB] chat buttons (work even with hotbar items disabled). */
	private Component buttons() {
		return Component.text("[RESET]", NamedTextColor.YELLOW, TextDecoration.BOLD)
				.clickEvent(ClickEvent.runCommand("/br reset"))
				.hoverEvent(HoverEvent.showText(Component.text("Jedź jeszcze raz")))
				.append(Component.text("   "))
				.append(Component.text("[HUB]", NamedTextColor.RED, TextDecoration.BOLD)
						.clickEvent(ClickEvent.runCommand("/br leave"))
						.hoverEvent(HoverEvent.showText(Component.text("Powrót na spawn"))));
	}
}
