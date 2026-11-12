package pl.kingdomcraft.boatrace.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * The map menu (/bre gui), configured entirely in gui.yml: title, size, item slots / materials / names / lore.
 *
 * Maps need the permission boatrace.map.&lt;arena name in lower case&gt;. Clicking a map you may not play does nothing
 * but send gui.no-permission (messages.yml). Configurable clicks change the lap count of the map (default: left +1,
 * right -1, between 1 and 5) and join it (default: shift-click or middle click) - the lap count is also used by the
 * race that is started.
 */
public final class GuiManager implements Listener {

	private enum Type {
		MAP, RANDOM, CLOSE, TEAM, NONE
	}

	private record Action(Type type, String arena) {
	}

	/** An item from gui.yml. */
	private record Icon(Material material, String name, List<String> lore, List<Integer> slots) {
	}

	private static final class MenuHolder implements InventoryHolder {

		final Map<Integer, Action> actions = new HashMap<>();
		Inventory inventory;

		@Override
		public Inventory getInventory() {
			return this.inventory;
		}
	}

	private final JavaPlugin plugin;
	private final ArenaManager arenas;
	private final RaceManager races;

	/** player -> arena name (lower case) -> chosen laps */
	private final Map<UUID, Map<String, Integer>> chosenLaps = new HashMap<>();

	// --- gui.yml ---
	private String title = "&8Select a map";
	private int size = 27;
	private int lapsMin = 1;
	private int lapsMax = 5;
	private Set<String> clickIncrease = Set.of("LEFT");
	private Set<String> clickDecrease = Set.of("RIGHT");
	private Set<String> clickJoin = Set.of("SHIFT_LEFT", "SHIFT_RIGHT", "MIDDLE");
	private final Map<RaceManager.ArenaStatus, String> statusText = new HashMap<>();
	/** replaces the icon material of a map in maintenance; null = keep the icon */
	private Material maintenanceMaterial = Material.BARRIER;
	private String accessAllowed = "&aYou can play this map";
	private String accessDenied = "&cYou need &f{permission}";
	private final Map<String, Icon> mapIcons = new LinkedHashMap<>();
	private boolean showUnlisted = true;
	private int unlistedStartSlot = 10;
	private Icon unlisted;
	private Icon random;
	private Icon close;
	private Icon filler;
	private final List<Icon> decorations = new ArrayList<>();

	/** A button of gui.yml "competitive-buttons": join the competitive lobby of one arena or of any (arena null). */
	private record CompButton(Icon icon, String arena) {
	}

	private final List<CompButton> compButtons = new ArrayList<>();

	public GuiManager(final JavaPlugin plugin, final ArenaManager arenas, final RaceManager races) {
		this.plugin = plugin;
		this.arenas = arenas;
		this.races = races;
	}

