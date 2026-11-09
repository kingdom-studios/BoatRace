package pl.kingdomcraft.boatrace.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** A world-independent location (world is stored by name so arenas survive world (un)loading). */
public record Point(String world, double x, double y, double z, float yaw, float pitch) {

	public static Point of(final Location loc) {
		return new Point(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
	}

	/** @return the location or null when the world is not loaded */
	public Location toLocation() {
		final World bukkitWorld = Bukkit.getWorld(this.world);

		return bukkitWorld == null ? null : new Location(bukkitWorld, this.x, this.y, this.z, this.yaw, this.pitch);
	}

	public void save(final ConfigurationSection section) {
		section.set("world", this.world);
		section.set("x", this.x);
		section.set("y", this.y);
		section.set("z", this.z);
		section.set("yaw", (double) this.yaw);
		section.set("pitch", (double) this.pitch);
	}

	public static Point load(final ConfigurationSection section) {
		if (section == null || !section.isString("world"))
			return null;

		return new Point(
				section.getString("world"),
				section.getDouble("x"),
				section.getDouble("y"),
				section.getDouble("z"),
				(float) section.getDouble("yaw"),
				(float) section.getDouble("pitch"));
	}
}
