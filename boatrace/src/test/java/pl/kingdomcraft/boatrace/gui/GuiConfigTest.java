package pl.kingdomcraft.boatrace.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiConfigTest {

	/** Material lookup without the server's registry. */
	static Material material(final String name) {
		try {
			return Material.valueOf(name.toUpperCase());

		} catch (final IllegalArgumentException ex) {
			return null;
		}
	}

	static GuiConfig parse(final String yaml, final List<String> warnings) throws InvalidConfigurationException {
		final YamlConfiguration config = new YamlConfiguration();

		config.loadFromString(yaml);

		return GuiConfig.parse(config, GuiConfigTest::material, warnings::add);
	}

	private static GuiItem item(final GuiConfig config, final String menu, final String id) {
		return config.menus.get(menu).items().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
	}

	@Test
	void shippedGuisYmlParsesWithoutWarnings() throws IOException, InvalidConfigurationException {
		final YamlConfiguration yaml = new YamlConfiguration();

		try (InputStream in = GuiConfigTest.class.getResourceAsStream("/guis.yml")) {
			assertNotNull(in, "guis.yml must be in the jar");
			yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}

		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = GuiConfig.parse(yaml, GuiConfigTest::material, warnings::add);

		assertEquals(List.of(), warnings);
		assertEquals("unranked", config.defaultMenu);
		assertEquals(List.of("unranked", "ranked"), new ArrayList<>(config.menus.keySet()));

		final GuiMenu unranked = config.menus.get("unranked");
		final GuiMenu ranked = config.menus.get("ranked");

		assertFalse(unranked.requiresCompetitive());
		assertTrue(ranked.requiresCompetitive());
		assertEquals("all", unranked.autoMaps().filter());
		assertEquals("competitive", ranked.autoMaps().filter());

		// casual map: left / right = laps, shift / middle = join
		final GuiItem map = unranked.autoMaps().template();

		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_INCREASE, "")), map.actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_DECREASE, "")), map.actionsFor("RIGHT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), map.actionsFor("SHIFT_LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), map.actionsFor("SHIFT_RIGHT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), map.actionsFor("MIDDLE"));
		assertEquals(List.of(), map.actionsFor("DROP"));
		assertFalse(map.isRanked());

		// ranked map: any click joins the competitive lobby
		assertTrue(ranked.autoMaps().template().isRanked());
		assertEquals(List.of(new GuiAction(GuiAction.Type.RANKED_MAP, "")), ranked.autoMaps().template().actionsFor("RIGHT"));

		assertEquals(List.of(new GuiAction(GuiAction.Type.RANDOM_MAP, "unranked")), item(config, "unranked", "random").actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.RANDOM_MAP, "ranked")), item(config, "ranked", "random").actionsFor("LEFT"));
		assertEquals("random", item(config, "ranked", "random").arena());
		assertEquals(List.of(new GuiAction(GuiAction.Type.CLOSE, "")), item(config, "unranked", "close").actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.OPEN_MENU, "unranked")), item(config, "ranked", "back").actionsFor("LEFT"));
		assertTrue(item(config, "unranked", "ranked").requiresCompetitive());
		assertEquals(List.of(), item(config, "unranked", "info").actionsFor("LEFT"));
	}

	@Test
	void actionsCanBeWrittenInEveryForm() throws InvalidConfigurationException {
		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = parse("""
				default-menu: m
				menus:
				  m:
				    items:
				      a: { slot: 0, actions: close }
				      b: { slot: 1, actions: [ "join-map monaco", close ] }
				      c: { slot: 2, actions: [ { join-map: monaco }, "message: &aHello {player}" ] }
				      d:
				        slot: 3
				        actions:
				          LEFT: laps-increase
				          shift: join-map
				          Middle-Click: close
				          default: [ "player-command spawn", "console-command give {player} apple 1" ]
				""", warnings);

		assertEquals(List.of(), warnings);

		assertEquals(List.of(new GuiAction(GuiAction.Type.CLOSE, "")), item(config, "m", "a").actionsFor("ANYTHING"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "monaco"), new GuiAction(GuiAction.Type.CLOSE, "")), item(config, "m", "b").actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "monaco"), new GuiAction(GuiAction.Type.MESSAGE, "&aHello {player}")), item(config, "m", "c").actionsFor("RIGHT"));

		final GuiItem d = item(config, "m", "d");

		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_INCREASE, "")), d.actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), d.actionsFor("SHIFT_LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), d.actionsFor("SHIFT_RIGHT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.PLAYER_COMMAND, "spawn"), new GuiAction(GuiAction.Type.CONSOLE_COMMAND, "give {player} apple 1")), d.actionsFor("DROP"));
		// "Middle-Click" is not a click type: it is kept as written (upper case, '-' = '_') and simply never matches
		assertEquals(List.of(new GuiAction(GuiAction.Type.CLOSE, "")), d.actions().get("MIDDLE_CLICK"));
	}

	@Test
	void badActionsAreReportedAndSkipped() throws InvalidConfigurationException {
		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = parse("""
				default-menu: m
				menus:
				  m:
				    items:
				      a: { slot: 0, actions: [ "dance", "open-menu", "random-map sometimes", "message", "join-map monaco" ] }
				""", warnings);

		assertEquals(4, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("unknown action 'dance'"));
		assertTrue(warnings.get(1).contains("open-menu") && warnings.get(1).contains("argument"));
		assertTrue(warnings.get(2).contains("random-map"));
		assertTrue(warnings.get(3).contains("message") && warnings.get(3).contains("argument"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "monaco")), item(config, "m", "a").actionsFor("LEFT"));
	}

	@Test
	void randomMapDefaultsToUnranked() {
		assertEquals("unranked", GuiAction.parse("random-map", w -> {
		}).argument());
		assertEquals("ranked", GuiAction.parse("RANDOM_MAP: Ranked", w -> {
		}).argument());
		assertTrue(GuiAction.parse("random-map ranked", w -> {
		}).isRanked());
		assertFalse(GuiAction.parse("random-map", w -> {
		}).isRanked());
		assertTrue(GuiAction.parse("ranked-map monaco", w -> {
		}).isRanked());
		assertNull(GuiAction.parse("   ", w -> {
		}));
		// a colon inside the argument is kept
		assertEquals("tp 1:2", GuiAction.parse("player-command tp 1:2", w -> {
		}).argument());
	}

	@Test
	void itemOptions() throws InvalidConfigurationException {
		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = parse("""
				default-menu: m
				menus:
				  m:
				    items:
				      off: { slot: 0, enabled: false, material: STONE }
				      many: { slots: [1, 2, 3], material: DIAMOND, name: "&bHi", lore: [ "x", "y" ] }
				      bound: { slot: 4, arena: Monaco, permission: my.perm, requires-competitive: true }
				      weird: { slot: 5, material: NOT_A_BLOCK }
				""", warnings);

		assertNull(item(config, "m", "off"));
		assertEquals(List.of(1, 2, 3), item(config, "m", "many").slots());
		assertEquals(Material.DIAMOND, item(config, "m", "many").material());
		assertEquals(List.of("x", "y"), item(config, "m", "many").lore());

		final GuiItem bound = item(config, "m", "bound");

		assertEquals("Monaco", bound.arena());
		assertEquals("my.perm", bound.permission());
		assertTrue(bound.requiresCompetitive());

		assertEquals(Material.STONE, item(config, "m", "weird").material());
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("NOT_A_BLOCK"));
	}

	@Test
	void menuSettingsAreCheckedAndClamped() throws InvalidConfigurationException {
		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = parse("""
				default-menu: Missing
				menus:
				  First:
				    rows: 99
				    laps: { min: 4, max: 2 }
				    permission: " "
				    auto-maps: { filter: nonsense, material: STONE }
				    filler: { enabled: true, material: GLASS_PANE }
				  second:
				    rows: 0
				    auto-maps: { show: false }
				""", warnings);

		final GuiMenu first = config.menus.get("first");

		assertEquals(6, first.rows());
		assertEquals(54, first.size());
		assertEquals(4, first.lapsMin());
		assertEquals(4, first.lapsMax());
		assertNull(first.permission());
		assertEquals("all", first.autoMaps().filter());
		assertNotNull(first.filler());
		assertEquals(1, config.menus.get("second").rows());
		assertNull(config.menus.get("second").autoMaps());

		// ids are lower case and the unknown default menu falls back to the first one
		assertEquals("first", config.defaultMenu);
		assertEquals(2, warnings.size(), warnings.toString());
	}

	@Test
	void fileUpdaterAddsNewGlobalKeysButNeverTouchesTheOwnersMenus() throws IOException, InvalidConfigurationException {
		final YamlConfiguration defaults = new YamlConfiguration();

		try (InputStream in = GuiConfigTest.class.getResourceAsStream("/guis.yml")) {
			defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}

		// an older guis.yml: no maintenance section, no "maintenance" status, and only the owner's own menu
		final YamlConfiguration user = new YamlConfiguration();

		user.loadFromString("""
				default-menu: mine
				status:
				  free: "&2Open"
				menus:
				  mine:
				    rows: 2
				    items:
				      x: { slot: 0, material: STONE, actions: close }
				""");

		final List<String> added = pl.kingdomcraft.boatrace.util.ConfigUpdater.merge(user, defaults, java.util.Set.of("menus"));

		assertTrue(added.contains("maintenance"), added.toString());
		assertTrue(added.contains("status.maintenance"), added.toString());
		assertEquals("&2Open", user.getString("status.free"), "the owner's value stays");
		assertEquals(java.util.Set.of("mine"), user.getConfigurationSection("menus").getKeys(false), "no default menu is added to the owner's menus");
		assertFalse(added.stream().anyMatch(path -> path.startsWith("menus")), added.toString());

		// ... but a file without any menus gets the default ones as a whole
		final YamlConfiguration empty = new YamlConfiguration();

		empty.loadFromString("default-menu: unranked\n");
		assertTrue(pl.kingdomcraft.boatrace.util.ConfigUpdater.merge(empty, defaults, java.util.Set.of("menus")).contains("menus"));
		assertEquals(java.util.Set.of("unranked", "ranked"), empty.getConfigurationSection("menus").getKeys(false));
	}

	@Test
	void emptyFileHasNoMenusAndSaysSo() throws InvalidConfigurationException {
		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = parse("default-menu: x\n", warnings);

		assertTrue(config.menus.isEmpty());
		assertEquals(1, warnings.size());
	}
}