	/** The permission needed to play (and open from the menu) an arena. */
	public static String permission(final Arena arena) {
		return "boatrace.map." + arena.getName().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------------------------------
	// gui.yml
	// ------------------------------------------------------------------------------------------

	public void load() {
		final File file = new File(this.plugin.getDataFolder(), "gui.yml");

		if (!file.exists())
			this.plugin.saveResource("gui.yml", false);

		final YamlConfiguration c = YamlConfiguration.loadConfiguration(file);

		this.title = c.getString("title", "&8Select a map");
		this.size = Math.max(1, Math.min(6, c.getInt("rows", 3))) * 9;
		this.lapsMin = Math.max(1, c.getInt("laps.min", 1));
		this.lapsMax = Math.max(this.lapsMin, c.getInt("laps.max", 5));

		this.clickIncrease = clicks(c, "clicks.laps-increase", "LEFT");
		this.clickDecrease = clicks(c, "clicks.laps-decrease", "RIGHT");
		this.clickJoin = clicks(c, "clicks.join", "SHIFT_LEFT", "SHIFT_RIGHT", "MIDDLE");

		this.statusText.put(RaceManager.ArenaStatus.FREE, c.getString("status.free", "&aFree"));
		this.statusText.put(RaceManager.ArenaStatus.BUSY, c.getString("status.busy", "&cBusy"));
		this.statusText.put(RaceManager.ArenaStatus.EVENT, c.getString("status.event", "&eEvent - open for joining"));
		this.statusText.put(RaceManager.ArenaStatus.NOT_READY, c.getString("status.not-ready", "&7Not ready"));
		this.statusText.put(RaceManager.ArenaStatus.MAINTENANCE, c.getString("status.maintenance", "&cClosed - maintenance"));

		final String maintenance = c.getString("maintenance.material", "BARRIER");
		final Material maintenanceItem = maintenance == null || maintenance.isBlank() ? null : Material.matchMaterial(maintenance);

		this.maintenanceMaterial = maintenanceItem != null && maintenanceItem.isItem() ? maintenanceItem : null;
		this.accessAllowed = c.getString("access.allowed", this.accessAllowed);
		this.accessDenied = c.getString("access.denied", this.accessDenied);

		this.mapIcons.clear();

		final ConfigurationSection maps = c.getConfigurationSection("maps");

		if (maps != null)
			for (final String arena : maps.getKeys(false)) {
				final Icon icon = this.icon(maps.getConfigurationSection(arena), "&b&l{arena}");

				if (icon != null)
					this.mapIcons.put(arena.toLowerCase(Locale.ROOT), icon);
			}

		final ConfigurationSection unlistedSection = c.getConfigurationSection("unlisted-maps");

		this.showUnlisted = unlistedSection == null || unlistedSection.getBoolean("show", true);
		this.unlistedStartSlot = unlistedSection == null ? 10 : unlistedSection.getInt("start-slot", 10);
		this.unlisted = this.icon(unlistedSection, "&b&l{arena}");

		this.random = c.getBoolean("random.enabled", true) ? this.icon(c.getConfigurationSection("random"), "&d&lRandom map") : null;
		this.close = c.getBoolean("close.enabled", true) ? this.icon(c.getConfigurationSection("close"), "&c&lClose") : null;
		this.filler = c.getBoolean("filler.enabled", false) ? this.icon(c.getConfigurationSection("filler"), " ") : null;

		this.decorations.clear();

		final ConfigurationSection items = c.getConfigurationSection("items");

		if (items != null)
			for (final String key : items.getKeys(false)) {
				final Icon icon = this.icon(items.getConfigurationSection(key), " ");

				if (icon != null)
					this.decorations.add(icon);
			}

		this.compButtons.clear();

		final ConfigurationSection buttons = c.getConfigurationSection("competitive-buttons");

		if (buttons != null)
			for (final String key : buttons.getKeys(false)) {
				final ConfigurationSection section = buttons.getConfigurationSection(key);

				if (section == null || !section.getBoolean("enabled", true))
					continue;

				final Icon icon = this.icon(section, "&6&lCompetitive");
				final String arena = section.getString("arena", "random");

				if (icon != null)
					this.compButtons.add(new CompButton(icon, "random".equalsIgnoreCase(arena) ? null : arena));
			}
	}

	private static Set<String> clicks(final YamlConfiguration c, final String path, final String... defaults) {
		final Set<String> result = new HashSet<>();

		for (final String raw : c.getStringList(path))
			result.add(raw.trim().toUpperCase(Locale.ROOT));

		return result.isEmpty() ? new HashSet<>(Arrays.asList(defaults)) : result;
	}

	/** Reads material / name / lore / slot(s) of an item section; null when the section is missing. */
	private Icon icon(final ConfigurationSection section, final String defaultName) {
		if (section == null)
			return null;

		Material material = Material.matchMaterial(section.getString("material", "STONE"));

		if (material == null || !material.isItem()) {
			this.plugin.getLogger().warning("gui.yml: unknown material '" + section.getString("material") + "' in " + section.getCurrentPath() + " - using STONE.");
			material = Material.STONE;
		}

		final List<Integer> slots = new ArrayList<>();

		if (section.isList("slots"))
			slots.addAll(section.getIntegerList("slots"));
		else if (section.contains("slot"))
			slots.add(section.getInt("slot"));

		return new Icon(material, section.getString("name", defaultName), section.getStringList("lore"), slots);
	}

	// ------------------------------------------------------------------------------------------
	// Opening / rendering
	// ------------------------------------------------------------------------------------------

	public void open(final Player player) {
		final MenuHolder holder = new MenuHolder();
		final Inventory inventory = Bukkit.createInventory(holder, this.size, Msg.c(this.title));

		holder.inventory = inventory;

		this.render(player, holder);
		player.openInventory(inventory);
	}

	private void render(final Player player, final MenuHolder holder) {
		final Inventory inventory = holder.inventory;

		inventory.clear();
		holder.actions.clear();

		// everything with a configured slot keeps it; automatically placed maps use the remaining ones
		final Set<Integer> reserved = new HashSet<>();

		for (final Icon icon : this.mapIcons.values())
			reserved.addAll(icon.slots());

		for (final Icon icon : this.decorations)
			reserved.addAll(icon.slots());

		if (this.random != null)
			reserved.addAll(this.random.slots());

		if (this.close != null)
			reserved.addAll(this.close.slots());

		final pl.kingdomcraft.boatrace.Settings settings = pl.kingdomcraft.boatrace.BoatRacePlugin.getInstance().getSettings();
		final List<CompButton> visibleComp = new ArrayList<>();

		// only with competitive.enabled, and an arena button only for an arena that exists
		if (settings.compEnabled)
			for (final CompButton button : this.compButtons)
				if (button.arena() == null || this.arenas.get(button.arena()) != null) {
					visibleComp.add(button);
					reserved.addAll(button.icon().slots());
				}

		// 1) decoration (does nothing), 2) functional buttons on top of it
		for (final Icon icon : this.decorations)
			this.place(inventory, holder, icon.slots(), this.build(icon, new Object[0]), new Action(Type.NONE, null));

		if (this.close != null)
			this.place(inventory, holder, this.close.slots(), this.build(this.close, new Object[0]), new Action(Type.CLOSE, null));

		if (this.random != null)
			this.place(inventory, holder, this.random.slots(), this.build(this.random, this.randomPlaceholders(player)), new Action(Type.RANDOM, null));

		for (final CompButton button : visibleComp) {
			final RaceManager.CompLobbyInfo lobby = this.races.competitiveLobby(button.arena());

			this.place(inventory, holder, button.icon().slots(), this.build(button.icon(), new Object[] {
					"arena", lobby.arena(),
					"players", lobby.players(),
					"teams", lobby.teams(),
					"min", lobby.min(),
					"max", lobby.max(),
					"countdown", lobby.countdown() >= 0 ? Msg.text("gui.comp-countdown", "seconds", lobby.countdown()) : "",
					"queued", this.races.isInCompetitiveQueue(player.getUniqueId()) ? Msg.text("gui.comp-queued") : ""
			}), new Action(Type.TEAM, button.arena() != null ? button.arena() : ""));
		}

		for (final Map.Entry<String, Icon> entry : this.mapIcons.entrySet()) {
			final Arena arena = this.arenas.get(entry.getKey());

			if (arena != null)
				this.place(inventory, holder, entry.getValue().slots(), this.build(this.iconOf(arena, entry.getValue()), this.mapPlaceholders(player, arena)), new Action(Type.MAP, arena.getName()));
		}

		if (this.showUnlisted && this.unlisted != null) {
			final List<Arena> rest = new ArrayList<>();

			for (final Arena arena : this.arenas.readyArenas())
				if (!this.mapIcons.containsKey(arena.getName().toLowerCase(Locale.ROOT)))
					rest.add(arena);

			rest.sort(Comparator.comparing(Arena::getName, String.CASE_INSENSITIVE_ORDER));

			int slot = Math.max(0, this.unlistedStartSlot);

			for (final Arena arena : rest) {
				while (slot < this.size && (reserved.contains(slot) || holder.actions.containsKey(slot)))
					slot++;

				if (slot >= this.size)
					break;

				this.place(inventory, holder, List.of(slot), this.build(this.iconOf(arena, this.unlisted), this.mapPlaceholders(player, arena)), new Action(Type.MAP, arena.getName()));
			}
		}

		if (this.filler != null)
			for (int slot = 0; slot < this.size; slot++)
				if (inventory.getItem(slot) == null)
					inventory.setItem(slot, this.build(this.filler, new Object[0]));
	}

	private void place(final Inventory inventory, final MenuHolder holder, final List<Integer> slots, final ItemStack item, final Action action) {
		for (final int slot : slots)
			if (slot >= 0 && slot < this.size) {
				inventory.setItem(slot, item);
				holder.actions.put(slot, action);
			}
	}

	/** A map in maintenance is shown disabled: with the maintenance material instead of its icon. */
	private Icon iconOf(final Arena arena, final Icon icon) {
		if (!arena.isMaintenance() || this.maintenanceMaterial == null)
			return icon;

		return new Icon(this.maintenanceMaterial, icon.name(), icon.lore(), icon.slots());
	}

	private Object[] mapPlaceholders(final Player player, final Arena arena) {
		final String permission = permission(arena);

		return new Object[] {
				"arena", arena.getName(),
				"status", this.statusText.getOrDefault(this.races.statusOf(arena), ""),
				"players", this.races.playingCount(arena.getName()),
				"slots", this.races.capacityOf(arena),
				"laps", this.lapsOf(player, arena),
				"queue", this.races.queueSize(arena),
				"permission", permission,
				"access", Msg.apply(player.hasPermission(permission) ? this.accessAllowed : this.accessDenied, "permission", permission)
		};
	}

	private Object[] randomPlaceholders(final Player player) {
		int maps = 0;
		final int size = this.races.groupSize(player);

		// the maps the random button can really pick: open, permitted, with a grid position for the whole party
		for (final Arena arena : this.arenas.readyArenas())
			if (!arena.isMaintenance() && arena.getSpawns().size() >= size && player.hasPermission(permission(arena)))
				maps++;

		return new Object[] { "maps", maps, "players", this.races.playingCount() };
	}

	private ItemStack build(final Icon icon, final Object[] placeholders) {
		final ItemStack item = new ItemStack(icon.material());
		final ItemMeta meta = item.getItemMeta();
		final List<Component> lore = new ArrayList<>();

		for (final String line : icon.lore())
			lore.add(Msg.item(Msg.apply(line, placeholders)));

		meta.displayName(Msg.item(Msg.apply(icon.name(), placeholders)));
		meta.lore(lore.isEmpty() ? null : lore);
		meta.addItemFlags(ItemFlag.values());
		item.setItemMeta(meta);

		return item;
	}

	// ------------------------------------------------------------------------------------------
	// Laps
	// ------------------------------------------------------------------------------------------

	/** The chosen laps of the player for the map, by default the laps configured for the arena (within the GUI limits). */
	private int lapsOf(final Player player, final Arena arena) {
		final Map<String, Integer> chosen = this.chosenLaps.get(player.getUniqueId());
		final Integer value = chosen == null ? null : chosen.get(arena.getName().toLowerCase(Locale.ROOT));

		return Math.max(this.lapsMin, Math.min(this.lapsMax, value != null ? value : arena.getLaps()));
	}

	private void changeLaps(final Player player, final Arena arena, final int delta, final MenuHolder holder) {
		final int current = this.lapsOf(player, arena);
		final int updated = Math.max(this.lapsMin, Math.min(this.lapsMax, current + delta));

		if (updated == current)
			return;

		this.chosenLaps.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(arena.getName().toLowerCase(Locale.ROOT), updated);
		this.render(player, holder);
	}

	// ------------------------------------------------------------------------------------------
	// Clicks
	// ------------------------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.HIGH)
	public void onClick(final InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof final MenuHolder holder))
			return;

