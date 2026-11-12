package pl.kingdomcraft.boatrace;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

/** Typed view over config.yml (read once on load / reload). */
public final class Settings {

	/** A configured sound: a Bukkit Sound name (ENTITY_PLAYER_LEVELUP) or a sound key (minecraft:entity.player.levelup). */
	public record SoundSpec(String key, float volume, float pitch) {

		/** Plays the sound to this player only, at their position; {@code pitchOffset} is added to the pitch. */
		public void play(final org.bukkit.entity.Player player, final float pitchOffset) {
			if (this.key != null)
				player.playSound(player.getLocation(), this.key, this.volume, Math.max(0.5F, Math.min(2.0F, this.pitch + pitchOffset)));
		}

		public void play(final org.bukkit.entity.Player player) {
			this.play(player, 0.0F);
		}
	}

	// --- network identity (written to the database with every result) ---
	public String serverName = "hub";
	public String serverGroup = "";

	/** how often (seconds) the record cache is reloaded from the shared database (other servers' times) */
	public int recordsRefreshSeconds = 30;

	// --- sounds ---
	public SoundSpec soundCountdown = new SoundSpec("minecraft:block.note_block.pling", 1.0F, 0.6F);
	/** pitch raised by this much per lit lamp (0 = constant pitch) */
	public float soundCountdownPitchStep = 0.16F;
	public SoundSpec soundGo = new SoundSpec("minecraft:entity.player.levelup", 1.0F, 1.5F);
	public SoundSpec soundCheckpoint = new SoundSpec("minecraft:entity.experience_orb.pickup", 0.8F, 1.4F);
	public SoundSpec soundFinish = new SoundSpec("minecraft:ui.toast.challenge_complete", 1.0F, 1.0F);
	public SoundSpec soundOffTrack = new SoundSpec("minecraft:block.note_block.bass", 1.0F, 0.6F);
	public SoundSpec soundJumpstart = new SoundSpec("minecraft:entity.villager.no", 1.0F, 1.0F);
	public SoundSpec soundLap = new SoundSpec("minecraft:block.note_block.chime", 1.0F, 1.2F);
	public SoundSpec soundMissedCheckpoint = new SoundSpec("minecraft:entity.villager.no", 1.0F, 0.8F);

	// --- CaptainHook (season pass, achievements, missions) ---
	public boolean chEnabled = true;
	/** game id the proxy (and the website) knows BoatRace by (a-z, 0-9, _ and -) */
	public String chGame = "formula-boat-racing";
	/** results below / above these are not reported at all (a stored min / max stays forever) */
	public long chMinLapMs = 5000L;
	public double chMaxAvgSpeed = 75.0D;
	public long chXpFinish = 5L;
	public long chXpWin = 20L;
	public long chXpPodium = 10L;
	public long chXpPersonalBest = 10L;
	/** blocks sailed per 1 XP (0 = no distance XP) */
	public int chBlocksPerXp = 250;
	/** most season pass XP one player gets from BoatRace per day (0 = no limit) */
	public long chDailyXpLimit = 300L;
	// --- competitive (team against team, from a lobby) ---
	public boolean compEnabled = false;
	/** default of an arena: racers needed before the countdown starts (an arena can override it in arenas.yml) */
	public int compMinPlayers = 4;
	/** biggest team (a party); a solo player is a team of 1 */
	public int compMaxTeamSize = 4;
	/** countdown once the lobby has enough racers; a full lobby starts at once */
	public int compCountdownSeconds = 30;
	/** ELO points at stake in a competitive match (the casual k is ranking.k-factor) */
	public double compK = 48.0D;
	/** points by finishing position (index 0 = 1st place); a team scores the average of its members */
	public int[] teamPoints = { 10, 8, 6, 5, 4, 3, 2, 1 };
	/** leaving a running competitive match: rating points lost on top of the result, and no new match for a while */
	public int compLeaverPenalty = 15;
	public long compLeaverCooldownMs = 5L * 60_000L;
	/** race XP (captainhook.pass-xp) of competitive matches is multiplied by this */
	public double compXpMultiplier = 2.0D;
	/** XP for every member of the winning team who finished */
	public long compXpWin = 30L;
	/** XP for every member of a team whose members ALL finished */
	public long compXpFullTeam = 10L;
	/** same lineup bonus: + per match in a row with exactly the same team, at most max, forgotten after a break */
	public double lineupPerMatch = 0.10D;
	public double lineupMax = 0.30D;
	public long lineupExpireMs = 30L * 60_000L;
	/** team colors in assignment order (team 1 = first color); also the most teams of one match */
	public final List<pl.kingdomcraft.boatrace.team.TeamColor> teamColors = new ArrayList<>();
	public boolean teamLuckPerms = true;
	public String teamMetaKey = "boatrace_team";
	/** value of the LuckPerms meta, {chat} {name} {id} */
	public String teamMetaFormat = "{chat}[{name}] ";

