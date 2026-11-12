package pl.kingdomcraft.boatrace.race;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
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
import pl.kingdomcraft.boatrace.captainhook.CaptainHookHook;
import pl.kingdomcraft.boatrace.luckperms.LuckPermsHook;
import pl.kingdomcraft.boatrace.party.PartyManager;
import pl.kingdomcraft.boatrace.team.TeamColor;
import pl.kingdomcraft.boatrace.team.Lineups;
import pl.kingdomcraft.boatrace.team.LobbyFill;
import pl.kingdomcraft.boatrace.team.TeamScoring;
import pl.kingdomcraft.boatrace.ranking.Elo;
import pl.kingdomcraft.boatrace.storage.PlayerRating;
import pl.kingdomcraft.boatrace.spectate.SpectateManager;
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

	private static final String ADMIN_PERMISSION = "boatrace.admin";
	/** spared from the competitive leaver penalty and cooldown (testing) */
	public static final String COMP_BYPASS_PERMISSION = "boatrace.competitive.bypass";
	/** longest distance a boat can sail between two move events (blue ice is about 4 blocks per tick) */
	private static final double MAX_MOVE_BLOCKS = 10.0D;

	/** Outcome of {@link #join(Player, Arena)}; the racer has already been told about failures. */
	public enum JoinResult {
		JOINED,
		BANNED,
		NOT_READY,
		/** the arena is closed for players (/bre maintenance) */
		MAINTENANCE,
		/** the arena, all its copies (and - if enabled - every other arena) are occupied and queueing is off */
		BUSY,
		/** the arena is occupied: the player / party waits in the queue of the arena */
		QUEUED,
		/** a party member is racing / banned, the grid is too small, or a non-leader tried to pick a map */
		PARTY_BLOCKED,
		FAILED
	}

	/** What the map menu shows for an arena. */
	public enum ArenaStatus {
		/** an instance of the arena is free */
		FREE,
		/** every instance is occupied */
		BUSY,
		/** an event lobby with free grid positions is open */
		EVENT,
		/** closed for players (/bre maintenance) */
		MAINTENANCE,
		NOT_READY
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
	private final PartyManager parties;
	private final NamespacedKey itemKey;

	/** read from other threads too (PlaceholderAPI), mutated on the main thread only */
	private final Map<UUID, RaceSession> sessions = new ConcurrentHashMap<>();
	private final Map<UUID, RaceSession> boats = new HashMap<>();
	/** occupied arena instances: "arena@world" -> heat */
	private final Map<String, Heat> heats = new LinkedHashMap<>();

	/** waiting players / parties per arena ("arena name in lower case" -> FIFO list) */
	private final Map<String, List<QueueEntry>> queues = new LinkedHashMap<>();

	/** What counts as solid ground under the boat. A field only so the tests can run without the server's block registry. */
	Predicate<Material> solidCheck = Material::isSolid;

	private BukkitTask ticker;
	private long tickCounter;
	private long queueTicker;
	private boolean processingQueues;
	private boolean shuttingDown;
	/** /bre reload or a server stop sends everybody away: nobody loses ranking points for that */
	private boolean massLeave;
	private SpectateManager spectate;
	private CaptainHookHook captainHook;
	private LuckPermsHook luckPerms;

	/** competitive lobbies: teams waiting, first come first served (lobby = arena, or "*" for any arena) */
	private final List<CompEntry> compQueue = new ArrayList<>();
	/** lobby key -> epoch ms when its countdown ends */
	private final Map<String, Long> compCountdowns = new HashMap<>();
	/** lobbies told that every fitting track is busy (once) */
	private final Set<String> compWaitingForTrack = new java.util.HashSet<>();
	/** player -> epoch ms until which they may not queue for competitive (left a running match) */
	private final Map<UUID, Long> leaverCooldowns = new HashMap<>();
	/** same lineup bonus, in memory */
	private final Lineups lineups = new Lineups();
	private boolean processingComp;

	/** A queued player or party: the owner is the player who picked the map (the party leader). */
	private static final class QueueEntry {

		final UUID owner;
		Integer laps;

		QueueEntry(final UUID owner, final Integer laps) {
			this.owner = owner;
			this.laps = laps;
		}
	}

	public RaceManager(final JavaPlugin plugin, final Settings settings, final ArenaManager arenas, final RecordStorage storage, final PartyManager parties) {
		this.plugin = plugin;
		this.settings = settings;
		this.arenas = arenas;
		this.storage = storage;
		this.parties = parties;
		this.itemKey = new NamespacedKey(plugin, "race_item");
	}

	/** Team tag as LuckPerms meta (optional). */
	public void setLuckPerms(final LuckPermsHook luckPerms) {
		this.luckPerms = luckPerms;
	}

	/** Race stats and season pass XP for CaptainHook (optional). */
	public void setCaptainHook(final CaptainHookHook captainHook) {
		this.captainHook = captainHook;
	}

	public void setSpectate(final SpectateManager spectate) {
		this.spectate = spectate;
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	public void start() {
		this.ticker = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1L, 1L);
	}

	/** Server shutdown / plugin disable: remove every boat, restore world blocks, give everybody their inventory back. */
	public void shutdown() {
		this.shuttingDown = true;

		if (this.ticker != null)
			this.ticker.cancel();

		this.queues.clear();
		this.leaveAll(LeaveMode.ORIGIN);
	}

	/** Used by /bre reload. */
	public void leaveAllToHub() {
		this.leaveAll(LeaveMode.HUB);
	}

	private void leaveAll(final LeaveMode mode) {
		this.massLeave = true;

		try {
			for (final RaceSession session : new ArrayList<>(this.sessions.values()))
				this.dispose(session, mode);

		} finally {
			this.massLeave = false;
		}

		// empty event lobbies (and anything left over) - restores WORLD start signals
		for (final Heat heat : new ArrayList<>(this.heats.values()))
			this.cleanupHeat(heat);

		this.heats.clear();
	}

	// ------------------------------------------------------------------------------------------
	// Public API (commands / listener / placeholders)
	// ------------------------------------------------------------------------------------------

	/**
	 * The tab list group of a player: the instance ("arena@world") of the race they are in - solo, event, party or
	 * competitive, also while waiting on the grid or after the finish - or null when they are not in a race (lobby).
	 */
	public String tabGroupOf(final UUID uuid) {
		final RaceSession session = this.sessions.get(uuid);
		final Heat heat = session != null && !session.disposed ? session.heat : null;

		return heat != null ? heat.key : null;
	}

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
		return this.pickRandomReadyArena(arena -> true);
	}

	/** Same, but only among the open arenas the filter accepts (arenas in maintenance are never picked). */
	public Arena pickRandomReadyArena(final Predicate<Arena> filter) {
		final List<Arena> ready = new ArrayList<>();
		final List<Arena> free = new ArrayList<>();

		for (final Arena arena : this.arenas.readyArenas())
			if (!arena.isMaintenance() && filter.test(arena)) {
				ready.add(arena);

				if (this.freeInstanceWorld(arena, null) != null)
					free.add(arena);
			}

		final List<Arena> pool = free.isEmpty() ? ready : free;

		return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}

	/**
	 * A random arena for this player: open, accepted by the filter and - when a party leader joins - with at least one
	 * grid position for every party member (smaller arenas are excluded completely).
	 */
	public Arena pickRandomReadyArena(final Player player, final Predicate<Arena> filter) {
		final int size = this.groupSize(player);

		return this.pickRandomReadyArena(arena -> arena.getSpawns().size() >= size && filter.test(arena));
	}

	/** How many players start when this player joins a map: the whole party for its leader, otherwise 1. */
	public int groupSize(final Player player) {
		return this.parties.groupOf(player).size();
	}

	public JoinResult join(final Player player, final Arena arena) {
		return this.join(player, arena, null);
	}

	/**
	 * Joins the arena (or switches to it when the player already races). Silent on success: the player simply
	 * appears in a boat on the grid.
	 *
	 * A party leader takes the whole party along (see {@link #joinGroup}); a party member cannot pick a map.
	 *
	 * @param laps laps of the new race, null = the arena's setting (ignored when joining an open event)
	 */
	public JoinResult join(final Player player, final Arena arena, final Integer laps) {
		if (this.parties.isFollower(player)) {
			Msg.send(player, "party.only-leader-join");
			return JoinResult.PARTY_BLOCKED;
		}

		final List<Player> group = this.parties.groupOf(player);

		// a normal race replaces a place in a competitive lobby
		for (final Player member : group)
			this.removeFromCompQueue(member.getUniqueId(), true);

		return this.joinGroup(group, arena, laps, true, true);
	}

	/**
	 * Starts a race for one player or a whole party (the leader is first in the list).
	 *
	 * Order: an open event lobby of the arena with enough free grid positions -> a free instance of the arena (its own
	 * world first, then the fallback worlds) -> a random other free arena (fallback.random-free-arena) -> the queue of
	 * the arena. A party always starts together as a private event on the grid (positions 1, 2, 3 ...), a single
	 * player as a solo run on position #1.
	 *
	 * @param allowOtherArena may the players be sent to another free arena
	 * @param allowQueue      wait in the queue instead of failing when everything is busy
	 */
	private JoinResult joinGroup(final List<Player> players, final Arena arena, final Integer laps, final boolean allowOtherArena, final boolean allowQueue) {
		final Player leader = players.get(0);
		final boolean group = players.size() > 1;

		for (final Player member : players)
			if (this.storage.isBlacklisted(member.getUniqueId())) {
				if (group)
					Msg.send(leader, "party.member-banned", "player", member.getName());
				else
					Msg.send(leader, "race.banned");

				return JoinResult.BANNED;
			}

		if (!arena.isReady()) {
			Msg.send(leader, "race.not-ready", "arena", arena.getName(), "missing", arena.missing());
			return JoinResult.NOT_READY;
		}

		// closed by an admin: only admins may still go there (testing)
		if (arena.isMaintenance() && !leader.hasPermission(ADMIN_PERMISSION)) {
			Msg.send(leader, "race.maintenance", "arena", arena.getName());
			return JoinResult.MAINTENANCE;
		}

		if (group) {
			// the leader may switch maps, the others must not be in a race
			for (final Player member : players.subList(1, players.size()))
				if (this.sessions.containsKey(member.getUniqueId())) {
					Msg.send(leader, "party.member-busy", "player", member.getName());
					return JoinResult.PARTY_BLOCKED;
				}

			if (arena.getSpawns().size() < players.size()) {
				Msg.send(leader, "party.not-enough-slots", "arena", arena.getName(), "slots", arena.getSpawns().size(), "size", players.size());
				return JoinResult.PARTY_BLOCKED;
			}
		}

		final RaceSession existing = this.sessions.get(leader.getUniqueId());

		// 1) an open event lobby with room for everybody
		final Heat lobby = this.findEvent(arena);

		if (lobby != null && lobby.phase == Heat.Phase.LOBBY) {
			final List<Player> toAttach = new ArrayList<>();

			for (final Player player : players) {
				final RaceSession current = this.sessions.get(player.getUniqueId());

				if (current == null || current.heat != lobby)
					toAttach.add(player);
			}

			if (toAttach.isEmpty())
				return JoinResult.JOINED;

			if (lobby.freeSlots() >= toAttach.size())
				return this.attachGroup(toAttach, lobby, false, false);
		}

		// 2) a free instance of this arena, 3) another free arena the group fits on
		Arena target = arena;
		String world = this.freeInstanceWorld(arena, existing);

		if (world == null && allowOtherArena && this.settings.fallbackEnabled && this.settings.fallbackRandomArena) {
			final List<Arena> candidates = new ArrayList<>();

			for (final Arena other : this.arenas.readyArenas())
				if (other != arena && !other.isMaintenance() && other.getSpawns().size() >= players.size() && this.freeInstanceWorld(other, existing) != null)
					candidates.add(other);

			if (!candidates.isEmpty()) {
				target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
				world = this.freeInstanceWorld(target, existing);
			}
		}

		// 4) everything is busy: wait in line
		if (world == null) {
			if (allowQueue && this.settings.queueEnabled)
				return this.enqueue(players, arena, laps);

			Msg.send(leader, "race.all-busy", "arena", arena.getName());
			return JoinResult.BUSY;
		}

		final Arena instance = world.equals(target.getWorldName()) ? target : target.inWorld(world);
		final Heat heat = new Heat(instanceKey(target, world), instance, group);

		heat.privateHeat = group;

		if (laps != null)
			heat.laps = Math.max(1, laps);

		final JoinResult result;

		if (group) {
			result = this.attachGroup(players, heat, true, true);

			// nobody could be seated (e.g. no inventory snapshot): do not keep the instance occupied
			if (heat.slots.isEmpty() && this.heats.get(heat.key) == heat)
				this.releaseHeat(heat);

		} else {
			final RaceSession session = existing != null ? existing : this.createSession(leader);

			if (session == null)
				return JoinResult.FAILED;

			this.dequeue(leader.getUniqueId());
			this.detach(session);
			this.heats.put(heat.key, heat);

			session.heat = heat;
			session.slot = instance.getPrimarySpawnIndex();
			heat.slots.put(session.slot, session);

			this.startSoloRun(session);

			result = session.disposed ? JoinResult.FAILED : JoinResult.JOINED;
		}

		if (result == JoinResult.JOINED && !group && (target != arena || !world.equals(arena.getWorldName())))
			Msg.send(leader, "race.redirected", "arena", target.getName(), "world", world, "requested", arena.getName());

		return result;
	}

	/**
	 * Seats several players on the grid of an event heat: sessions (inventory snapshots) first - if one of them cannot
	 * be created nothing changes - then each takes the next free grid position.
	 *
	 * @param startNow     start the lights right away (private party heats); an open event waits for /bre start
	 * @param registerHeat the heat is new and occupies its instance from now on
	 */
	private JoinResult attachGroup(final List<Player> players, final Heat heat, final boolean startNow, final boolean registerHeat) {
		final List<RaceSession> toAttach = new ArrayList<>();
		final List<RaceSession> created = new ArrayList<>();

		for (final Player player : players) {
			RaceSession session = this.sessions.get(player.getUniqueId());

			if (session == null) {
				session = this.createSession(player);

				if (session == null) {
					for (final RaceSession fresh : created)
						this.dispose(fresh, LeaveMode.SILENT);

					return JoinResult.FAILED;
				}

				created.add(session);
			}

			toAttach.add(session);
		}

		// leave the old heats first - a new heat may have the same key as the leader's old solo heat
		for (final RaceSession session : toAttach) {
			this.dequeue(session.playerId);
			this.detach(session);
		}

		if (registerHeat)
			this.heats.put(heat.key, heat);

		for (final RaceSession session : toAttach)
			if (!session.disposed)
				this.attachToEvent(session, heat);

		if (startNow && !heat.slots.isEmpty())
			this.startEventCountdown(heat);

		return toAttach.get(0).disposed ? JoinResult.FAILED : JoinResult.JOINED;
	}

	/** /bre leave or the HUB item. Silent: no chat output at all (except when leaving a queue). */
	public void leave(final Player player) {
		final RaceSession session = this.sessions.get(player.getUniqueId());

		if (session != null)
			this.dispose(session, LeaveMode.HUB);

		else if (this.dequeue(player.getUniqueId()))
			Msg.send(player, "queue.left");

		else if (this.removeFromCompQueue(player.getUniqueId(), true))
			Msg.send(player, "comp.queue-left");
	}

	// ------------------------------------------------------------------------------------------
	// Queue
	// ------------------------------------------------------------------------------------------

	private static String arenaKey(final Arena arena) {
		return arena.getName().toLowerCase(Locale.ROOT);
	}

	/** Puts the player / party in the queue of the busy arena. Tells everybody in the group the position. */
	private JoinResult enqueue(final List<Player> players, final Arena arena, final Integer laps) {
		final Player leader = players.get(0);
		final List<QueueEntry> existing = this.queues.get(arenaKey(arena));

		if (existing != null)
			for (int i = 0; i < existing.size(); i++)
				if (existing.get(i).owner.equals(leader.getUniqueId())) {
					existing.get(i).laps = laps;
					Msg.send(leader, "queue.already", "arena", arena.getName(), "position", i + 1);
					return JoinResult.QUEUED;
				}

		// a player waits for one map at a time
		this.dequeue(leader.getUniqueId());

		final List<QueueEntry> list = this.queues.computeIfAbsent(arenaKey(arena), k -> new ArrayList<>());

		list.add(new QueueEntry(leader.getUniqueId(), laps));

		for (final Player player : players)
			Msg.send(player, "queue.joined", "arena", arena.getName(), "position", list.size());

		return JoinResult.QUEUED;
	}

	/** Removes the queue entry owned by the player. @return true when there was one */
	private boolean dequeue(final UUID owner) {
		boolean removed = false;

		for (final Iterator<List<QueueEntry>> it = this.queues.values().iterator(); it.hasNext();) {
			final List<QueueEntry> list = it.next();

			removed |= list.removeIf(entry -> entry.owner.equals(owner));

			if (list.isEmpty())
				it.remove();
		}

		return removed;
	}

	/** An arena instance became free: let the queues move on in the next tick (never inside the event that freed it). */
	private void scheduleQueueProcessing() {
		if (this.queues.isEmpty() || this.shuttingDown || !this.plugin.isEnabled())
			return;

		Bukkit.getScheduler().runTask(this.plugin, this::processQueues);
	}

	/**
	 * First come, first served per arena: the first entry starts as soon as a free instance of ITS arena exists
	 * (its own world or a fallback copy). Entries whose owner left are dropped, a party is re-read from the party
	 * system (members may have changed since it queued).
	 */
	private void processQueues() {
		if (this.processingQueues || this.queues.isEmpty())
			return;

		this.processingQueues = true;

		try {
			for (final String key : new ArrayList<>(this.queues.keySet())) {
				final Arena arena = this.arenas.get(key);

				for (final QueueEntry entry : new ArrayList<>(this.queues.getOrDefault(key, List.of()))) {
					final Player owner = Bukkit.getPlayer(entry.owner);

					if (owner == null || !owner.isOnline()) {
						this.dequeue(entry.owner);
						continue;
					}

					if (arena == null || !arena.isReady() || arena.isMaintenance()) {
						this.dequeue(entry.owner);
						Msg.send(owner, "queue.cancelled", "arena", arena == null ? key : arena.getName());
						continue;
					}

					// joined somebody else's party meanwhile - only a leader can pick a map
					if (this.parties.isFollower(owner)) {
						this.dequeue(entry.owner);
						continue;
					}

					// still racing somewhere else: keep the place, never pull a racer out of a running race
					if (this.sessions.containsKey(entry.owner))
						continue;

					// the first one in line has to wait for a free instance - nobody may jump the queue
					if (this.freeInstanceWorld(arena, null) == null)
						break;

					this.dequeue(entry.owner);
					this.joinGroup(this.parties.groupOf(owner), arena, entry.laps, false, false);
				}
			}

		} finally {
			this.processingQueues = false;
		}
	}

	// ------------------------------------------------------------------------------------------
	// Status (map menu, placeholders, spectate)
	// ------------------------------------------------------------------------------------------

	/** Players in a race (finished racers count until they are sent to the hub). Safe to call from any thread. */
	public int playingCount() {
		int count = 0;

		for (final RaceSession session : this.sessions.values())
			if (session.heat != null && !session.disposed)
				count++;

		return count;
	}

	/** Players racing on the arena (all its instances / fallback worlds). Safe to call from any thread. */
	public int playingCount(final String arenaName) {
		int count = 0;

		for (final RaceSession session : this.sessions.values()) {
			final Heat heat = session.heat;

			if (heat != null && !session.disposed && heat.arena.getName().equalsIgnoreCase(arenaName))
				count++;
		}

		return count;
	}

	/** Grid positions of all instances the arena can run on at the same time. */
	public int capacityOf(final Arena arena) {
		return arena.getSpawns().size() * Math.max(1, this.instanceWorlds(arena).size());
	}

	public int queueSize(final Arena arena) {
		final List<QueueEntry> list = this.queues.get(arenaKey(arena));

		return list == null ? 0 : list.size();
	}

	public ArenaStatus statusOf(final Arena arena) {
		if (!arena.isReady())
			return ArenaStatus.NOT_READY;

		if (arena.isMaintenance())
			return ArenaStatus.MAINTENANCE;

		final Heat lobby = this.findEvent(arena);

		if (lobby != null && lobby.phase == Heat.Phase.LOBBY && lobby.freeSlots() > 0)
			return ArenaStatus.EVENT;

		return this.freeInstanceWorld(arena, null) != null ? ArenaStatus.FREE : ArenaStatus.BUSY;
	}

	/** A racer of the arena to spectate: one whose clock is running, otherwise any. Null when nobody races there. */
	public Player racerOnArena(final Arena arena) {
		Player fallback = null;

		for (final RaceSession session : this.sessions.values()) {
			final Heat heat = session.heat;

			if (heat == null || session.disposed || !heat.arena.getName().equalsIgnoreCase(arena.getName()))
				continue;

			final Player player = Bukkit.getPlayer(session.playerId);

			if (player == null)
				continue;

			if (session.state == RaceSession.State.RUNNING)
				return player;

			fallback = player;
		}

		return fallback;
	}

	/** Names of the online racers (tab completion). */
	public List<String> racerNames() {
		final List<String> names = new ArrayList<>();

		for (final UUID uuid : this.sessions.keySet()) {
			final Player player = Bukkit.getPlayer(uuid);

			if (player != null)
				names.add(player.getName());
		}

		return names;
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

		if (this.heats.get(heat.key) == heat)
			this.releaseHeat(heat);

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
			if (heat.event && !heat.privateHeat && heat.arena.getName().equalsIgnoreCase(arena.getName()))
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
		// an admin who is spectating must get the real game mode back BEFORE the inventory snapshot is taken
		if (this.spectate != null)
			this.spectate.stop(player, true);

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
		if (heat.slots.isEmpty() && !(heat.event && heat.phase == Heat.Phase.LOBBY) && this.heats.get(heat.key) == heat)
			this.releaseHeat(heat);

		// a party member who left must not keep the rest of the party waiting at the finish
		else if (heat.privateHeat)
			this.checkPartyComplete(heat);
	}

	/** Frees the arena instance of the heat; waiting players / parties may take it. */
	private void releaseHeat(final Heat heat) {
		this.cleanupHeat(heat);
		this.heats.remove(heat.key);

		this.scheduleQueueProcessing();

		if (!this.compQueue.isEmpty() && !this.shuttingDown && this.plugin.isEnabled())
			Bukkit.getScheduler().runTask(this.plugin, this::processCompetitive);
	}

	private void dispose(final RaceSession s, final LeaveMode mode) {
		if (s.disposed)
			return;

		s.disposed = true;
		this.sessions.remove(s.playerId);

		// Leaving a ranked race before the finish = behind everybody still racing (and a broken "not last" streak)
		final Heat heat = s.heat;
		final boolean teamRace = heat != null && heat.competitive;
		// competitive: leaving during the start lights abandons the team as well
		final boolean dnf = heat != null && !this.massLeave
				&& (s.state == RaceSession.State.RUNNING && heat.ranked && !heat.rankingSettled
						|| teamRace && !heat.teamSettled && (s.state == RaceSession.State.RUNNING || s.state == RaceSession.State.COUNTDOWN));

		if (dnf) {
			final Player leaving = Bukkit.getPlayer(s.playerId);
			final String name = leaving != null ? leaving.getName() : this.storage.getRating(s.playerId, "?").name();

			heat.dnf.add(new Heat.Result(s.playerId, name, 0L, 0L));

			if (!this.storage.isBlacklisted(s.playerId) && (heat.ranked || teamRace && heat.fieldSize >= this.settings.rankingMinRacers))
				this.storage.recordRankedResult(s.playerId, name, false, false);

			if (teamRace)
				this.punishLeaver(s.playerId, name, leaving);
		}

		this.detach(s);

		if (dnf)
			this.settle(heat);

		// the armor goes with the inventory snapshot below, the transient tag has to be removed here
		if (teamRace && this.luckPerms != null)
			this.luckPerms.clearMeta(s.playerId, this.settings.teamMetaKey);

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
		s.runDistance = 0.0D;
		s.lastTrackPos = null;
		s.offTrack = false;
		s.offTrackTicks = 0;

		// The personal best exists for the standard single-lap race only. A multi-lap total is never compared with it,
		// and its checkpoint times only serve as the reference of the FIRST lap (see recordSplit).
		final RaceRecord best = this.storage.getRecord(s.heat.arena.getName(), s.playerId);

		s.bestTotalMs = best == null || s.heat.laps > 1 ? -1L : best.getBestMs();
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
		heat.ranked = this.isRanked(heat);

		for (final RaceSession racer : heat.racers()) {
			if (racer.state != RaceSession.State.COUNTDOWN)
				continue;

			racer.state = RaceSession.State.RUNNING;
			racer.runDistance = 0.0D;
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

		// a reset is already on its way (reset zone, off track, left the boat ...): this run no longer counts anything
		if (s.restartQueued)
			return;

		// reset zones (void / kill zones): exactly the selected blocks, swept so a fast fall cannot skip them
		for (final Region zone : arena.getResetZones())
			if (zone.intersectsExact(from, to)) {
				this.resetByZone(s, player);
				return;
			}

		final Zone start = arena.getStartLine();
		final Zone finish = arena.getFinishLine();

		if (start == null || finish == null)
			return;

		this.trackDistance(s, from, to);

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
	 * Blocks sailed (horizontal). A jump longer than a boat can sail in one move is a teleport (rescue, restart) and
	 * does not count.
	 */
	private void trackDistance(final RaceSession s, final Location from, final Location to) {
		if (from.getWorld() != to.getWorld())
			return;

		final double dx = to.getX() - from.getX();
		final double dz = to.getZ() - from.getZ();
		final double moved = Math.sqrt(dx * dx + dz * dz);

		if (moved < MAX_MOVE_BLOCKS)
			s.runDistance += moved;
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

		// from the second lap on there is no delta - it is neither calculated nor shown any more
		s.deltaText = "";

		Msg.title(player, s.lap == s.heat.laps ? "final-lap" : "new-lap", "lap", s.lap, "laps", s.heat.laps);
		this.settings.soundLap.play(player);
	}

	/** Stores the elapsed time at a split and updates the permanent action bar delta to the personal best. */
	private void recordSplit(final RaceSession s, final int splitIndex) {
		if (splitIndex < 0 || splitIndex >= s.splits.length)
			return;

		final long elapsed = this.elapsedMs(s);

		s.splits[splitIndex] = elapsed;

		// the delta to the personal best exists in the first lap only
		if (s.lap == 1 && s.referenceSplits != null && splitIndex < s.referenceSplits.length && s.referenceSplits[splitIndex] > 0L)
			s.deltaText = deltaText(elapsed - s.referenceSplits[splitIndex]);
	}

	/** "Lap 2/5 | " for multi-lap races, "" for single-lap ones (messages.yml actionbar.lap). */
	private String lapText(final RaceSession s) {
		return this.teamText(s) + (s.heat.laps > 1 ? Msg.text("actionbar.lap", "lap", Math.min(s.lap, s.heat.laps), "laps", s.heat.laps) : "");
	}

	private void finish(final RaceSession s, final Player player) {
		s.state = RaceSession.State.FINISHED;
		s.finalMs = this.elapsedMs(s);

		final Heat heat = s.heat;
		final String arena = heat.arena.getName();
		final long previousBest = s.bestTotalMs; // -1 in multi-lap races: there is no personal best to compare with
		final boolean blacklisted = this.storage.isBlacklisted(s.playerId);
		final int position = heat.event ? ++heat.finishedCount : 0;
		final boolean newBest = this.storage.submitRun(arena, heat.laps, s.playerId, player.getName(), s.finalMs, s.penaltyMs, s.splits.clone(), position, heat.fieldSize);
		final boolean rewarded = !blacklisted && this.giveReward(s, player);

		heat.results.add(new Heat.Result(s.playerId, player.getName(), s.finalMs, s.penaltyMs));

		final String summary;

		if (blacklisted)
			summary = "finish.banned";

		else if (heat.laps > 1)
			summary = "finish.multi-lap";

		else if (newBest)
			summary = previousBest > 0L ? "finish.new-best" : "finish.first";

		else
			summary = "finish.normal";

		final long deltaMs = previousBest > 0L ? s.finalMs - previousBest : 0L;

		// The result of the player (a party gets the leaderboard when everybody is done)
		Msg.raw(player, summary,
				"player", player.getName(),
				"arena", arena,
				"time", TimeFmt.format(s.finalMs),
				"penalty_note", s.penaltyMs > 0L ? Msg.text("finish.penalty-note", "penalty", TimeFmt.delta(s.penaltyMs)) : "",
				"best", previousBest > 0L ? TimeFmt.format(previousBest) : "-",
				"delta", TimeFmt.delta(deltaMs),
				"rank", Math.max(0, this.storage.getRank(arena, s.playerId)),
				"laps", heat.laps,
				"laps_note", heat.laps > 1 ? Msg.text("finish.laps-note", "laps", heat.laps) : "",
				"reward_note", rewarded ? Msg.text("finish.reward-note", "arena", arena) : "",
				"event_note", heat.event ? Msg.text("finish.event-note", "position", position, "racers", heat.fieldSize) : "");

		// Ranked race (casual ranked or competitive): games, the racer's own 1st place and the "not last" streak are
		// decided right now - everybody still racing will be behind. The rating itself moves when the race is settled.
		final boolean competitive = heat.competitive;
		final boolean placed = !blacklisted && (heat.ranked || competitive && heat.fieldSize >= this.settings.rankingMinRacers);
		final PlayerRating rating = placed ? this.storage.recordRankedResult(s.playerId, player.getName(), position < heat.fieldSize, position == 1)
				: this.storage.getRating(s.playerId, player.getName());
		final double xpBoost = competitive ? this.settings.compXpMultiplier * heat.xpBoost.getOrDefault(s.playerId, 1.0D) : 1.0D;

		if (this.captainHook != null && !blacklisted)
			this.captainHook.reportFinish(player, new CaptainHookHook.FinishReport(CaptainHookHook.trackId(arena), heat.laps, heat.laps == heat.arena.getLaps(),
					s.finalMs, s.finalMs - s.penaltyMs, lapTimes(s), s.runDistance, placed, placed ? position : 0, placed ? rating.streak() : -1,
					rating.rating(), newBest && heat.laps == 1, xpBoost));

		// Shown on the action bar while waiting for the teleport
		s.deltaText = previousBest > 0L ? deltaText(deltaMs) : "";

		Msg.title(player, "finish", "time", TimeFmt.format(s.finalMs), "arena", arena, "laps", heat.laps);
		this.settings.soundFinish.play(player);

		this.settle(heat);

		if (heat.privateHeat)
			// a party member stays on the map (free to move around) until the whole party has finished
			this.checkPartyComplete(heat);

		else
			// Short pause with the result on screen, then back to the hub (the boat is removed on dispose)
			this.later(s, this.settings.finishDelayTicks, () -> {
				if (!s.disposed && s.state == RaceSession.State.FINISHED && this.sessions.get(s.playerId) == s)
					this.dispose(s, LeaveMode.HUB);
			});
	}

	/** Official time of every lap: from the lap lines in the splits, the last lap ends with the finish. */
	private static long[] lapTimes(final RaceSession s) {
		final int laps = s.heat.laps;
		final long[] times = new long[laps];
		long previous = 0L;

		for (int lap = 1; lap <= laps; lap++) {
			final int index = (lap - 1) * (s.cpCount + 1) + s.cpCount;
			final long end = lap == laps ? s.finalMs : index < s.splits.length ? s.splits[index] : 0L;

			// a missing lap line (should not happen) makes the lap unusable instead of wrong
			times[lap - 1] = end > previous ? end - previous : 0L;
			previous = Math.max(previous, end);
		}

		return times;
	}

	// ------------------------------------------------------------------------------------------
	// Ranking
	// ------------------------------------------------------------------------------------------

	/** Event and party races with enough racers at lights out; solo runs never count. */
	private boolean isRanked(final Heat heat) {
		return this.settings.rankingEnabled && heat.event && !heat.competitive && heat.fieldSize >= this.settings.rankingMinRacers
				&& (!heat.privateHeat || this.settings.rankingPartyRaces);
	}

	/** Settles whatever the race still owes once nobody is racing: ranking points, team result. */
	private void settle(final Heat heat) {
		this.settleRanking(heat);
		this.settleTeams(heat);
	}

	/**
	 * Hands out the ranking points of a ranked race once nobody is racing any more. Order: finishers in finishing
	 * order, then the racers who left (the last to leave first). Blacklisted players are left out.
	 */
	private void settleRanking(final Heat heat) {
		if (!heat.ranked || heat.rankingSettled || this.massLeave)
			return;

		for (final RaceSession racer : heat.slots.values())
			if (racer.state == RaceSession.State.RUNNING)
				return;

		heat.rankingSettled = true;

		final List<UUID> order = new ArrayList<>();
		final Map<UUID, String> names = new HashMap<>();
		final List<Heat.Result> placed = new ArrayList<>(heat.results);

		for (int i = heat.dnf.size() - 1; i >= 0; i--)
			placed.add(heat.dnf.get(i));

		for (final Heat.Result result : placed)
			if (!this.storage.isBlacklisted(result.uuid()) && !names.containsKey(result.uuid())) {
				order.add(result.uuid());
				names.put(result.uuid(), result.name());
			}

		if (order.size() < 2)
			return;

		final Map<UUID, Integer> before = new HashMap<>();

		for (final UUID uuid : order)
			before.put(uuid, this.storage.getRating(uuid, names.get(uuid)).rating());

		final Map<UUID, Integer> deltas = Elo.deltas(order, before, this.settings.rankingK);

		this.storage.addRatings(deltas, names);

		final Map<UUID, Integer> after = new HashMap<>();

		for (final UUID uuid : order) {
			final int rating = this.storage.getRating(uuid, names.get(uuid)).rating();
			final Player player = Bukkit.getPlayer(uuid);

			after.put(uuid, rating);

			if (player != null) {
				final int change = rating - before.get(uuid);

				Msg.send(player, "ranking.change", "rating", rating, "delta", change > 0 ? "&a+" + change : change < 0 ? "&c" + change : "&7+0",
						"rank", this.storage.getRatingRank(uuid));
			}
		}

		if (this.captainHook != null)
			this.captainHook.reportRatings(after);
	}

	// ------------------------------------------------------------------------------------------
	// Competitive (team against team, from a lobby)
	// ------------------------------------------------------------------------------------------

	public enum CompJoinResult {
		QUEUED, DISABLED, NOT_LEADER, TOO_BIG, BANNED, BUSY, COOLDOWN, NO_ARENA
	}

	/** One team waiting in a competitive lobby: a party, or a solo player (a team of 1). */
	private static final class CompEntry {

		final UUID leader;
		/** the leader first; a party never changes while queued (it is dropped instead) */
		final List<UUID> members;
		/** arena name, null = any competitive arena */
		final String arena;

		CompEntry(final UUID leader, final List<UUID> members, final String arena) {
			this.leader = leader;
			this.members = members;
			this.arena = arena;
		}

		String key() {
			return lobbyKey(this.arena);
		}
	}

	/** A lobby somebody waits in, for /bre ranked list and the GUI. */
	public record CompLobbyInfo(String arena, int players, int teams, int min, int max, int countdown) {
	}

	private static String lobbyKey(final String arena) {
		return arena == null ? "*" : arena.toLowerCase(Locale.ROOT);
	}

	public boolean hasCompetitiveBypass(final Player player) {
		return player.hasPermission(COMP_BYPASS_PERMISSION);
	}

	/**
	 * Puts the player - or their whole party, which always plays as ONE team - in a competitive lobby.
	 *
	 * @param arena null = any competitive arena
	 */
	public CompJoinResult joinCompetitive(final Player player, final Arena arena) {
		if (!this.settings.compEnabled) {
			Msg.send(player, "comp.disabled");
			return CompJoinResult.DISABLED;
		}

		if (this.parties.isFollower(player)) {
			Msg.send(player, "party.only-leader-join");
			return CompJoinResult.NOT_LEADER;
		}

		final List<Player> group = this.parties.groupOf(player);

		if (group.size() > this.settings.compMaxTeamSize) {
			Msg.send(player, "comp.party-too-big", "size", group.size(), "max", this.settings.compMaxTeamSize);
			return CompJoinResult.TOO_BIG;
		}

		final long now = System.currentTimeMillis();

		for (final Player member : group) {
			if (this.storage.isBlacklisted(member.getUniqueId())) {
				Msg.send(player, group.size() > 1 ? "party.member-banned" : "race.banned", "player", member.getName());
				return CompJoinResult.BANNED;
			}

			if (this.sessions.containsKey(member.getUniqueId())) {
				Msg.send(player, "comp.member-busy", "player", member.getName());
				return CompJoinResult.BUSY;
			}

			final Long until = this.leaverCooldowns.get(member.getUniqueId());

			if (until != null && until > now && !this.hasCompetitiveBypass(member)) {
				Msg.send(player, "comp.cooldown", "player", member.getName(), "minutes", Math.max(1L, (until - now + 59_999L) / 60_000L));
				return CompJoinResult.COOLDOWN;
			}
		}

		if (arena != null ? !this.fitsCompetitive(arena, group.size(), player) : this.compArenas(group.size()).isEmpty()) {
			Msg.send(player, "comp.no-arena", "arena", arena != null ? arena.getName() : Msg.text("comp.any-arena"));
			return CompJoinResult.NO_ARENA;
		}

		final List<UUID> members = new ArrayList<>();

		for (final Player member : group) {
			this.removeFromCompQueue(member.getUniqueId(), false);
			this.dequeue(member.getUniqueId());
			members.add(member.getUniqueId());
		}

		final CompEntry entry = new CompEntry(player.getUniqueId(), members, arena != null ? arena.getName() : null);

		this.compQueue.add(entry);

		final CompLobbyInfo info = this.lobbyInfo(entry.key());

		for (final Player member : group)
			Msg.send(member, "comp.queued", "arena", info.arena(), "players", info.players(), "min", info.min(), "max", info.max());

		this.processCompetitive();

		return CompJoinResult.QUEUED;
	}

	/** @return true if the player (or their party) was in a competitive lobby */
	public boolean leaveCompetitive(final Player player) {
		return this.removeFromCompQueue(player.getUniqueId(), true);
	}

	public boolean isInCompetitiveQueue(final UUID uuid) {
		for (final CompEntry entry : this.compQueue)
			if (entry.members.contains(uuid))
				return true;

		return false;
	}

	/** Every lobby somebody waits in, oldest first. */
	public List<CompLobbyInfo> competitiveLobbies() {
		final List<CompLobbyInfo> list = new ArrayList<>();
		final Set<String> seen = new java.util.HashSet<>();

		for (final CompEntry entry : this.compQueue)
			if (seen.add(entry.key()))
				list.add(this.lobbyInfo(entry.key()));

		return list;
	}

	/** The lobby of an arena (null = the "any map" lobby). */
	public CompLobbyInfo competitiveLobby(final String arena) {
		return this.lobbyInfo(lobbyKey(arena));
	}

	private CompLobbyInfo lobbyInfo(final String key) {
		int players = 0;
		int teams = 0;

		for (final CompEntry entry : this.compQueue)
			if (entry.key().equals(key)) {
				players += entry.members.size();
				teams++;
			}

		final Long end = this.compCountdowns.get(key);
		final int countdown = end == null ? -1 : (int) Math.max(0L, (end - System.currentTimeMillis() + 999L) / 1000L);
		final Arena arena = "*".equals(key) ? null : this.arenas.get(key);

		return new CompLobbyInfo(arena != null ? arena.getName() : Msg.text("comp.any-arena"), players, teams, this.lobbyMin(key), this.lobbyMax(key), countdown);
	}

	/** Racers needed for the countdown: the arena's min-players; the "any map" lobby takes the lowest of all arenas. */
	private int lobbyMin(final String key) {
		if (!"*".equals(key)) {
			final Arena arena = this.arenas.get(key);

			return arena != null ? arena.competitiveMin(this.settings.compMinPlayers) : this.settings.compMinPlayers;
		}

		int min = Integer.MAX_VALUE;

		for (final Arena arena : this.compArenas(1))
			min = Math.min(min, arena.competitiveMin(this.settings.compMinPlayers));

		return min == Integer.MAX_VALUE ? this.settings.compMinPlayers : min;
	}

	/** Most racers of a match: the arena's max-players; the "any map" lobby takes the highest of all arenas. */
	private int lobbyMax(final String key) {
		if (!"*".equals(key)) {
			final Arena arena = this.arenas.get(key);

			return arena != null ? arena.competitiveMax() : 0;
		}

		int max = 0;

		for (final Arena arena : this.compArenas(1))
			max = Math.max(max, arena.competitiveMax());

		return max;
	}

	/** Live team score for the action bar ({lap_text}): "Red 7.5 pts | " in a competitive match, "" otherwise. */
	private String teamText(final RaceSession s) {
		final Heat heat = s.heat;

		if (heat == null || !heat.competitive)
			return "";

		final Integer team = heat.teamOf.get(s.playerId);

		if (team == null || team >= heat.teamColors.size())
			return "";

		final List<UUID> order = new ArrayList<>(heat.results.size());

		for (final Heat.Result result : heat.results)
			order.add(result.uuid());

		final double points = TeamScoring.liveAverage(order, heat.teamOf, team, this.settings.teamPoints);

		return Msg.text("actionbar.team", "team", heat.teamColors.get(team).colored(),
				"points", new TeamScoring.Standing(team, points, 0, 0).pointsText());
	}

	/** Team color of a racer of a running competitive match, or null (thread safe enough for placeholders). */
	public TeamColor teamColorOf(final UUID uuid) {
		final RaceSession session = this.sessions.get(uuid);
		final Heat heat = session != null ? session.heat : null;

		if (heat == null || !heat.competitive)
			return null;

		final Integer team = heat.teamOf.get(uuid);

		return team != null && team < heat.teamColors.size() ? heat.teamColors.get(team) : null;
	}

	/** The team tag (competitive.luckperms.format) of a racer of a running competitive match, or "". */
	public String teamTagOf(final UUID uuid) {
		final TeamColor color = this.teamColorOf(uuid);

		return color == null ? "" : this.teamTag(color);
	}

	private String teamTag(final TeamColor color) {
		return this.settings.teamMetaFormat.replace("{chat}", color.chat()).replace("{name}", color.name()).replace("{id}", color.id());
	}

	/** The arena can host a competitive match with at least this many racers (and is open for the player). */
	private boolean fitsCompetitive(final Arena arena, final int racers, final Player who) {
		return arena.isReady() && arena.isCompetitive() && arena.competitiveMax() >= Math.max(2, racers)
				&& (!arena.isMaintenance() || who != null && who.hasPermission(ADMIN_PERMISSION));
	}

	/** Open competitive arenas with room for at least this many racers. */
	private List<Arena> compArenas(final int racers) {
		final List<Arena> list = new ArrayList<>();

		for (final Arena arena : this.arenas.readyArenas())
			if (!arena.isMaintenance() && this.fitsCompetitive(arena, racers, null))
				list.add(arena);

		return list;
	}

	/**
	 * Removes the entry the player is part of (a party leaves the lobby as a whole).
	 *
	 * @param notifyOthers tell the other members of the entry
	 */
	private boolean removeFromCompQueue(final UUID uuid, final boolean notifyOthers) {
		for (final Iterator<CompEntry> it = this.compQueue.iterator(); it.hasNext();) {
			final CompEntry entry = it.next();

			if (!entry.members.contains(uuid))
				continue;

			it.remove();

			if (notifyOthers)
				for (final UUID member : entry.members)
					if (!member.equals(uuid)) {
						final Player other = Bukkit.getPlayer(member);

						if (other != null)
							Msg.send(other, "comp.queue-cancelled");
					}

			return true;
		}

		return false;
	}

	/** Still valid: everybody online and free, the party unchanged, the arena still there. */
	private boolean isValid(final CompEntry entry) {
		if (!this.settings.compEnabled || entry.members.size() > this.settings.compMaxTeamSize)
			return false;

		if (entry.arena != null && this.arenas.get(entry.arena) == null)
			return false;

		for (final UUID member : entry.members)
			if (Bukkit.getPlayer(member) == null || this.sessions.containsKey(member) || this.storage.isBlacklisted(member))
				return false;

		final Set<UUID> now = new java.util.HashSet<>();

		for (final Player member : this.parties.groupOf(Bukkit.getPlayer(entry.leader)))
			now.add(member.getUniqueId());

		return now.equals(new java.util.HashSet<>(entry.members));
	}

	/**
	 * Once a second (and when a track becomes free): drops invalid entries, then for every lobby
	 * - not enough racers (min-players) or fewer than 2 teams: no countdown (a running one is cancelled),
	 * - enough: the countdown runs (competitive.countdown-seconds),
	 * - full (max-players) or the countdown is over: the match starts on a free copy of the arena.
	 */
	private void processCompetitive() {
		if (this.compQueue.isEmpty() && this.compCountdowns.isEmpty() || this.shuttingDown || this.processingComp)
			return;

		this.processingComp = true;

		try {
			for (final Iterator<CompEntry> it = this.compQueue.iterator(); it.hasNext();) {
				final CompEntry entry = it.next();

				if (!this.isValid(entry)) {
					it.remove();

					for (final UUID member : entry.members) {
						final Player player = Bukkit.getPlayer(member);

						if (player != null && !this.sessions.containsKey(member))
							Msg.send(player, "comp.queue-cancelled");
					}
				}
			}

			final Map<String, List<CompEntry>> lobbies = new LinkedHashMap<>();

			for (final CompEntry entry : this.compQueue)
				lobbies.computeIfAbsent(entry.key(), key -> new ArrayList<>()).add(entry);

			// lobbies that emptied: forget their countdown
			this.compCountdowns.keySet().removeIf(key -> !lobbies.containsKey(key));
			this.compWaitingForTrack.removeIf(key -> !lobbies.containsKey(key));

			for (final Map.Entry<String, List<CompEntry>> lobby : lobbies.entrySet())
				this.processLobby(lobby.getKey(), lobby.getValue());

		} finally {
			this.processingComp = false;
		}
	}

	private void processLobby(final String key, final List<CompEntry> entries) {
		final List<Integer> sizes = new ArrayList<>();

		for (final CompEntry entry : entries)
			sizes.add(entry.members.size());

		final int max = this.lobbyMax(key);
		final int min = this.lobbyMin(key);
		final List<Integer> selected = LobbyFill.select(sizes, max, this.settings.teamColors.size(), this.settings.compMaxTeamSize);
		final int players = LobbyFill.players(sizes, selected);
		final boolean ready = selected.size() >= 2 && players >= min;
		final long now = System.currentTimeMillis();
		final Long end = this.compCountdowns.get(key);

		if (!ready) {
			if (end != null) {
				this.compCountdowns.remove(key);
				this.tellLobby(entries, "comp.countdown-cancelled", "players", players, "min", min);
			}

			return;
		}

		if (end == null) {
			this.compCountdowns.put(key, now + this.settings.compCountdownSeconds * 1000L);
			this.tellLobby(entries, "comp.countdown-started", "seconds", this.settings.compCountdownSeconds, "players", players, "max", max);

			if (this.settings.compCountdownSeconds > 0 && players < max)
				return;

		} else if (players < max && now < end) {
			final long left = (end - now + 999L) / 1000L;

			if (left == 10 || left == 5 || left <= 3)
				this.tellLobby(entries, "comp.countdown", "seconds", left, "players", players, "max", max);

			return;
		}

		// full lobby or countdown over: start on a free copy of a fitting arena, otherwise wait for one
		Arena arena = null;
		String world = null;

		if (!"*".equals(key)) {
			arena = this.arenas.get(key);
			world = arena != null && this.fitsCompetitive(arena, players, Bukkit.getPlayer(entries.get(0).leader)) ? this.freeInstanceWorld(arena, null) : null;

		} else {
			final List<Arena> free = new ArrayList<>();

			for (final Arena candidate : this.compArenas(players))
				if (candidate.competitiveMin(this.settings.compMinPlayers) <= players && this.freeInstanceWorld(candidate, null) != null)
					free.add(candidate);

			if (!free.isEmpty()) {
				arena = free.get(ThreadLocalRandom.current().nextInt(free.size()));
				world = this.freeInstanceWorld(arena, null);
			}
		}

		if (arena == null || world == null) {
			if (!this.compWaitingForTrack.contains(key)) {
				this.compWaitingForTrack.add(key);
				this.tellLobby(entries, "comp.waiting-track");
			}

			return;
		}

		this.compWaitingForTrack.remove(key);
		this.compCountdowns.remove(key);

		final List<List<Player>> teams = new ArrayList<>();

		for (final int index : selected) {
			final CompEntry entry = entries.get(index);
			final List<Player> members = new ArrayList<>();

			this.compQueue.remove(entry);

			for (final UUID member : entry.members)
				members.add(Bukkit.getPlayer(member));

			teams.add(members);
		}

		this.startCompetitiveMatch(arena, world, teams);
	}

	private void tellLobby(final List<CompEntry> entries, final String key, final Object... placeholders) {
		for (final CompEntry entry : entries)
			for (final UUID member : entry.members) {
				final Player player = Bukkit.getPlayer(member);

				if (player != null)
					Msg.send(player, key, placeholders);
			}
	}

	/**
	 * Seats a match: the first racers of every team, then the second ones ... (nobody gets the whole front row), every
	 * racer is dressed in the team color, gets the team tag and is told their team (and the lineup bonus). The lights
	 * start right away.
	 */
	private void startCompetitiveMatch(final Arena arena, final String world, final List<List<Player>> teams) {
		final Arena instance = world.equals(arena.getWorldName()) ? arena : arena.inWorld(world);
		final Heat heat = new Heat(instanceKey(arena, world), instance, true);
		final long now = System.currentTimeMillis();

		heat.privateHeat = true;
		heat.competitive = true;

		int biggest = 0;

		for (int team = 0; team < teams.size(); team++) {
			final List<Player> members = teams.get(team);
			final List<UUID> uuids = new ArrayList<>();

			heat.teamColors.add(this.settings.teamColors.get(team));
			biggest = Math.max(biggest, members.size());

			for (final Player member : members) {
				heat.teamOf.put(member.getUniqueId(), team);
				uuids.add(member.getUniqueId());
			}

			final int streak = this.lineups.countMatch(uuids, now, this.settings.lineupExpireMs);
			final double bonus = Lineups.bonus(streak, this.settings.lineupPerMatch, this.settings.lineupMax);

			for (final UUID uuid : uuids) {
				heat.xpBoost.put(uuid, 1.0D + bonus);
				heat.lineupStreak.put(uuid, streak);
			}
		}

		// attaching in this order makes the lowest free grid position alternate between the teams
		final List<Player> seating = new ArrayList<>();

		for (int member = 0; member < biggest; member++)
			for (final List<Player> team : teams)
				if (member < team.size())
					seating.add(team.get(member));

		final JoinResult result = this.attachGroup(seating, heat, true, true);

		if (heat.slots.isEmpty() && this.heats.get(heat.key) == heat)
			this.releaseHeat(heat);

		if (result != JoinResult.JOINED) {
			for (final Player player : seating)
				if (player.isOnline() && !this.sessions.containsKey(player.getUniqueId()))
					Msg.send(player, "comp.match-failed");

			return;
		}

		for (final RaceSession racer : heat.racers()) {
			final Player player = Bukkit.getPlayer(racer.playerId);
			final Integer team = heat.teamOf.get(racer.playerId);

			if (player == null || team == null)
				continue;

			final TeamColor color = heat.teamColors.get(team);
			final List<String> mates = new ArrayList<>();

			for (final Player mate : teams.get(team))
				if (!mate.equals(player))
					mates.add(mate.getName());

			this.dressTeam(player, color);

			if (this.luckPerms != null)
				this.luckPerms.setMeta(player.getUniqueId(), this.settings.teamMetaKey, this.teamTag(color));

			Msg.send(player, "comp.assigned", "team", color.colored(), "color", color.chat(), "arena", arena.getName(), "teams", teams.size(),
					"racers", seating.size(), "mates", mates.isEmpty() ? Msg.text("comp.solo") : String.join(", ", mates));

			final int streak = heat.lineupStreak.getOrDefault(racer.playerId, 1);

			if (streak > 1)
				Msg.send(player, "comp.lineup-bonus", "matches", streak, "bonus", Math.round((heat.xpBoost.get(racer.playerId) - 1.0D) * 100.0D));

			Msg.title(player, "team-assigned", "team", color.colored(), "teams", teams.size(), "racers", seating.size());
		}
	}

	/** Full leather armor in the team color. It cannot be taken off (the inventory is locked while racing) and goes
	 * away with the inventory snapshot when the race is over. */
	private void dressTeam(final Player player, final TeamColor color) {
		final PlayerInventory inventory = player.getInventory();

		inventory.setHelmet(this.teamArmor(Material.LEATHER_HELMET, color));
		inventory.setChestplate(this.teamArmor(Material.LEATHER_CHESTPLATE, color));
		inventory.setLeggings(this.teamArmor(Material.LEATHER_LEGGINGS, color));
		inventory.setBoots(this.teamArmor(Material.LEATHER_BOOTS, color));
	}

	private ItemStack teamArmor(final Material material, final TeamColor color) {
		final ItemStack item = new ItemStack(material);
		final LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();

		meta.setColor(color.armor());
		meta.setUnbreakable(true);
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ENCHANTS);
		meta.displayName(Msg.item(Msg.text("comp.armor-name", "team", color.colored())));
		meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.STRING, "team-armor");
		item.setItemMeta(meta);

		return item;
	}

	/**
	 * Leaving a running competitive match: the result already counts as a DNF (0 points for the team, last place in the
	 * rating); on top of that competitive.leaver.rating-penalty points and a cooldown before the next match. Players
	 * with boatrace.competitive.bypass (ops) are spared - for testing.
	 */
	private void punishLeaver(final UUID uuid, final String name, final Player player) {
		if (player != null && this.hasCompetitiveBypass(player))
			return;

		if (this.settings.compLeaverCooldownMs > 0L)
			this.leaverCooldowns.put(uuid, System.currentTimeMillis() + this.settings.compLeaverCooldownMs);

		if (this.settings.rankingEnabled && this.settings.compLeaverPenalty > 0)
			this.storage.addRatings(Map.of(uuid, -this.settings.compLeaverPenalty), Map.of(uuid, name));

		if (player != null)
			Msg.send(player, "comp.leaver-penalty", "points", this.settings.compLeaverPenalty,
					"minutes", Math.max(1L, this.settings.compLeaverCooldownMs / 60_000L));
	}

	/**
	 * Computes the match result once nobody is racing any more: average points per member (DNF = 0), then the rating:
	 * ONE rating per player - the teams in standing order are the "players" of an ELO race (a team is as strong as the
	 * average of its members, competitive.k-factor), every member gets their team's change. Then the CaptainHook stats
	 * and XP.
	 */
	private void settleTeams(final Heat heat) {
		// only once the race was really started and nobody is racing (or waiting for the lights) any more
		if (!heat.competitive || heat.teamSettled || this.massLeave || heat.phase != Heat.Phase.RACING)
			return;

		for (final RaceSession racer : heat.slots.values())
			if (racer.state == RaceSession.State.RUNNING || racer.state == RaceSession.State.COUNTDOWN)
				return;

		heat.teamSettled = true;

		final List<UUID> finishOrder = new ArrayList<>();
		final Map<UUID, String> names = new HashMap<>();

		for (final Heat.Result result : heat.results) {
			finishOrder.add(result.uuid());
			names.put(result.uuid(), result.name());
		}

		for (final Heat.Result result : heat.dnf)
			names.putIfAbsent(result.uuid(), result.name());

		for (final UUID uuid : heat.teamOf.keySet())
			names.putIfAbsent(uuid, this.storage.getRating(uuid, "?").name());

		heat.teamStandings = TeamScoring.standings(finishOrder, heat.teamOf, heat.teamColors.size(), this.settings.teamPoints);

		final int winner = heat.teamStandings.get(0).team();
		final Set<UUID> counted = new java.util.HashSet<>();
		final Set<UUID> winners = new java.util.HashSet<>();

		// nobody crossed the line: there is no winner and nothing to compare
		final boolean decided = !finishOrder.isEmpty();

		for (final Map.Entry<UUID, Integer> member : heat.teamOf.entrySet())
			if (!this.storage.isBlacklisted(member.getKey())) {
				counted.add(member.getKey());

				if (decided && member.getValue() == winner)
					winners.add(member.getKey());
			}

		final boolean rated = decided && this.settings.rankingEnabled && heat.fieldSize >= this.settings.rankingMinRacers && heat.teamColors.size() >= 2;
		final Map<UUID, Integer> before = new HashMap<>();

		for (final UUID uuid : counted)
			before.put(uuid, this.storage.getRating(uuid, names.get(uuid)).rating());

		if (rated) {
			final List<UUID> teamOrder = new ArrayList<>();
			final Map<UUID, Integer> teamRatings = new HashMap<>();

			for (final TeamScoring.Standing standing : heat.teamStandings) {
				final UUID key = new UUID(0L, standing.team());
				int sum = 0;
				int count = 0;

				for (final UUID uuid : counted)
					if (heat.teamOf.get(uuid) == standing.team()) {
						sum += before.get(uuid);
						count++;
					}

				teamOrder.add(key);
				teamRatings.put(key, count > 0 ? Math.round((float) sum / count) : this.settings.rankingStart);
			}

			final Map<UUID, Integer> teamDeltas = Elo.deltas(teamOrder, teamRatings, this.settings.compK);
			final Map<UUID, Integer> deltas = new HashMap<>();

			for (final UUID uuid : counted)
				deltas.put(uuid, teamDeltas.get(new UUID(0L, heat.teamOf.get(uuid))));

			this.storage.addRatings(deltas, names);
		}

		this.storage.recordCompetitiveResult(counted, names, winners);

		// a team whose members ALL crossed the line
		final Set<UUID> fullTeams = new java.util.HashSet<>();
		final Set<UUID> finishedSet = new java.util.HashSet<>(finishOrder);

		for (final TeamScoring.Standing standing : heat.teamStandings) {
			boolean all = standing.members() > 0;

			for (final Map.Entry<UUID, Integer> member : heat.teamOf.entrySet())
				if (member.getValue() == standing.team() && !finishedSet.contains(member.getKey()))
					all = false;

			if (all)
				for (final Map.Entry<UUID, Integer> member : heat.teamOf.entrySet())
					if (member.getValue() == standing.team() && counted.contains(member.getKey()))
						fullTeams.add(member.getKey());
		}

		final Map<UUID, Integer> after = new HashMap<>();

		for (final UUID uuid : counted) {
			final int rating = this.storage.getRating(uuid, names.get(uuid)).rating();
			final Player player = Bukkit.getPlayer(uuid);

			after.put(uuid, rating);

			if (rated && player != null) {
				final int change = rating - before.get(uuid);

				Msg.send(player, "comp.rating-change", "rating", rating, "delta", change > 0 ? "&a+" + change : change < 0 ? "&c" + change : "&7+0",
						"rank", this.storage.getRatingRank(uuid));
			}
		}

		if (this.captainHook != null) {
			final List<UUID> finished = new ArrayList<>();

			for (final UUID uuid : finishOrder)
				if (counted.contains(uuid))
					finished.add(uuid);

			this.captainHook.reportCompetitiveResult(finished, winners, fullTeams, rated ? after : Map.of(), after, heat.xpBoost);
		}
	}

	/** Team standings with every member's place, to every remaining racer of the match. */
	private void sendTeamLeaderboard(final Heat heat, final List<RaceSession> recipients) {
		if (heat.teamStandings == null)
			return;

		final Map<UUID, Integer> places = new HashMap<>();
		final Map<UUID, String> names = new HashMap<>();

		for (int index = 0; index < heat.results.size(); index++) {
			places.put(heat.results.get(index).uuid(), index + 1);
			names.put(heat.results.get(index).uuid(), heat.results.get(index).name());
		}

		for (final Heat.Result result : heat.dnf)
			names.putIfAbsent(result.uuid(), result.name());

		final String lapsNote = heat.laps > 1 ? Msg.text("finish.laps-note", "laps", heat.laps) : "";

		for (final RaceSession recipient : recipients) {
			final Player player = Bukkit.getPlayer(recipient.playerId);

			if (player == null)
				continue;

			final Integer own = heat.teamOf.get(recipient.playerId);

			Msg.raw(player, "finish.team-header", "arena", heat.arena.getName(), "teams", heat.teamColors.size(), "laps_note", lapsNote);

			int place = 1;

			for (final TeamScoring.Standing standing : heat.teamStandings) {
				final TeamColor color = heat.teamColors.get(standing.team());
				final List<String> members = new ArrayList<>();

				for (final Map.Entry<UUID, Integer> member : heat.teamOf.entrySet())
					if (member.getValue() == standing.team()) {
						final Integer position = places.get(member.getKey());

						members.add(Msg.text("finish.team-member", "color", color.chat(), "player", names.getOrDefault(member.getKey(), "?"),
								"position", position != null ? "#" + position : Msg.text("finish.team-dnf")));
					}

				Msg.raw(player, "finish.team-line", "place", place++, "team", color.colored(), "points", standing.pointsText(),
						"members", String.join("&7, ", members), "you", own != null && own == standing.team() ? Msg.text("finish.party-you") : "");
			}

			Msg.raw(player, "finish.team-footer", "arena", heat.arena.getName(), "teams", heat.teamColors.size());
		}
	}

	/**
	 * Party heats: once EVERY remaining racer has finished, the party leaderboard goes to all of them and - after the
	 * usual pause - they are all sent to the hub at the same moment. Racers who left meanwhile do not hold anybody up.
	 */
	private void checkPartyComplete(final Heat heat) {
		if (!heat.privateHeat || heat.partyDone)
			return;

		final List<RaceSession> racers = heat.racers();

		if (racers.isEmpty())
			return;

		for (final RaceSession racer : racers)
			if (racer.state != RaceSession.State.FINISHED)
				return;

		heat.partyDone = true;

		if (heat.competitive) {
			this.settleTeams(heat);
			this.sendTeamLeaderboard(heat, racers);

		} else
			this.sendPartyLeaderboard(heat, racers);

		for (final RaceSession racer : racers)
			this.later(racer, this.settings.finishDelayTicks, () -> {
				if (!racer.disposed && racer.state == RaceSession.State.FINISHED && this.sessions.get(racer.playerId) == racer)
					this.dispose(racer, LeaveMode.HUB);
			});
	}

	/** Everybody who finished, fastest first (penalties included), as chat lines to every remaining party member. */
	private void sendPartyLeaderboard(final Heat heat, final List<RaceSession> recipients) {
		final List<Heat.Result> ranking = new ArrayList<>(heat.results);

		ranking.sort(Comparator.comparingLong(Heat.Result::totalMs));

		final String arena = heat.arena.getName();
		final String lapsNote = heat.laps > 1 ? Msg.text("finish.laps-note", "laps", heat.laps) : "";

		for (final RaceSession recipient : recipients) {
			final Player player = Bukkit.getPlayer(recipient.playerId);

			if (player == null)
				continue;

			Msg.raw(player, "finish.party-header", "arena", arena, "laps", heat.laps, "laps_note", lapsNote, "players", ranking.size());

			int place = 1;

			for (final Heat.Result result : ranking)
				Msg.raw(player, "finish.party-line",
						"position", place++,
						"player", result.name(),
						"time", TimeFmt.format(result.totalMs()),
						"penalty_note", result.penaltyMs() > 0L ? Msg.text("finish.penalty-note", "penalty", TimeFmt.delta(result.penaltyMs())) : "",
						"you", result.uuid().equals(recipient.playerId) ? Msg.text("finish.party-you") : "");

			Msg.raw(player, "finish.party-footer", "arena", arena, "laps", heat.laps, "players", ranking.size());
		}
	}

	private static int finishedCount(final Heat heat) {
		int done = 0;

		for (final RaceSession racer : heat.racers())
			if (racer.state == RaceSession.State.FINISHED)
				done++;

		return done;
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
		final Ground ground = this.groundUnder(s.boat, allowed);

		// Flying or falling (ramp jump, drop, falling boat): neither on nor off the track. Never a violation, and the
		// time spent off the track starts anew once the boat lands.
		if (ground == Ground.AIR) {
			s.offTrackTicks = 0;
			return;
		}

		if (ground == Ground.TRACK) {
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

	/** What is under the boat. */
	private enum Ground {
		/** no surface under it: flying / falling */
		AIR,
		/** a track block under (part of) it */
		TRACK,
		/** surface under it, but no track block */
		OFF_TRACK
	}

	/**
	 * Flying / falling when the game itself says so (boat status IN_AIR) or when there is no surface at all under the
	 * boat's footprint within scan-depth blocks. On the track as soon as ANY block of the footprint is a track block
	 * (a boat half over the edge is still on the track). Only a surface that is not a track block is off the track.
	 */
	private Ground groundUnder(final Boat boat, final Set<Material> allowed) {
		if (boat.getStatus() == Boat.Status.IN_AIR)
			return Ground.AIR;

		final Location location = boat.getLocation();
		final World world = location.getWorld();

		if (world == null)
			return Ground.AIR;

		final double radius = this.settings.offTrackFootprint;
		final double[][] offsets = radius <= 0.0D
				? new double[][] { { 0.0D, 0.0D } }
				: new double[][] { { 0.0D, 0.0D }, { radius, 0.0D }, { -radius, 0.0D }, { 0.0D, radius }, { 0.0D, -radius } };
		final int top = (int) Math.floor(location.getY() - 0.1D);
		boolean surface = false;

		for (final double[] offset : offsets) {
			final Material found = this.surfaceAt(world, (int) Math.floor(location.getX() + offset[0]), top, (int) Math.floor(location.getZ() + offset[1]), allowed);

			if (found == null)
				continue;

			if (allowed.contains(found))
				return Ground.TRACK;

			surface = true;
		}

		return surface ? Ground.OFF_TRACK : Ground.AIR;
	}

	/**
	 * The first block under the boat (scan-depth blocks deep) that something can drive on: a solid block, water, lava
	 * or a track block. Air and non-solid decoration (grass, flowers, ...) are not ground. Null when there is none.
	 */
	private Material surfaceAt(final World world, final int x, final int top, final int z, final Set<Material> allowed) {
		for (int depth = 0; depth < this.settings.offTrackScanDepth; depth++) {
			final Material type = world.getBlockAt(x, top - depth, z).getType();

			if (allowed.contains(type) || this.solidCheck.test(type) || type == Material.WATER || type == Material.LAVA)
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

	/**
	 * A racer touched a reset zone. Solo: exactly what /bre reset does - the run is aborted and a fresh attempt starts
	 * (clock, checkpoints, laps and penalties reset, new countdown). Event / party: the clock belongs to everybody, so
	 * the racer is put back on their own grid slot with checkpoints / laps reset and has to cross the start line again;
	 * the time that has passed counts against them.
	 */
	private void resetByZone(final RaceSession s, final Player player) {
		Msg.title(player, "reset-zone");
		this.settings.soundResetZone.play(player);

		if (s.heat.event)
			this.queueSlotReset(s);
		else
			this.queueRestart(s);
	}

	private void queueSlotReset(final RaceSession s) {
		if (s.restartQueued || s.disposed)
			return;

		s.restartQueued = true;

		// next tick - never teleport inside the move event that triggered this
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			if (s.disposed || this.sessions.get(s.playerId) != s || s.heat == null)
				return;

			s.restartQueued = false;
			this.resetProgress(s);
			this.place(s, this.spawnOf(s));
		});
	}

	/** Back to the beginning of the race for one racer of a running event: checkpoints, laps, start line - not the clock. */
	private void resetProgress(final RaceSession s) {
		s.lap = 1;
		s.nextCheckpoint = 0;
		s.crossedStart = false;
		s.splits = new long[s.splits.length];
		s.deltaText = "";
		s.lastTrackPos = null;
		s.offTrack = false;
		s.offTrackTicks = 0;
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
		this.dequeue(player.getUniqueId());
		this.removeFromCompQueue(player.getUniqueId(), true);

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
		// safety net once a second - the normal trigger is an instance being released
		if ((!this.queues.isEmpty() || !this.compQueue.isEmpty() || !this.compCountdowns.isEmpty()) && ++this.queueTicker % 20 == 0) {
			this.processQueues();
			this.processCompetitive();
		}

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
				case FINISHED -> {
					if (s.heat.privateHeat && !s.heat.partyDone)
						Msg.actionBar(player, "actionbar.finished-party", "time", TimeFmt.format(s.finalMs), "delta_text", s.deltaText,
								"lap_text", lapText(s), "lap", s.heat.laps, "laps", s.heat.laps,
								"done", finishedCount(s.heat), "total", s.heat.slots.size());
					else
						Msg.actionBar(player, "actionbar.finished", "time", TimeFmt.format(s.finalMs), "delta_text", s.deltaText,
								"lap_text", lapText(s), "lap", s.heat.laps, "laps", s.heat.laps);
				}
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
