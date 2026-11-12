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
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class GuiMigrationTest {

	/** An old gui.yml like the plugin shipped it, with the owner's changes. */
	private static final String OLD = """
			title: "&0My maps"
			rows: 4
			laps:
			  min: 1
			  max: 3
			clicks:
			  laps-increase: [LEFT, DROP]
			  laps-decrease: [RIGHT]
			  join: [MIDDLE, DROP]
			status:
			  free: "&2Open"
			  busy: "&4Taken"
			access:
			  allowed: "&aOK"
			  denied: "&cNo: {permission}"
			maintenance:
			  material: RED_STAINED_GLASS_PANE
			maps:
			  monaco:
			    slot: 11
			    material: DIAMOND
			    name: "&bMonaco"
			    lore: ["&7Laps {laps}"]
			unlisted-maps:
			  show: true
			  start-slot: 19
			  material: OAK_BOAT
			  name: "&b{arena}"
			  lore: ["&7Status {status}"]
			random:
			  enabled: true
			  slot: 31
			  material: ENDER_PEARL
			  name: "&dRandom"
			close:
			  enabled: false
			  slot: 35
			  material: BARRIER
			filler:
			  enabled: true
			  material: BLACK_STAINED_GLASS_PANE
			  name: " "
			items:
			  info:
			    slot: 4
			    material: PAPER
			    name: "&eInfo"
			  close:
			    slot: 0
			    material: STONE
			    name: "&7A decoration that is called close"
			competitive-buttons:
			  any:
			    enabled: true
			    arena: random
			    slot: 29
			    material: LEATHER_CHESTPLATE
			    name: "&6Competitive"
			    lore: ["&7{players} in {teams} teams"]
			  monaco:
			    enabled: false
			    arena: monaco
			    slot: 30
			    material: LEATHER_BOOTS
			""";

	private static YamlConfiguration yaml(final String text) throws InvalidConfigurationException {
		final YamlConfiguration yaml = new YamlConfiguration();

		yaml.loadFromString(text);

		return yaml;
	}

	private static YamlConfiguration shipped() throws IOException, InvalidConfigurationException {
		final YamlConfiguration yaml = new YamlConfiguration();

		try (InputStream in = GuiMigrationTest.class.getResourceAsStream("/guis.yml")) {
			assertNotNull(in);
			yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}

		return yaml;
	}

	private static GuiConfig parse(final YamlConfiguration converted, final List<String> warnings) {
		return GuiConfig.parse(converted, GuiConfigTest::material, warnings::add);
	}

	private static GuiItem item(final GuiMenu menu, final String id) {
		return menu.items().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
	}

	@Test
	void oldMenuBecomesUnrankedWithEverythingKept() throws Exception {
		final YamlConfiguration converted = GuiMigration.convert(yaml(OLD), shipped().getConfigurationSection("menus.ranked"));
		final List<String> warnings = new ArrayList<>();
		final GuiConfig config = parse(converted, warnings);

		assertEquals(List.of(), warnings);
		assertEquals("unranked", config.defaultMenu);
		assertEquals(List.of("unranked", "ranked"), new ArrayList<>(config.menus.keySet()));

		// look and feel
		final GuiMenu unranked = config.menus.get("unranked");

		assertEquals("&0My maps", unranked.title());
		assertEquals(4, unranked.rows());
		assertEquals(3, unranked.lapsMax());
		assertEquals(Material.BLACK_STAINED_GLASS_PANE, unranked.filler().material());
		assertEquals("&2Open", config.status.get("free"));
		assertEquals("&4Taken", config.status.get("busy"));
		assertEquals("&cNo: {permission}", config.accessDenied);
		assertEquals(Material.RED_STAINED_GLASS_PANE, config.maintenanceMaterial);

		// the old "unlisted-maps" = auto-maps, with the owner's click mapping: join (MIDDLE, DROP) wins over laps-increase on DROP
		final GuiMenu.AutoMaps auto = unranked.autoMaps();

		assertEquals(19, auto.startSlot());
		assertEquals("all", auto.filter());
		assertEquals(Material.OAK_BOAT, auto.template().material());
		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_INCREASE, "")), auto.template().actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_DECREASE, "")), auto.template().actionsFor("RIGHT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), auto.template().actionsFor("MIDDLE"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), auto.template().actionsFor("DROP"));
		assertEquals(List.of(), auto.template().actionsFor("SHIFT_LEFT"), "the owner removed the shift clicks");

		// a map with its own icon
		final GuiItem monaco = item(unranked, "map-monaco");

		assertEquals("monaco", monaco.arena());
		assertEquals(List.of(11), monaco.slots());
		assertEquals(Material.DIAMOND, monaco.material());
		assertEquals(List.of("&7Laps {laps}"), monaco.lore());
		assertEquals(auto.template().actions(), monaco.actions());

		// the buttons
		assertEquals(List.of(new GuiAction(GuiAction.Type.RANDOM_MAP, "unranked")), item(unranked, "random").actionsFor("LEFT"));
		assertEquals(List.of(31), item(unranked, "random").slots());
		assertNull(item(unranked, "close_2"), "the disabled close button is not carried over as an enabled item");
		assertEquals(List.of(), item(unranked, "close").actionsFor("LEFT"), "the decoration called close stays a decoration");

		// decoration keeps no actions
		assertEquals(List.of(), item(unranked, "info").actionsFor("LEFT"));
	}

	@Test
	void competitiveButtonsBecomeRankedItems() throws Exception {
		final GuiConfig config = parse(GuiMigration.convert(yaml(OLD), shipped().getConfigurationSection("menus.ranked")), new ArrayList<>());
		final GuiMenu unranked = config.menus.get("unranked");

		final GuiItem any = item(unranked, "ranked-any");

		assertEquals("random", any.arena());
		assertTrue(any.requiresCompetitive());
		assertTrue(any.isRanked());
		assertEquals(List.of(new GuiAction(GuiAction.Type.RANKED_MAP, "")), any.actionsFor("LEFT"));
		assertEquals(List.of(29), any.slots());

		// a button that was switched off stays switched off (it is not shown)
		assertNull(item(unranked, "ranked-monaco"));
	}

	@Test
	void drawingOrderIsKept() throws Exception {
		final GuiConfig config = parse(GuiMigration.convert(yaml(OLD), null), new ArrayList<>());
		final List<String> order = config.menus.get("unranked").items().stream().map(GuiItem::id).collect(Collectors.toList());

		// decoration, then the buttons, then the competitive buttons, then the maps - later items win a slot, as before
		assertEquals(List.of("info", "close", "random", "ranked-any", "map-monaco"), order);
		assertFalse(config.menus.containsKey("ranked"), "no default ranked menu was given");
	}

	@Test
	void idsNeverCollide() throws Exception {
		final YamlConfiguration converted = GuiMigration.convert(yaml("""
				items:
				  random: { slot: 1, material: STONE }
				close: { enabled: true, slot: 2, material: BARRIER }
				random: { enabled: true, slot: 3, material: ENDER_PEARL }
				"""), null);
		final GuiConfig config = parse(converted, new ArrayList<>());
		final List<String> ids = config.menus.get("unranked").items().stream().map(GuiItem::id).collect(Collectors.toList());

		assertEquals(List.of("random", "close", "random_2"), ids);
		assertEquals(List.of(new GuiAction(GuiAction.Type.RANDOM_MAP, "unranked")), item(config.menus.get("unranked"), "random_2").actionsFor("LEFT"));
	}

	@Test
	void defaultClicksWhenTheOldFileHadNone() throws Exception {
		final GuiConfig config = parse(GuiMigration.convert(yaml("unlisted-maps: { show: true, material: OAK_BOAT }\n"), null), new ArrayList<>());
		final GuiItem template = config.menus.get("unranked").autoMaps().template();

		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_INCREASE, "")), template.actionsFor("LEFT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.LAPS_DECREASE, "")), template.actionsFor("RIGHT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), template.actionsFor("SHIFT_RIGHT"));
		assertEquals(List.of(new GuiAction(GuiAction.Type.JOIN_MAP, "")), template.actionsFor("MIDDLE"));
	}
}