	// --- tab list ---
	/** a player only sees the players of their own group in the tab list: their race session, or the lobby */
	public boolean tabListSessionOnly = false;

	// --- ranking (ELO-like points of races against other players) ---
	public boolean rankingEnabled = true;
	public int rankingStart = 1000;
	public int rankingMin = 100;
	/** casual k (events, party races); competitive matches use competitive.k-factor */
	public double rankingK = 16.0D;
	/** a race is ranked with at least this many racers at lights out (also needed for wins / podiums) */
	public int rankingMinRacers = 2;
	/** party races count for the ranking too */
	public boolean rankingPartyRaces = true;
	/** players need this many ranked races to show up on the ranking leaderboard */
	public int rankingLeaderboardMinGames = 5;
	/** rating -> multiplier of the season pass XP of a race, highest matching rating wins */
	public final java.util.TreeMap<Integer, Double> rankingXpMultipliers = new java.util.TreeMap<>();

	/** @return the XP multiplier for this rating (1.0 without tiers) */
	public double xpMultiplier(final int rating) {
		final Map.Entry<Integer, Double> tier = this.rankingXpMultipliers.floorEntry(rating);

		return tier != null ? tier.getValue() : this.rankingXpMultipliers.isEmpty() ? 1.0D : this.rankingXpMultipliers.firstEntry().getValue();
	}

	public String hubCommand = "spawn";
	public EntityType boatType = EntityType.OAK_BOAT;

	/** jumpstart penalty when the player moves after ALL lamps are lit (latest possible jumpstart) */
	public long penaltyMinMs = 4500L;
	/** jumpstart penalty when the player moves before the first lamp is lit (earliest possible jumpstart) */
	public long penaltyMaxMs = 12500L;

	/** number of lamps a single /bre lights set selection is split into */
	public int lamps = 5;
	public int firstDelayTicks = 20;
	public int intervalTicks = 20;
	public int holdMinTicks = 10;
	public int holdMaxTicks = 50;
	public BlockData lampOn = Bukkit.createBlockData(Material.RED_CONCRETE);
	public BlockData lampOff = Bukkit.createBlockData(Material.BLACK_CONCRETE);

	/** pause between crossing the finish line and the teleport to the hub */
	public int finishDelayTicks = 60;

	// --- start signal (blocks that turn "green" at lights out) ---
	public enum SignalMode { CLIENT, WORLD }

	public boolean signalEnabled = true;
	public SignalMode signalMode = SignalMode.CLIENT;
	public BlockData signalGo = Bukkit.createBlockData(Material.LIME_CONCRETE);
	/** null = the real block of the world */
	public BlockData signalOff = null;
	public int signalDurationTicks = 100;

	// --- off-track detection ---
	public enum OffTrackAction { PENALTY, TELEPORT_BACK, RESET }

	public boolean offTrackEnabled = true;
	public int offTrackIntervalTicks = 2;
	public int offTrackGraceTicks = 10;
	public OffTrackAction offTrackAction = OffTrackAction.PENALTY;
	public long offTrackPenaltyMs = 3000L;
	public int offTrackScanDepth = 3;
	public final Set<Material> defaultTrackBlocks = EnumSet.noneOf(Material.class);

