package pl.kingdomcraft.boatrace.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** The parsed guis.yml. Plain parsing, no server needed (materials are resolved through the given function). */
public final class GuiConfig {

	/** Status texts of a map item ({status}), by {@code RaceManager.ArenaStatus} name in kebab case. */
	public final Map<String, String> status = new HashMap<>();
	public String accessAllowed = "&aYou can play this map";
	public String accessDenied = "&cYou need &f{permission}";
	/** replaces the icon of a map in maintenance; null = keep the icon */
	public Material maintenanceMaterial = Material.BARRIER;
	public String defaultMenu = "unranked";
	/** lower-case id -> menu, in file order */
	public final Map<String, GuiMenu> menus = new LinkedHashMap<>();

	public GuiConfig() {
		this.status.put("free", "&aFree");
		this.status.put("busy", "&cBusy &7- you will be queued");
		this.status.put("event", "&eEvent open - join now");
		this.status.put("not-ready", "&7Not ready");
		this.status.put("maintenance", "&cClosed - maintenance");
	}

	/**
	 * @param materials resolves a material name to an item material, null when it is unknown / not an item
	 * @param warn      receives every problem (the offending entry is skipped or replaced by a default)
	 */
	public static GuiConfig parse(final ConfigurationSection root, final Function<String, Material> materials, final Consumer<String> warn) {
		final GuiConfig config = new GuiConfig();

		config.defaultMenu = root.getString("default-menu", "unranked").trim().toLowerCase(Locale.ROOT);

		final ConfigurationSection status = root.getConfigurationSection("status");

		if (status != null)
			for (final String key : status.getKeys(false))
				config.status.put(key.toLowerCase(Locale.ROOT), status.getString(key, ""));

		config.accessAllowed = root.getString("access.allowed", config.accessAllowed);
		config.accessDenied = root.getString("access.denied", config.accessDenied);

		final String maintenance = root.getString("maintenance.material", "BARRIER");

		config.maintenanceMaterial = maintenance == null || maintenance.isBlank() ? null : materials.apply(maintenance.trim());

		final ConfigurationSection menus = root.getConfigurationSection("menus");

		if (menus != null)
			for (final String id : menus.getKeys(false)) {
				final ConfigurationSection section = menus.getConfigurationSection(id);

				if (section != null)
					config.menus.put(id.toLowerCase(Locale.ROOT), parseMenu(id.toLowerCase(Locale.ROOT), section, materials, text -> warn.accept("menus." + id + ": " + text)));
			}

		if (config.menus.isEmpty())
			warn.accept("guis.yml has no menus - /bre gui has nothing to open");

		else if (!config.menus.containsKey(config.defaultMenu)) {
			warn.accept("default-menu '" + config.defaultMenu + "' does not exist - using '" + config.menus.keySet().iterator().next() + "'");
			config.defaultMenu = config.menus.keySet().iterator().next();
		}

		return config;
	}

	private static GuiMenu parseMenu(final String id, final ConfigurationSection section, final Function<String, Material> materials, final Consumer<String> warn) {
		final int lapsMin = Math.max(1, section.getInt("laps.min", 1));
		final List<GuiItem> items = new ArrayList<>();
		final ConfigurationSection itemsSection = section.getConfigurationSection("items");

		if (itemsSection != null)
			for (final String key : itemsSection.getKeys(false)) {
				final GuiItem item = parseItem(key, itemsSection.getConfigurationSection(key), " ", materials, text -> warn.accept("items." + key + ": " + text));

				if (item != null)
					items.add(item);
			}

		GuiMenu.AutoMaps autoMaps = null;
		final ConfigurationSection auto = section.getConfigurationSection("auto-maps");

		if (auto != null && auto.getBoolean("show", true)) {
			String filter = auto.getString("filter", "all").trim().toLowerCase(Locale.ROOT);

			if (!filter.equals("all") && !filter.equals("competitive")) {
				warn.accept("auto-maps.filter must be 'all' or 'competitive', not '" + filter + "' - using 'all'");
				filter = "all";
			}

			final GuiItem template = parseItem("auto-maps", auto, "&b&l{arena}", materials, text -> warn.accept("auto-maps: " + text));

			if (template != null)
				autoMaps = new GuiMenu.AutoMaps(Math.max(0, auto.getInt("start-slot", 10)), filter, template);
		}

		final ConfigurationSection fillerSection = section.getConfigurationSection("filler");
		final GuiItem filler = fillerSection != null && fillerSection.getBoolean("enabled", false)
				? parseItem("filler", fillerSection, " ", materials, text -> warn.accept("filler: " + text))
				: null;

		final String permission = section.getString("permission");

		return new GuiMenu(id,
				section.getString("title", "&8Menu"),
				Math.max(1, Math.min(6, section.getInt("rows", 3))),
				lapsMin,
				Math.max(lapsMin, section.getInt("laps.max", 5)),
				permission == null || permission.isBlank() ? null : permission.trim(),
				section.getBoolean("requires-competitive", false),
				items,
				autoMaps,
				filler);
	}

