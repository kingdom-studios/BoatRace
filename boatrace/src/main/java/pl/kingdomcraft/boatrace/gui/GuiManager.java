package pl.kingdomcraft.boatrace.gui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
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
import pl.kingdomcraft.boatrace.Settings;
import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * The menus (/bre gui &lt;menu&gt; [player]), configured entirely in guis.yml: any number of menus, each made of items
 * with their own slots, icons, lore and - per mouse click - actions (see {@link GuiAction}). Typical menus: casual
 * ("unranked") and competitive ("ranked").
 *
 * Maps need the permission boatrace.map.&lt;arena name in lower case&gt;. A click on a map the player may not play does
 * nothing but send gui.no-permission (messages.yml). Opening a menu is silent: nothing is sent to the chat.
 */
public final class GuiManager implements Listener {

	/** An item as placed in an open menu: the configured item and the map it stands for (an automatic map's arena). */
	record Bound(GuiItem item, String arena) {
	}

	/** The holder of every inventory this class opens. */
	static final class MenuHolder implements InventoryHolder {

		final GuiMenu menu;
		final Map<Integer, Bound> items = new HashMap<>();
		Inventory inventory;

		MenuHolder(final GuiMenu menu) {
			this.menu = menu;
		}

		@Override
		public Inventory getInventory() {
			return this.inventory;
		}
	}

	/** Who clicked what: what the actions of one click work with. */
	private record Context(Player player, MenuHolder holder, String arena) {
	}

	private final JavaPlugin plugin;
	private final ArenaManager arenas;
	private final RaceManager races;
	private final Settings settings;

	/** player -> arena name (lower case) -> chosen laps */
	private final Map<UUID, Map<String, Integer>> chosenLaps = new HashMap<>();

	private GuiConfig config = new GuiConfig();

	/** Resolves the material names of guis.yml. A field only so the tests can run without the server's item registry. */
	java.util.function.Function<String, Material> materials = GuiManager::material;

	/** Creates the item of a menu slot. A field for the same reason as {@link #materials}. */
	java.util.function.Function<Material, ItemStack> stacks = ItemStack::new;

	public GuiManager(final JavaPlugin plugin, final ArenaManager arenas, final RaceManager races, final Settings settings) {
		this.plugin = plugin;
		this.arenas = arenas;
		this.races = races;
		this.settings = settings;
	}