	// --- events (the grid itself is unlimited: /bre setspawn <arena> <1, 2, 3 ...>) ---
	public boolean eventAutoStartWhenFull = true;
	public int eventStartDelayTicks = 60;

	// --- party / queue / map permissions ---
	public int partyDefaultLimit = 4;
	public int partyMaxLimit = 10;
	public int partyInviteSeconds = 60;
	public boolean queueEnabled = true;
	public boolean enforceMapPermissionOnCommand = false;

	// --- fallback maps ---
	public boolean fallbackEnabled = true;
	public boolean fallbackRandomArena = true;
	/** lower-case arena name -> worlds holding an identical copy of the track (same coordinates) */
	public final Map<String, List<String>> fallbackWorlds = new HashMap<>();

	public boolean hotbarEnabled = true;
	public int resetSlot = 7;
	public int hubSlot = 8;

	public String placeholderEmpty = "-";

	public void load(final FileConfiguration c) {
		this.serverName = c.getString("Server.Name", "hub");
		this.serverGroup = c.getString("Server.Group", "");
		this.recordsRefreshSeconds = Math.max(5, c.getInt("records.refresh-seconds", 30));

		this.soundCountdown = sound(c, "start-countdown", this.soundCountdown);
		this.soundCountdownPitchStep = (float) c.getDouble("Sounds.start-countdown.pitch-step", 0.16D);
		this.soundGo = sound(c, "start-go", this.soundGo);
		this.soundCheckpoint = sound(c, "checkpoint", this.soundCheckpoint);
		this.soundFinish = sound(c, "finish", this.soundFinish);
		this.soundOffTrack = sound(c, "off-track", this.soundOffTrack);
		this.soundJumpstart = sound(c, "jumpstart-penalty", this.soundJumpstart);
		this.soundMissedCheckpoint = sound(c, "missed-checkpoint", this.soundMissedCheckpoint);
		this.soundLap = sound(c, "lap", this.soundLap);

		this.hubCommand = c.getString("hub-command", "spawn");

		final String boat = c.getString("boat-type", "OAK_BOAT").toLowerCase(Locale.ROOT);
		final EntityType found = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(boat));

		this.boatType = found != null ? found : EntityType.OAK_BOAT;

		final long min = Math.max(0L, Math.round(c.getDouble("jumpstart-penalty.min-seconds", 4.5D) * 1000.0D));
		final long max = Math.max(0L, Math.round(c.getDouble("jumpstart-penalty.max-seconds", 12.5D) * 1000.0D));

		this.penaltyMinMs = Math.min(min, max);
		this.penaltyMaxMs = Math.max(min, max);

		this.lamps = Math.max(1, Math.min(15, c.getInt("start-lights.lamps", 5)));
		this.firstDelayTicks = Math.max(1, c.getInt("start-lights.first-delay-ticks", 20));
		this.intervalTicks = Math.max(1, c.getInt("start-lights.interval-ticks", 20));
		this.holdMinTicks = Math.max(0, c.getInt("start-lights.hold-min-ticks", 10));
		this.holdMaxTicks = Math.max(this.holdMinTicks, c.getInt("start-lights.hold-max-ticks", 50));
		this.lampOn = blockData(c.getString("start-lights.lamp-on-block"), Material.RED_CONCRETE);
		this.lampOff = blockData(c.getString("start-lights.lamp-off-block"), Material.BLACK_CONCRETE);

		this.finishDelayTicks = Math.max(0, c.getInt("finish-teleport-delay-ticks", 60));

		this.signalEnabled = c.getBoolean("start-signal.enabled", true);
		this.signalMode = "WORLD".equalsIgnoreCase(c.getString("start-signal.mode", "CLIENT")) ? SignalMode.WORLD : SignalMode.CLIENT;
		this.signalGo = blockData(c.getString("start-signal.go-block"), Material.LIME_CONCRETE);

