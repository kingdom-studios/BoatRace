package pl.kingdomcraft.captainhook.protocol;

import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Lenient readers of message payloads: a missing, null or mistyped field yields the default, so receivers
 * keep working when a newer sender adds or changes fields.
 */
public final class Json {

	private Json() {
	}

	public static String getString(final JsonObject object, final String key, final String def) {
		final JsonElement element = object.get(key);

		return element != null && element.isJsonPrimitive() ? element.getAsString() : def;
	}

	public static long getLong(final JsonObject object, final String key, final long def) {
		final JsonElement element = object.get(key);

		try {
			return element != null && element.isJsonPrimitive() ? element.getAsLong() : def;

		} catch (final NumberFormatException ex) {
			return def;
		}
	}

	public static int getInt(final JsonObject object, final String key, final int def) {
		final long value = getLong(object, key, def);

		return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? def : (int) value;
	}

	public static boolean getBoolean(final JsonObject object, final String key, final boolean def) {
		final JsonElement element = object.get(key);

		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() ? element.getAsBoolean() : def;
	}

	/**
	 * @return the UUID, or null when the field is missing or not a UUID
	 */
	public static UUID getUuid(final JsonObject object, final String key) {
		final String value = getString(object, key, null);

		try {
			return value != null ? UUID.fromString(value) : null;

		} catch (final IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * @return the nested object, or an empty one
	 */
	public static JsonObject getObject(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);

		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	/**
	 * @return true if the field holds an object (not null and not missing)
	 */
	public static boolean hasObject(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);

		return element != null && element.isJsonObject();
	}

	/**
	 * @return the nested array, or an empty one
	 */
	public static JsonArray getArray(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);

		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}
}