		// nothing may be taken out, put in or shifted around
		event.setCancelled(true);

		if (event.getClickedInventory() != event.getInventory() || !(event.getWhoClicked() instanceof final Player player))
			return;

		final Action action = holder.actions.get(event.getSlot());

		if (action == null)
			return;

		switch (action.type()) {
			case CLOSE -> Bukkit.getScheduler().runTask(this.plugin, (Runnable) player::closeInventory);
			case RANDOM -> this.clickRandom(player);
			case MAP -> this.clickMap(player, action.arena(), event.getClick().name(), holder);
			case TEAM -> this.clickCompetitive(player, action.arena());
			default -> {
			}
		}
	}

	private void clickMap(final Player player, final String arenaName, final String click, final MenuHolder holder) {
		final Arena arena = this.arenas.get(arenaName);

		if (arena == null) {
			this.render(player, holder);
			return;
		}

		// a map without the permission blocks every action on it
		if (!player.hasPermission(permission(arena))) {
			Msg.send(player, "gui.no-permission", "arena", arena.getName(), "permission", permission(arena));
			return;
		}

		// closed maps are disabled: no joining and no lap changes
		if (arena.isMaintenance()) {
			Msg.send(player, "race.maintenance", "arena", arena.getName());
			return;
		}

		if (this.clickJoin.contains(click))
			this.joinFromMenu(player, arena, this.lapsOf(player, arena));

		else if (this.clickIncrease.contains(click))
			this.changeLaps(player, arena, 1, holder);

		else if (this.clickDecrease.contains(click))
			this.changeLaps(player, arena, -1, holder);
	}

	/** @param arenaName arena of the button, empty = any */
	private void clickCompetitive(final Player player, final String arenaName) {
		final Arena arena = arenaName.isEmpty() ? null : this.arenas.get(arenaName);

		if (!arenaName.isEmpty() && arena == null)
			return;

		if (arena != null && !player.hasPermission(permission(arena))) {
			Msg.send(player, "gui.no-permission", "arena", arena.getName(), "permission", permission(arena));
			return;
		}

		Bukkit.getScheduler().runTask(this.plugin, () -> {
			player.closeInventory();

			// a second click leaves the lobby again
			if (this.races.isInCompetitiveQueue(player.getUniqueId())) {
				this.races.leaveCompetitive(player);
				Msg.send(player, "comp.queue-left");

			} else
				this.races.joinCompetitive(player, arena);
		});
	}

	private void clickRandom(final Player player) {
		// open maps only, and - for a party leader - only maps with a grid position for every member
		final Arena arena = this.races.pickRandomReadyArena(player, candidate -> player.hasPermission(permission(candidate)));

		if (arena == null) {
			if (this.races.groupSize(player) > 1)
				Msg.send(player, "party.no-map-fits", "size", this.races.groupSize(player));
			else
				Msg.send(player, "gui.no-random");

			return;
		}

		this.joinFromMenu(player, arena, null);
	}

	/** Closes the menu and joins on the next tick (never open / close inventories inside the click event). */
	private void joinFromMenu(final Player player, final Arena arena, final Integer laps) {
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			player.closeInventory();

			if (player.isOnline())
				this.races.join(player, arena, laps);
		});
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onDrag(final InventoryDragEvent event) {
		if (event.getInventory().getHolder() instanceof MenuHolder)
			event.setCancelled(true);
	}

	@EventHandler
	public void onQuit(final PlayerQuitEvent event) {
		this.chosenLaps.remove(event.getPlayer().getUniqueId());
	}
}