		final String off = c.getString("start-signal.off-block", "");
		final Material offMaterial = off == null || off.isBlank() ? null : Material.matchMaterial(off);

		this.signalOff = offMaterial != null && offMaterial.isBlock() ? Bukkit.createBlockData(offMaterial) : null;
		this.signalDurationTicks = Math.max(1, (int) Math.round(c.getDouble("start-signal.duration-seconds", 5.0D) * 20.0D));

		this.offTrackEnabled = c.getBoolean("off-track.enabled", true);
		this.offTrackIntervalTicks = Math.max(1, c.getInt("off-track.check-interval-ticks", 2));
		this.offTrackGraceTicks = Math.max(0, c.getInt("off-track.grace-ticks", 10));
		this.offTrackAction = parseAction(c.getString("off-track.action", "PENALTY"));
		this.offTrackPenaltyMs = Math.max(0L, Math.round(c.getDouble("off-track.penalty-seconds", 3.0D) * 1000.0D));
		this.offTrackScanDepth = Math.max(1, Math.min(10, c.getInt("off-track.scan-depth", 3)));
		this.defaultTrackBlocks.clear();

		for (final String raw : c.getStringList("off-track.default-track-blocks")) {
			final Material material = Material.matchMaterial(raw);

			if (material != null && material.isBlock())
				this.defaultTrackBlocks.add(material);
		}

		this.eventAutoStartWhenFull = c.getBoolean("event.auto-start-when-full", true);
		this.eventStartDelayTicks = Math.max(0, c.getInt("event.start-delay-ticks", 60));

		this.partyMaxLimit = Math.max(2, c.getInt("party.max-limit", 10));
		this.partyDefaultLimit = Math.max(2, Math.min(this.partyMaxLimit, c.getInt("party.default-limit", 4)));
		this.partyInviteSeconds = Math.max(5, c.getInt("party.invite-timeout-seconds", 60));
		this.queueEnabled = c.getBoolean("queue.enabled", true);
		this.enforceMapPermissionOnCommand = c.getBoolean("map-permissions.enforce-on-join-command", false);

		this.fallbackEnabled = c.getBoolean("fallback.enabled", true);
		this.fallbackRandomArena = c.getBoolean("fallback.random-free-arena", true);
		this.fallbackWorlds.clear();

		final ConfigurationSection maps = c.getConfigurationSection("fallback.maps");

		if (maps != null)
			for (final String arena : maps.getKeys(false))
				this.fallbackWorlds.put(arena.toLowerCase(Locale.ROOT), new ArrayList<>(maps.getStringList(arena)));

		this.hotbarEnabled = c.getBoolean("hotbar.enabled", true);
		this.resetSlot = Math.max(0, Math.min(8, c.getInt("hotbar.reset-slot", 7)));
		this.hubSlot = Math.max(0, Math.min(8, c.getInt("hotbar.hub-slot", 8)));

		this.placeholderEmpty = c.getString("placeholders.empty", "-");

		this.chEnabled = c.getBoolean("captainhook.enabled", true);
		this.chGame = c.getString("captainhook.game", "formula-boat-racing").trim().toLowerCase(Locale.ROOT);
		this.chMinLapMs = Math.max(0L, Math.round(c.getDouble("captainhook.min-lap-seconds", 5.0D) * 1000.0D));
		this.chMaxAvgSpeed = Math.max(1.0D, c.getDouble("captainhook.max-avg-speed", 75.0D));
		this.chXpFinish = Math.max(0L, c.getLong("captainhook.pass-xp.finish", 5L));
		this.chXpWin = Math.max(0L, c.getLong("captainhook.pass-xp.win", 20L));
		this.chXpPodium = Math.max(0L, c.getLong("captainhook.pass-xp.podium", 10L));
		this.chXpPersonalBest = Math.max(0L, c.getLong("captainhook.pass-xp.personal-best", 10L));
		this.chBlocksPerXp = Math.max(0, c.getInt("captainhook.pass-xp.blocks-per-xp", 250));
		this.chDailyXpLimit = Math.max(0L, c.getLong("captainhook.pass-xp.daily-limit", 300L));

