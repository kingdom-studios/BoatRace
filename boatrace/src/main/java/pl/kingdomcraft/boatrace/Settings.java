package pl.kingdomcraft.boatrace;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.Locale;

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

	private static BlockData blockData(final String name, final Material fallback) {
		Material material = name == null ? null : Material.matchMaterial(name);

		if (material == null || !material.isBlock())
			material = fallback;

		return Bukkit.createBlockData(material);
	}
}
