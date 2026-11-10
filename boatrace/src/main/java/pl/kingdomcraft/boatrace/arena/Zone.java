package pl.kingdomcraft.boatrace.arena;

import java.util.Collections;
import java.util.List;

import org.bukkit.Location;

/**
 * A line made of one or more independent cuboid segments (e.g. a curved, slanted or arc-shaped start/finish line
 * built from several pos1/pos2 selections). Being in / passing through ANY segment counts as being on the line.
 *
 * The zone is a live view over the segment list it was created with - no copy, no allocation per check.
 */
public final class Zone {

	private final List<Region> segments;

	public Zone(final List<Region> segments) {
		this.segments = segments;
	}

	public static Zone of(final Region region) {
		return new Zone(Collections.singletonList(region));
	}

	public List<Region> segments() {
		return Collections.unmodifiableList(this.segments);
	}

	public boolean isEmpty() {
		return this.segments.isEmpty();
	}

	/** True when the location is inside any segment. */
	public boolean contains(final Location location) {
		for (final Region segment : this.segments)
			if (segment.contains(location))
				return true;

		return false;
	}

	/** True when the movement from -> to touches any segment. */
	public boolean intersects(final Location from, final Location to) {
		for (final Region segment : this.segments)
			if (segment.intersects(from, to))
				return true;

		return false;
	}

	/** "2 segments: #1 (...), #2 (...)" or the single region's description. */
	public String describe() {
		if (this.segments.size() == 1)
			return this.segments.get(0).describe();

		final StringBuilder builder = new StringBuilder(this.segments.size() + " segments");

		for (int i = 0; i < this.segments.size(); i++)
			builder.append(i == 0 ? ": " : ", ").append('#').append(i + 1).append(' ').append(this.segments.get(i).describe());

		return builder.toString();
	}
}
