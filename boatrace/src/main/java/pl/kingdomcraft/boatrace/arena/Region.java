package pl.kingdomcraft.boatrace.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * An axis-aligned cuboid of blocks (WorldEdit-style pos1 / pos2 selection), inclusive on both ends.
 * Used for the start line, the finish line, checkpoint zones and the F1 lamps.
 *
 * Detection is slightly generous vertically: a boat floating on / sliding over the selected blocks
 * (up to {@link #VERTICAL_TOLERANCE} blocks above their top face) still counts as inside.
 */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

	/** How far above the top face of the selected blocks a boat is still considered inside. */
	public static final double VERTICAL_TOLERANCE = 1.5D;

	public static Region of(final Location a, final Location b) {
		return new Region(a.getWorld().getName(),
				Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
				Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
	}

	public static Region ofBlock(final Location block) {
		return of(block, block);
	}

	/** The same cuboid in another world (fallback copies of a track). */
	public Region withWorld(final String otherWorld) {
		return new Region(otherWorld, this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
	}

	public int sizeX() {
		return this.maxX - this.minX + 1;
	}

	public int sizeY() {
		return this.maxY - this.minY + 1;
	}

	public int sizeZ() {
		return this.maxZ - this.minZ + 1;
	}

	public long volume() {
		return (long) this.sizeX() * this.sizeY() * this.sizeZ();
	}

	public String describe() {
		return "(" + this.minX + ", " + this.minY + ", " + this.minZ + ") -> (" + this.maxX + ", " + this.maxY + ", " + this.maxZ + ") "
				+ this.sizeX() + "x" + this.sizeY() + "x" + this.sizeZ();
	}

	private boolean sameWorld(final Location location) {
		return location.getWorld() != null && location.getWorld().getName().equals(this.world);
	}

	/** True when the location lies inside the (tolerance-expanded) box. */
	public boolean contains(final Location location) {
		return this.containsPadded(location, 0.5D, VERTICAL_TOLERANCE);
	}

	/** True when the movement segment from -> to touches the box (fast boats cannot skip a zone between two ticks). */
	public boolean intersects(final Location from, final Location to) {
		return this.sweep(from, to, 0.5D, VERTICAL_TOLERANCE);
	}

	/** Like {@link #contains}, but exactly the selected blocks - no vertical tolerance (reset zones). */
	public boolean containsExact(final Location location) {
		return this.containsPadded(location, 0.0D, 0.0D);
	}

	/** Like {@link #intersects}, but exactly the selected blocks - no vertical tolerance (reset zones). */
	public boolean intersectsExact(final Location from, final Location to) {
		return this.sweep(from, to, 0.0D, 0.0D);
	}

	private boolean containsPadded(final Location location, final double padBelow, final double padAbove) {
		if (!this.sameWorld(location))
			return false;

		final double x = location.getX(), y = location.getY(), z = location.getZ();

		return x >= this.minX && x < this.maxX + 1
				&& y >= this.minY - padBelow && y < this.maxY + 1 + padAbove
				&& z >= this.minZ && z < this.maxZ + 1;
	}

	private boolean sweep(final Location from, final Location to, final double padBelow, final double padAbove) {
		if (!this.sameWorld(to))
			return false;

		if (from.getWorld() != to.getWorld())
			return this.containsPadded(to, padBelow, padAbove);

		final double[] start = { from.getX(), from.getY(), from.getZ() };
		final double[] delta = { to.getX() - start[0], to.getY() - start[1], to.getZ() - start[2] };
		final double[] low = { this.minX, this.minY - padBelow, this.minZ };
		final double[] high = { this.maxX + 1, this.maxY + 1 + padAbove, this.maxZ + 1 };

		double tMin = 0.0D, tMax = 1.0D;

		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(delta[axis]) < 1.0E-9D) {
				if (start[axis] < low[axis] || start[axis] >= high[axis])
					return false;

				continue;
			}

			double t1 = (low[axis] - start[axis]) / delta[axis];
			double t2 = (high[axis] - start[axis]) / delta[axis];

			if (t1 > t2) {
				final double swap = t1;
				t1 = t2;
				t2 = swap;
			}

			tMin = Math.max(tMin, t1);
			tMax = Math.min(tMax, t2);

			if (tMin > tMax)
				return false;
		}

		return true;
	}

	/** @return every block location of the region, or an empty list when the world is not loaded */
	public List<Location> blocks() {
		final List<Location> result = new ArrayList<>();
		final World bukkitWorld = Bukkit.getWorld(this.world);

		if (bukkitWorld == null)
			return result;

		for (int x = this.minX; x <= this.maxX; x++)
			for (int y = this.minY; y <= this.maxY; y++)
				for (int z = this.minZ; z <= this.maxZ; z++)
					result.add(new Location(bukkitWorld, x, y, z));

		return result;
	}

	/**
	 * Splits the selection into {@code parts} equal segments along its longest horizontal axis, ordered from
	 * {@code first} towards the other end. Used to turn one selection into a row of F1 lamps.
	 *
	 * @return null when the longest axis is shorter than {@code parts}
	 */
	public List<Region> split(final int parts, final Location first) {
		final boolean alongX = this.sizeX() >= this.sizeZ();
		final int length = alongX ? this.sizeX() : this.sizeZ();

		if (parts < 1 || length < parts)
			return null;

		final List<Region> result = new ArrayList<>();
		final int origin = alongX ? this.minX : this.minZ;

		for (int i = 0; i < parts; i++) {
			final int from = origin + (int) Math.floor((double) i * length / parts);
			final int to = origin + (int) Math.floor((double) (i + 1) * length / parts) - 1;

			result.add(alongX
					? new Region(this.world, from, this.minY, this.minZ, to, this.maxY, this.maxZ)
					: new Region(this.world, this.minX, this.minY, from, this.maxX, this.maxY, to));
		}

		// pos1 decides which end is lamp #1
		final int firstCoord = alongX ? first.getBlockX() : first.getBlockZ();
		final int lastCoord = origin + length - 1;

		if (Math.abs(firstCoord - lastCoord) < Math.abs(firstCoord - origin))
			Collections.reverse(result);

		return result;
	}

	public void save(final ConfigurationSection section) {
		section.set("world", this.world);
		section.set("min", this.minX + "," + this.minY + "," + this.minZ);
		section.set("max", this.maxX + "," + this.maxY + "," + this.maxZ);
	}

	/**
	 * Loads a region. Arenas saved by older versions stored single points (world/x/y/z); those are migrated into a
	 * cube of {@code legacyRadius} blocks around the point.
	 */
	public static Region load(final ConfigurationSection section, final int legacyRadius) {
		if (section == null || !section.isString("world"))
			return null;

		final String worldName = section.getString("world");

		if (section.isString("min") && section.isString("max")) {
			final int[] min = parse(section.getString("min"));
			final int[] max = parse(section.getString("max"));

			if (min == null || max == null)
				return null;

			return new Region(worldName,
					Math.min(min[0], max[0]), Math.min(min[1], max[1]), Math.min(min[2], max[2]),
					Math.max(min[0], max[0]), Math.max(min[1], max[1]), Math.max(min[2], max[2]));
		}

		if (section.contains("x")) {
			final int x = (int) Math.floor(section.getDouble("x"));
			final int y = (int) Math.floor(section.getDouble("y"));
			final int z = (int) Math.floor(section.getDouble("z"));

			return new Region(worldName, x - legacyRadius, y - 1, z - legacyRadius, x + legacyRadius, y + 1, z + legacyRadius);
		}

		return null;
	}

	private static int[] parse(final String raw) {
		final String[] parts = raw.split(",");

		if (parts.length != 3)
			return null;

		try {
			return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()) };

		} catch (final NumberFormatException ex) {
			return null;
		}
	}
}