	/** The permission needed to play (and join from a menu) an arena. */
	public static String permission(final Arena arena) {
		return "boatrace.map." + arena.getName().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------------------------------
	// guis.yml
	// ------------------------------------------------------------------------------------------

	/** (Re)loads guis.yml. A file with an error keeps the previous menus. */
	public void load() {
		final File file = new File(this.plugin.getDataFolder(), "guis.yml");

		if (!file.exists())
			this.createFile(file);

		final YamlConfiguration yaml = new YamlConfiguration();

		try {
			yaml.load(file);

		} catch (final IOException | InvalidConfigurationException ex) {
			this.plugin.getLogger().log(Level.WARNING, "guis.yml could not be read - the menus keep their previous setup.", ex);
			return;
		}

		this.config = GuiConfig.parse(yaml, this.materials, text -> this.plugin.getLogger().warning("guis.yml " + text));
	}

	private static Material material(final String raw) {
		final Material material = Material.matchMaterial(raw);

		return material != null && material.isItem() ? material : null;
	}

	/** First start: an old gui.yml is converted (nothing of it is lost), otherwise the default guis.yml is written. */
	private void createFile(final File target) {
		final File legacy = new File(this.plugin.getDataFolder(), "gui.yml");

		if (legacy.exists() && this.migrate(legacy, target))
			return;

		this.plugin.saveResource("guis.yml", false);
	}

	private boolean migrate(final File legacy, final File target) {
		try {
			final YamlConfiguration old = new YamlConfiguration();

			old.load(legacy);

			final YamlConfiguration defaults = new YamlConfiguration();

			try (InputStream in = this.plugin.getResource("guis.yml")) {
				if (in != null)
					defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			}

			final YamlConfiguration converted = GuiMigration.convert(old, defaults.getConfigurationSection("menus.ranked"));

			converted.options().width(Integer.MAX_VALUE);
			converted.options().setHeader(List.of(
					"BoatRace menus - created from your old gui.yml (the old file is kept as gui.yml.migrated).",
					"Your menu is now \"unranked\"; the \"ranked\" menu is new. Every item has its own actions - see the default",
					"guis.yml of the plugin (documentation at the top) or the README. Open a menu: /bre gui <menu> [player]"));
			converted.save(target);

			Files.move(legacy.toPath(), new File(this.plugin.getDataFolder(), "gui.yml.migrated").toPath(), StandardCopyOption.REPLACE_EXISTING);

			this.plugin.getLogger().info("gui.yml was converted to guis.yml (menu \"unranked\" = your old menu, new menu \"ranked\"); the old file is now gui.yml.migrated.");

			return true;

		} catch (final IOException | InvalidConfigurationException ex) {
			this.plugin.getLogger().log(Level.WARNING, "Could not convert gui.yml to guis.yml - the default guis.yml is used (gui.yml is untouched).", ex);

			return false;
		}
	}

	public boolean hasMenu(final String id) {
		return this.config.menus.containsKey(id.toLowerCase(Locale.ROOT));
	}

	public Set<String> menuIds() {
		return this.config.menus.keySet();
	}

	public String defaultMenu() {
		return this.config.defaultMenu;
	}

	// ------------------------------------------------------------------------------------------
	// Opening
	// ------------------------------------------------------------------------------------------

	/** The default menu for the player. */
	public boolean open(final Player player) {
		return this.open(player, this.config.defaultMenu, player);
	}

	/**
	 * Opens the menu for {@code viewer}. Silent on success - nothing is sent to anybody. A refusal (unknown menu,
	 * competitive matches off, no permission to open it) is reported to {@code requester}.
	 *
	 * @param requester who asked: the viewer themselves, or an admin / the console (who may open every menu)
	 * @return whether the menu was opened
	 */
	public boolean open(final Player viewer, final String menuId, final CommandSender requester) {
		final GuiMenu menu = this.config.menus.get(menuId.toLowerCase(Locale.ROOT));

		if (menu == null) {
			Msg.send(requester, "gui.unknown-menu", "menu", menuId, "menus", String.join(", ", this.config.menus.keySet()));
			return false;
		}

		if (menu.requiresCompetitive() && !this.settings.compEnabled) {
			Msg.send(requester, "comp.disabled");
			return false;
		}

		if (menu.permission() != null && requester == viewer && !viewer.hasPermission(menu.permission())) {
			Msg.send(viewer, "gui.menu-no-permission", "permission", menu.permission());
			return false;
		}

		final MenuHolder holder = new MenuHolder(menu);

		holder.inventory = Bukkit.createInventory(holder, menu.size(), Msg.c(menu.title()));

		this.render(viewer, holder);
		viewer.openInventory(holder.inventory);

		return true;
	}

	// ------------------------------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------------------------------

	private boolean visible(final GuiItem item) {
		if (item.requiresCompetitive() && !this.settings.compEnabled)
			return false;

		// an item of a map that does not exist (any more) is not shown
		return item.arena() == null || "random".equalsIgnoreCase(item.arena()) || this.arenas.get(item.arena()) != null;
	}

	private void render(final Player player, final MenuHolder holder) {
		final GuiMenu menu = holder.menu;
		final Inventory inventory = holder.inventory;
		final int size = menu.size();

		inventory.clear();
		holder.items.clear();

		// items keep their configured slots; automatically added maps use the ones that stay free
		final Set<Integer> reserved = new HashSet<>();
		final List<GuiItem> shown = new ArrayList<>();

		for (final GuiItem item : menu.items())
			if (this.visible(item)) {
				shown.add(item);
				reserved.addAll(item.slots());
			}

		// file order: when two items share a slot the later one wins
		for (final GuiItem item : shown)
			this.place(player, holder, item, item.arena(), item.slots());

		final GuiMenu.AutoMaps auto = menu.autoMaps();

		if (auto != null) {
			final Set<String> own = new HashSet<>();

			for (final GuiItem item : menu.items())
				if (item.arena() != null)
					own.add(item.arena().toLowerCase(Locale.ROOT));

			final List<Arena> rest = new ArrayList<>();

			for (final Arena arena : this.arenas.readyArenas())
				if (!own.contains(arena.getName().toLowerCase(Locale.ROOT)) && ("all".equals(auto.filter()) || arena.isCompetitive()))
					rest.add(arena);

			rest.sort(Comparator.comparing(Arena::getName, String.CASE_INSENSITIVE_ORDER));

			int slot = auto.startSlot();

			for (final Arena arena : rest) {
				while (slot < size && (reserved.contains(slot) || holder.items.containsKey(slot)))
					slot++;

				if (slot >= size)
					break;

				this.place(player, holder, auto.template(), arena.getName(), List.of(slot));
			}
		}

		if (menu.filler() != null) {
			final ItemStack filler = this.build(player, holder, menu.filler(), null);

			for (int slot = 0; slot < size; slot++)
				if (inventory.getItem(slot) == null)
					inventory.setItem(slot, filler);
		}
	}

	private void place(final Player player, final MenuHolder holder, final GuiItem item, final String arenaName, final List<Integer> slots) {
		final ItemStack stack = this.build(player, holder, item, arenaName);

		for (final int slot : slots)
			if (slot >= 0 && slot < holder.menu.size()) {
				holder.inventory.setItem(slot, stack);
				holder.items.put(slot, new Bound(item, arenaName));
			}
	}

	private ItemStack build(final Player player, final MenuHolder holder, final GuiItem item, final String arenaName) {
		final Arena arena = this.arenaOf(arenaName);
		Material material = item.material();

		// a map in maintenance is shown disabled
		if (arena != null && arena.isMaintenance() && this.config.maintenanceMaterial != null)
			material = this.config.maintenanceMaterial;

		final Object[] placeholders = this.placeholders(player, holder.menu, item, arenaName, arena);
		final ItemStack stack = this.stacks.apply(material);
		final ItemMeta meta = stack.getItemMeta();
		final List<Component> lore = new ArrayList<>();

		for (final String line : item.lore())
			lore.add(Msg.item(Msg.apply(line, placeholders)));

		meta.displayName(Msg.item(Msg.apply(item.name(), placeholders)));
		meta.lore(lore.isEmpty() ? null : lore);
		meta.addItemFlags(ItemFlag.values());
		stack.setItemMeta(meta);

		return stack;
	}

	/** The arena an item or action name refers to; null for no map and for "random". */
	private Arena arenaOf(final String name) {
		return name == null || "random".equalsIgnoreCase(name) ? null : this.arenas.get(name);
	}

	private Object[] placeholders(final Player player, final GuiMenu menu, final GuiItem item, final String arenaName, final Arena arena) {
		final List<Object> ph = new ArrayList<>();
		final boolean ranked = item.isRanked();

		ph.add("player");
		ph.add(player.getName());
		ph.add("maps");
		ph.add(this.randomChoices(player));
		ph.add("playing");
		ph.add(arena != null ? this.races.playingCount(arena.getName()) : this.races.playingCount());

		RaceManager.CompLobbyInfo lobby = null;

		// the competitive lobby of the map ("random" / ranked item without a map = the lobby for any map)
		if (ranked || arenaName != null)
			lobby = this.races.competitiveLobby(arena != null ? arena.getName() : null);

		if (lobby != null) {
			final String countdown = lobby.countdown() >= 0 ? Msg.text("gui.comp-countdown", "seconds", lobby.countdown()) : "";
			final String queued = this.races.isInCompetitiveQueue(player.getUniqueId()) ? Msg.text("gui.comp-queued") : "";

			ph.addAll(List.of("lobby_players", lobby.players(), "lobby_teams", lobby.teams(), "lobby_min", lobby.min(), "lobby_max", lobby.max(),
					"lobby_countdown", countdown, "lobby_queued", queued));

			if (ranked)
				ph.addAll(List.of("players", lobby.players(), "teams", lobby.teams(), "min", lobby.min(), "max", lobby.max(), "countdown", countdown, "queued", queued));
		}

		if (arena != null) {
			final String permission = permission(arena);
			final String status = this.config.status.getOrDefault(this.races.statusOf(arena).name().toLowerCase(Locale.ROOT).replace('_', '-'), "");

			ph.addAll(List.of("arena", arena.getName(), "status", status, "slots", this.races.capacityOf(arena), "laps", this.lapsOf(player, arena, menu),
					"queue", this.races.queueSize(arena), "permission", permission,
					"access", Msg.apply(player.hasPermission(permission) ? this.config.accessAllowed : this.config.accessDenied, "permission", permission)));

			if (!ranked)
				ph.addAll(List.of("players", this.races.playingCount(arena.getName())));

		} else {
			ph.addAll(List.of("arena", lobby != null ? lobby.arena() : ""));

			if (!ranked)
				ph.addAll(List.of("players", this.races.playingCount()));
		}

		return ph.toArray();
	}

	/** The maps the random button can pick for this player: open, permitted, with room for the whole party. */
	private int randomChoices(final Player player) {
		int maps = 0;
		final int size = this.races.groupSize(player);

		for (final Arena arena : this.arenas.readyArenas())
			if (!arena.isMaintenance() && arena.getSpawns().size() >= size && player.hasPermission(permission(arena)))
				maps++;

		return maps;
	}

	// ------------------------------------------------------------------------------------------
	// Laps
	// ------------------------------------------------------------------------------------------

	/** The chosen laps of the player for the map, by default the laps configured for the arena (within the menu's limits). */
	private int lapsOf(final Player player, final Arena arena, final GuiMenu menu) {
		final Map<String, Integer> chosen = this.chosenLaps.get(player.getUniqueId());
		final Integer value = chosen == null ? null : chosen.get(arena.getName().toLowerCase(Locale.ROOT));

		return Math.max(menu.lapsMin(), Math.min(menu.lapsMax(), value != null ? value : arena.getLaps()));
	}

	private void changeLaps(final Player player, final Arena arena, final int delta, final MenuHolder holder) {
		final int current = this.lapsOf(player, arena, holder.menu);
		final int updated = Math.max(holder.menu.lapsMin(), Math.min(holder.menu.lapsMax(), current + delta));

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

		this.click(player, holder, event.getSlot(), event.getClick().name());
	}

	/** Runs the actions the item in {@code slot} has for this click type. */
	void click(final Player player, final MenuHolder holder, final int slot, final String click) {
		final Bound bound = holder.items.get(slot);

		if (bound == null)
			return;

		final GuiItem item = bound.item();

		if (item.permission() != null && !player.hasPermission(item.permission())) {
			Msg.send(player, "gui.item-no-permission", "permission", item.permission());
			return;
		}

		final Context context = new Context(player, holder, bound.arena());

		// a refused action (no permission, map closed ...) stops the ones after it
		for (final GuiAction action : item.actionsFor(click))
			if (!this.run(context, action))
				break;
	}

	/** @return false when the action was refused / could not work and the rest of the click must not run */
	private boolean run(final Context ctx, final GuiAction action) {
		final Player player = ctx.player();

		switch (action.type()) {
			case CLOSE -> {
				Bukkit.getScheduler().runTask(this.plugin, (Runnable) player::closeInventory);

				return true;
			}
			case OPEN_MENU -> {
				if (!this.hasMenu(action.argument())) {
					this.plugin.getLogger().warning("guis.yml: open-menu '" + action.argument() + "' - there is no such menu.");
					return false;
				}

				// next tick - never switch inventories inside the click event
				Bukkit.getScheduler().runTask(this.plugin, () -> {
					if (player.isOnline())
						this.open(player, action.argument(), player);
				});

				return true;
			}
			case JOIN_MAP -> {
				final Arena arena = this.target(ctx, action);

				if (arena == null || !this.allowed(player, arena))
					return false;

				this.joinFromMenu(player, arena, this.lapsOf(player, arena, ctx.holder().menu));

				return true;
			}
			case RANKED_MAP -> {
				final String name = action.argument().isEmpty() ? ctx.arena() : action.argument();
				final Arena arena = this.arenaOf(name);

				// "random" / no map = the lobby for any map; a map that does not exist does nothing
				if (arena == null && name != null && !"random".equalsIgnoreCase(name))
					return false;

				if (arena != null && !this.allowed(player, arena))
					return false;

				this.toggleCompetitive(player, arena);

				return true;
			}
			case RANDOM_MAP -> {
				if ("ranked".equals(action.argument())) {
					this.toggleCompetitive(player, null);
					return true;
				}

				return this.joinRandom(player);
			}
			case LAPS_INCREASE, LAPS_DECREASE -> {
				final Arena arena = this.target(ctx, action);

				if (arena == null || !this.allowed(player, arena))
					return false;

				this.changeLaps(player, arena, action.type() == GuiAction.Type.LAPS_INCREASE ? 1 : -1, ctx.holder());

				return true;
			}
			case PLAYER_COMMAND, CONSOLE_COMMAND -> {
				final String command = this.fill(ctx, action.argument());
				final String line = command.startsWith("/") ? command.substring(1) : command;

				Bukkit.getScheduler().runTask(this.plugin, () -> {
					if (action.type() == GuiAction.Type.PLAYER_COMMAND) {
						if (player.isOnline())
							Bukkit.dispatchCommand(player, line);

					} else
						Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
				});

				return true;
			}
			case MESSAGE -> {
				player.sendMessage(Msg.c(this.fill(ctx, action.argument())));

				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** The map an action works on: its own argument, otherwise the map of the item. Null = none, "random" or unknown. */
	private Arena target(final Context ctx, final GuiAction action) {
		return this.arenaOf(action.argument().isEmpty() ? ctx.arena() : action.argument());
	}

	/** The permission + maintenance gate of every action on a map; tells the player when it refuses. */
	private boolean allowed(final Player player, final Arena arena) {
		if (!player.hasPermission(permission(arena))) {
			Msg.send(player, "gui.no-permission", "arena", arena.getName(), "permission", permission(arena));
			return false;
		}

		// a closed map is disabled: no joining, no lap changes
		if (arena.isMaintenance()) {
			Msg.send(player, "race.maintenance", "arena", arena.getName());
			return false;
		}

		return true;
	}

	/** {player} {uuid} {arena} {laps} in commands / messages of guis.yml. */
	private String fill(final Context ctx, final String text) {
		final Arena arena = this.arenaOf(ctx.arena());

		return Msg.apply(text,
				"player", ctx.player().getName(),
				"uuid", ctx.player().getUniqueId(),
				"arena", arena != null ? arena.getName() : "",
				"laps", arena != null ? this.lapsOf(ctx.player(), arena, ctx.holder().menu) : 1);
	}

	/** A random casual map: open, permitted, with a grid position for every party member. */
	private boolean joinRandom(final Player player) {
		final Arena arena = this.races.pickRandomReadyArena(player, candidate -> player.hasPermission(permission(candidate)));

		if (arena == null) {
			if (this.races.groupSize(player) > 1)
				Msg.send(player, "party.no-map-fits", "size", this.races.groupSize(player));
			else
				Msg.send(player, "gui.no-random");

			return false;
		}

		this.joinFromMenu(player, arena, null);

		return true;
	}

	/** Closes the menu and joins on the next tick (never open / close inventories inside the click event). */
	private void joinFromMenu(final Player player, final Arena arena, final Integer laps) {
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			player.closeInventory();

			if (player.isOnline())
				this.races.join(player, arena, laps);
		});
	}

	/** Joins the competitive lobby of the map (null = any map); a second click leaves it again. */
	private void toggleCompetitive(final Player player, final Arena arena) {
		Bukkit.getScheduler().runTask(this.plugin, () -> {
			player.closeInventory();

			if (this.races.isInCompetitiveQueue(player.getUniqueId())) {
				this.races.leaveCompetitive(player);
				Msg.send(player, "comp.queue-left");

			} else
				this.races.joinCompetitive(player, arena);
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
