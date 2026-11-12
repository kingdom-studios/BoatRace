package pl.kingdomcraft.captainhook.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A YAML file of the plugin folder, copied from the jar on first use. Getters name the file and the full key in
 * their errors, so a typo in a configuration file is easy to find.
 */
public final class YamlFile {

	private final String name;
	private final String path;
	private final Map<String, Object> values;

	private YamlFile(final String name, final String path, final Map<String, Object> values) {
		this.name = name;
		this.path = path;
		this.values = values;
	}

	/**
	 * Loads the file from the data directory, creating it from the default bundled in the jar when missing.
	 */
	public static YamlFile load(final Path dataDirectory, final String name) throws ConfigException {
		final Path file = dataDirectory.resolve(name);

		try {
			if (Files.notExists(file)) {
				Files.createDirectories(dataDirectory);

				try (InputStream in = YamlFile.class.getResourceAsStream("/" + name)) {
					if (in == null)
						throw new ConfigException(name + " is missing from the plugin jar");

					Files.copy(in, file);
				}
			}

			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				return parse(name, reader);
			}

		} catch (final IOException ex) {
			throw new ConfigException("Could not read " + name + ": " + ex.getMessage(), ex);
		}
	}

	public static YamlFile parse(final String name, final Reader reader) throws ConfigException {
		try {
			final Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);

			if (root == null)
				return new YamlFile(name, "", Collections.emptyMap());

			if (!(root instanceof Map))
				throw new ConfigException(name + " must be a YAML mapping");

			return new YamlFile(name, "", toStringKeys((Map<?, ?>) root));

		} catch (final RuntimeException ex) {
			throw new ConfigException(name + " is not valid YAML: " + ex.getMessage(), ex);
		}
	}

	private static Map<String, Object> toStringKeys(final Map<?, ?> map) {
		final Map<String, Object> copy = new LinkedHashMap<>();

		for (final Map.Entry<?, ?> entry : map.entrySet())
			copy.put(String.valueOf(entry.getKey()), entry.getValue());

		return copy;
	}

	/**
	 * @return the keys of this section in file order
	 */
	public List<String> keys() {
		return new ArrayList<>(this.values.keySet());
	}

	public boolean has(final String key) {
		return this.values.get(key) != null;
	}

	/**
	 * @return the nested section, or an empty one when the key is missing
	 */
	public YamlFile section(final String key) throws ConfigException {
		final Object value = this.values.get(key);

		if (value == null)
			return new YamlFile(this.name, this.pathOf(key), Collections.emptyMap());

		if (!(value instanceof Map))
			throw this.invalid(key, "must be a section");

		return new YamlFile(this.name, this.pathOf(key), toStringKeys((Map<?, ?>) value));
	}

	/**
	 * @return the sections of a list of mappings (e.g. "pools: daily: [ {key: a, …}, {key: b, …} ]"), empty if the key is missing
	 */
	public List<YamlFile> getSectionList(final String key) throws ConfigException {
		final Object value = this.values.get(key);
		final List<YamlFile> sections = new ArrayList<>();

		if (value == null)
			return sections;

		if (!(value instanceof List))
			throw this.invalid(key, "must be a list");

		int index = 0;

		for (final Object element : (List<?>) value) {
			if (!(element instanceof Map))
				throw this.invalid(key + "[" + index + "]", "must be a section");

			sections.add(new YamlFile(this.name, this.pathOf(key) + "[" + index + "]", toStringKeys((Map<?, ?>) element)));
			index++;
		}

		return sections;
	}

	public String getString(final String key, final String def) throws ConfigException {
		final Object value = this.values.get(key);

		if (value == null)
			return def;

		if (value instanceof Map || value instanceof List)
			throw this.invalid(key, "must be a text");

		return String.valueOf(value);
	}

	public String requireString(final String key) throws ConfigException {
		final String value = this.getString(key, null);

		if (value == null || value.isBlank())
			throw this.invalid(key, "is required");

		return value;
	}

	public long getLong(final String key, final long def) throws ConfigException {
		final Object value = this.values.get(key);

		if (value == null)
			return def;

		if (value instanceof Integer || value instanceof Long)
			return ((Number) value).longValue();

		throw this.invalid(key, "must be a whole number");
	}

	public boolean getBoolean(final String key, final boolean def) throws ConfigException {
		final Object value = this.values.get(key);

		if (value == null)
			return def;

		if (value instanceof Boolean)
			return (Boolean) value;

		throw this.invalid(key, "must be true or false");
	}

	/**
	 * @return the list as texts, a single value counts as a list of one, a missing key as an empty list
	 */
	public List<String> getStringList(final String key) throws ConfigException {
		final Object value = this.values.get(key);
		final List<String> list = new ArrayList<>();

		if (value == null)
			return list;

		if (value instanceof Map)
			throw this.invalid(key, "must be a list");

		for (final Object element : value instanceof List ? (List<?>) value : Collections.singletonList(value)) {
			if (element == null || element instanceof Map || element instanceof List)
				throw this.invalid(key, "must be a list of texts");

			list.add(String.valueOf(element));
		}

		return list;
	}

	/**
	 * @return the raw value, e.g. a java.util.Date for an unquoted YAML date, or null
	 */
	public Object getRaw(final String key) {
		return this.values.get(key);
	}

	public ConfigException invalid(final String key, final String problem) {
		return new ConfigException(this.name + ": " + this.pathOf(key) + " " + problem);
	}

	private String pathOf(final String key) {
		return this.path.isEmpty() ? key : this.path + "." + key;
	}
}