	/** @return null when the section is missing or the item is switched off ({@code enabled: false}) */
	static GuiItem parseItem(final String id, final ConfigurationSection section, final String defaultName, final Function<String, Material> materials, final Consumer<String> warn) {
		if (section == null || !section.getBoolean("enabled", true))
			return null;

		final String materialName = section.getString("material", "STONE");
		Material material = materials.apply(materialName.trim());

		if (material == null) {
			warn.accept("unknown material '" + materialName + "' - using STONE");
			material = Material.STONE;
		}

		final List<Integer> slots = new ArrayList<>();

		if (section.isList("slots"))
			slots.addAll(section.getIntegerList("slots"));
		else if (section.contains("slot"))
			slots.add(section.getInt("slot"));

		final String arena = section.getString("arena");
		final String permission = section.getString("permission");

		return new GuiItem(id, material, section.getString("name", defaultName), section.getStringList("lore"), slots,
				arena == null || arena.isBlank() ? null : arena.trim(),
				permission == null || permission.isBlank() ? null : permission.trim(),
				section.getBoolean("requires-competitive", false),
				parseActions(section.get("actions"), warn));
	}

	/**
	 * {@code actions: close} / {@code actions: [a, b]} = for every click;
	 * {@code actions: {LEFT: [...], SHIFT: join-map, default: close}} = per click type (SHIFT = both shift clicks,
	 * default / any / all = every other click). A list entry is {@code "name argument"} or a one-entry map {@code name: argument}.
	 */
	static Map<String, List<GuiAction>> parseActions(final Object node, final Consumer<String> warn) {
		final Map<String, List<GuiAction>> result = new LinkedHashMap<>();

		if (node == null)
			return result;

		if (node instanceof final ConfigurationSection section) {
			for (final String key : section.getKeys(false)) {
				final List<GuiAction> list = parseActionList(section.get(key), warn);
				final String click = key.trim().toUpperCase(Locale.ROOT).replace('-', '_');

				if (click.equals("DEFAULT") || click.equals("ANY") || click.equals("ALL") || click.equals("*"))
					result.put(GuiItem.ANY, list);

				else if (click.equals("SHIFT")) {
					result.putIfAbsent("SHIFT_LEFT", list);
					result.putIfAbsent("SHIFT_RIGHT", list);

				} else
					result.put(click, list);
			}

			return result;
		}

		result.put(GuiItem.ANY, parseActionList(node, warn));

		return result;
	}

	private static List<GuiAction> parseActionList(final Object node, final Consumer<String> warn) {
		final List<GuiAction> list = new ArrayList<>();

		if (node instanceof final List<?> entries) {
			for (final Object entry : entries)
				addAction(list, entry, warn);

		} else
			addAction(list, node, warn);

		return list;
	}

	private static void addAction(final List<GuiAction> list, final Object entry, final Consumer<String> warn) {
		final String text;

		if (entry instanceof final Map<?, ?> map && map.size() == 1) {
			final Map.Entry<?, ?> only = map.entrySet().iterator().next();

			text = only.getKey() + " " + only.getValue();

		} else if (entry instanceof final ConfigurationSection section && section.getKeys(false).size() == 1) {
			final String key = section.getKeys(false).iterator().next();

			text = key + " " + section.get(key);

		} else if (entry instanceof String || entry instanceof Number || entry instanceof Boolean)
			text = String.valueOf(entry);

		else {
			warn.accept("an action must be a text like 'join-map monaco' (got " + entry + ")");
			return;
		}

		final GuiAction action = GuiAction.parse(text, warn);

		if (action != null)
			list.add(action);
	}
}