		this.loadCompetitive(c);

		this.tabListSessionOnly = c.getBoolean("tablist.session-only", false);

		this.rankingEnabled = c.getBoolean("ranking.enabled", true);
		this.rankingStart = Math.max(1, c.getInt("ranking.start-rating", 1000));
		this.rankingMin = Math.max(0, Math.min(this.rankingStart, c.getInt("ranking.min-rating", 100)));
		this.rankingK = Math.max(1.0D, c.getDouble("ranking.k-factor", 16.0D));
		this.rankingMinRacers = Math.max(2, c.getInt("ranking.min-racers", 2));
		this.rankingPartyRaces = c.getBoolean("ranking.party-races", true);
		this.rankingLeaderboardMinGames = Math.max(0, c.getInt("ranking.leaderboard-min-games", 5));
		this.rankingXpMultipliers.clear();

		final ConfigurationSection tiers = c.getConfigurationSection("ranking.xp-multipliers");

		if (tiers != null)
			for (final String rating : tiers.getKeys(false))
				try {
					this.rankingXpMultipliers.put(Integer.parseInt(rating.trim()), Math.max(0.0D, tiers.getDouble(rating)));

				} catch (final NumberFormatException ex) {
					Bukkit.getLogger().warning("[BoatRace] ranking.xp-multipliers: '" + rating + "' is not a number - skipped.");
				}
	}

	private void loadCompetitive(final FileConfiguration c) {
		this.compEnabled = c.getBoolean("competitive.enabled", false);
		this.compMinPlayers = Math.max(2, c.getInt("competitive.min-players", 4));
		this.compMaxTeamSize = Math.max(1, c.getInt("competitive.max-team-size", 4));
		this.compCountdownSeconds = Math.max(0, c.getInt("competitive.countdown-seconds", 30));
		this.compK = Math.max(1.0D, c.getDouble("competitive.k-factor", 48.0D));
		this.compLeaverPenalty = Math.max(0, c.getInt("competitive.leaver.rating-penalty", 15));
		this.compLeaverCooldownMs = Math.max(0L, Math.round(c.getDouble("competitive.leaver.cooldown-minutes", 5.0D) * 60_000L));
		this.compXpMultiplier = Math.max(0.0D, c.getDouble("competitive.xp-multiplier", 2.0D));
		this.compXpWin = Math.max(0L, c.getLong("competitive.pass-xp.win", 30L));
		this.compXpFullTeam = Math.max(0L, c.getLong("competitive.pass-xp.full-team", 10L));
		this.lineupPerMatch = Math.max(0.0D, c.getDouble("competitive.lineup-bonus.per-match", 0.10D));
		this.lineupMax = Math.max(0.0D, c.getDouble("competitive.lineup-bonus.max", 0.30D));
		this.lineupExpireMs = Math.max(60_000L, Math.round(c.getDouble("competitive.lineup-bonus.expire-minutes", 30.0D) * 60_000L));
		this.teamLuckPerms = c.getBoolean("competitive.luckperms.enabled", true);
		this.teamMetaKey = c.getString("competitive.luckperms.meta-key", "boatrace_team").trim();
		this.teamMetaFormat = c.getString("competitive.luckperms.format", "{chat}[{name}] ");

		final List<Integer> points = c.getIntegerList("competitive.points");

		this.teamPoints = points.isEmpty() ? new int[] { 10, 8, 6, 5, 4, 3, 2, 1 } : points.stream().mapToInt(value -> Math.max(0, value)).toArray();

		this.teamColors.clear();

		final ConfigurationSection colors = c.getConfigurationSection("competitive.colors");

		if (colors != null)
			for (final String id : colors.getKeys(false)) {
				final ConfigurationSection color = colors.getConfigurationSection(id);
				final String rawArmor = color != null ? color.getString("armor", id) : id;
				org.bukkit.Color armor = pl.kingdomcraft.boatrace.team.TeamColor.parseColor(rawArmor);

				if (armor == null) {
					Bukkit.getLogger().warning("[BoatRace] competitive.colors." + id + ".armor '" + rawArmor + "' is not a color (Bukkit name or #RRGGBB) - using GRAY.");
					armor = org.bukkit.Color.GRAY;
				}

				this.teamColors.add(new pl.kingdomcraft.boatrace.team.TeamColor(id, color != null ? color.getString("name", id) : id, armor,
						color != null ? color.getString("chat", "&f") : "&f"));
			}

		if (this.teamColors.size() < 2) {
			Bukkit.getLogger().warning("[BoatRace] competitive.colors needs at least 2 colors - using RED and BLUE.");
			this.teamColors.clear();
			this.teamColors.add(new pl.kingdomcraft.boatrace.team.TeamColor("RED", "Red", org.bukkit.Color.RED, "&c"));
			this.teamColors.add(new pl.kingdomcraft.boatrace.team.TeamColor("BLUE", "Blue", org.bukkit.Color.BLUE, "&9"));
		}
	}

	/**
	 * Jumpstart penalty scaled by how early the player moved: {@code litLamps} = 0 (no lamp lit yet) gives the
	 * maximum, all lamps lit gives the minimum; linear in between.
	 */
	public long penaltyFor(final int litLamps, final int totalLamps) {
		if (totalLamps <= 0)
			return this.penaltyMaxMs;

		final double progress = Math.max(0.0D, Math.min(1.0D, (double) litLamps / totalLamps));

		return Math.round(this.penaltyMaxMs - (this.penaltyMaxMs - this.penaltyMinMs) * progress);
	}

	/** Worlds with copies of the arena (from fallback.maps), never null. */
	public List<String> fallbackWorldsOf(final String arena) {
		return this.fallbackWorlds.getOrDefault(arena.toLowerCase(Locale.ROOT), List.of());
	}

	/**
	 * Reads Sounds.<name>: sound / volume / pitch. "sound: NONE" (or "") disables it. A name without '.' or ':' is a
	 * Bukkit Sound constant, anything else a namespaced sound key (resource pack sounds work too).
	 */
	private static SoundSpec sound(final FileConfiguration c, final String name, final SoundSpec fallback) {
		final String base = "Sounds." + name;

		if (!c.isConfigurationSection(base))
			return fallback;

		final String raw = c.getString(base + ".sound", "").trim();
		final float volume = (float) c.getDouble(base + ".volume", fallback.volume());
		final float pitch = (float) c.getDouble(base + ".pitch", fallback.pitch());

		if (raw.isEmpty() || "NONE".equalsIgnoreCase(raw))
			return new SoundSpec(null, volume, pitch);

		if (raw.indexOf('.') >= 0 || raw.indexOf(':') >= 0)
			return new SoundSpec(raw.toLowerCase(Locale.ROOT), volume, pitch);

		try {
			final Object constant = org.bukkit.Sound.class.getField(raw.toUpperCase(Locale.ROOT)).get(null);

			if (constant instanceof final org.bukkit.Sound sound)
				return new SoundSpec(sound.key().asString(), volume, pitch);

		} catch (final ReflectiveOperationException ignored) {
		}

		Bukkit.getLogger().warning("[BoatRace] Unknown sound '" + raw + "' in Sounds." + name + " - using the default.");
		return new SoundSpec(fallback.key(), volume, pitch);
	}

	private static OffTrackAction parseAction(final String raw) {
		try {
			return OffTrackAction.valueOf(raw == null ? "PENALTY" : raw.trim().toUpperCase(Locale.ROOT));

		} catch (final IllegalArgumentException ex) {
			return OffTrackAction.PENALTY;
		}
	}

	private static BlockData blockData(final String name, final Material fallback) {
		Material material = name == null ? null : Material.matchMaterial(name);

		if (material == null || !material.isBlock())
			material = fallback;

		return Bukkit.createBlockData(material);
	}
}
