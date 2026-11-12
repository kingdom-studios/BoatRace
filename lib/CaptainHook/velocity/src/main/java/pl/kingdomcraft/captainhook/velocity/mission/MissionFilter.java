package pl.kingdomcraft.captainhook.velocity.mission;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;

/**
 * Optional conditions of a mission; an empty set means "any". Names are compared case-insensitively.
 *
 * @param servers   Velocity names of the servers the event happened on
 * @param worlds    world names
 * @param materials block or item types (BLOCK_BREAK, ITEM_CRAFT)
 * @param entities  entity types (MOB_KILL)
 * @param game      minigame (MINIGAME_STAT), null for any
 * @param stat      minigame stat (MINIGAME_STAT), null for any
 * @param minValue  MINIGAME_STAT only, null otherwise: count 1 for every report of the stat (any operation: add, set,
 *                  max, min) whose reported value is at least this, instead of adding up the counter. "Finish a race
 *                  with an average speed of 5 blocks/s" = stat top_avg_speed, min-value 500
 */
public record MissionFilter(Set<String> servers, Set<String> worlds, Set<String> materials, Set<String> entities, String game, String stat, Long minValue) {

	public static final MissionFilter ANY = new MissionFilter(Set.of(), Set.of(), Set.of(), Set.of(), null, null, null);

	public MissionFilter {
		servers = lower(servers);
		worlds = lower(worlds);
		materials = lower(materials);
		entities = lower(entities);
		game = game == null || game.isBlank() ? null : game.toLowerCase(Locale.ROOT);
		stat = stat == null || stat.isBlank() ? null : stat.toLowerCase(Locale.ROOT);
	}

	private static Set<String> lower(final Set<String> values) {
		return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * What happened, to be compared with a filter. Null fields are unknown.
	 */
	public record Event(String server, String world, String material, String entity, String game, String stat) {

		public static Event ofServer(final String server) {
			return new Event(server, null, null, null, null, null);
		}
	}

	/**
	 * How much a reported minigame stat adds to a mission with this filter (MINIGAME_STAT).
	 *
	 * @param counter true for an "add" report
	 * @param value   the reported value (for add: the amount)
	 */
	public long minigameDelta(final boolean counter, final long value) {
		if (this.minValue == null)
			return counter ? value : 0L;

		return value >= this.minValue ? 1L : 0L;
	}

	public boolean matches(final Event event) {
		return contains(this.servers, event.server()) && contains(this.worlds, event.world()) && contains(this.materials, event.material())
				&& contains(this.entities, event.entity()) && (this.game == null || this.game.equalsIgnoreCase(event.game()))
				&& (this.stat == null || this.stat.equalsIgnoreCase(event.stat()));
	}

	private static boolean contains(final Set<String> allowed, final String value) {
		return allowed.isEmpty() || value != null && allowed.contains(value.toLowerCase(Locale.ROOT));
	}

	public static MissionFilter parse(final YamlFile section) throws ConfigException {
		return new MissionFilter(
				new TreeSet<>(section.getStringList("server")),
				new TreeSet<>(section.getStringList("world")),
				new TreeSet<>(section.getStringList("material")),
				new TreeSet<>(section.getStringList("entity")),
				section.getString("game", null),
				section.getString("stat", null),
				section.has("min-value") ? section.getLong("min-value", 0) : null);
	}

	/**
	 * Stored in ch_mission_assignments.filter_json.
	 */
	public JsonObject toJson() {
		final JsonObject json = new JsonObject();

		put(json, "server", this.servers);
		put(json, "world", this.worlds);
		put(json, "material", this.materials);
		put(json, "entity", this.entities);

		if (this.game != null)
			json.addProperty("game", this.game);

		if (this.stat != null)
			json.addProperty("stat", this.stat);

		if (this.minValue != null)
			json.addProperty("minValue", this.minValue);

		return json;
	}

	private static void put(final JsonObject json, final String key, final Set<String> values) {
		if (values.isEmpty())
			return;

		final JsonArray array = new JsonArray();

		values.forEach(array::add);
		json.add(key, array);
	}

	public static MissionFilter fromJson(final JsonObject json) {
		return new MissionFilter(read(json, "server"), read(json, "world"), read(json, "material"), read(json, "entity"),
				Json.getString(json, "game", null), Json.getString(json, "stat", null),
				json.has("minValue") ? Json.getLong(json, "minValue", 0) : null);
	}

	private static Set<String> read(final JsonObject json, final String key) {
		final Set<String> values = new TreeSet<>();

		Json.getArray(json, key).forEach(element -> {
			if (element.isJsonPrimitive())
				values.add(element.getAsString());
		});

		return values;
	}
}
