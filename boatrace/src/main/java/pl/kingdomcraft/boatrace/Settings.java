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

	// --- grid / events ---
	public int maxGridPositions = 10;
	public boolean eventAutoStartWhenFull = true;
	public int eventStartDelayTicks = 60;

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

		this.maxGridPositions = Math.max(1, Math.min(64, c.getInt("grid.max-positions", 10)));
		this.eventAutoStartWhenFull = c.getBoolean("event.auto-start-when-full", true);
		this.eventStartDelayTicks = Math.max(0, c.getInt("event.start-delay-ticks", 60));

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
