package pl.kingdomcraft.boatrace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigUpdaterTest {

	private static final String DEFAULTS = """
			# The hub
			hub-command: "spawn"
			ranking:
			  enabled: true
			  # points at stake
			  k-factor: 16
			  xp-multipliers:
			    0: 0.5
			    900: 1.0
			competitive:
			  enabled: false   # off by default
			  colors:
			    RED: { name: "Red" }
			    BLUE: { name: "Blue" }
			    GREEN: { name: "Green" }
			""";

	@Test
	void missingKeysAreAddedAndOwnValuesStay() throws InvalidConfigurationException {
		final YamlConfiguration user = yaml("""
				hub-command: "lobby"
				ranking:
				  k-factor: 32
				  xp-multipliers:
				    0: 1.0
				custom-key: 5
				""");

		final List<String> added = ConfigUpdater.merge(user, yaml(DEFAULTS), Set.of("ranking.xp-multipliers", "competitive.colors"));

		assertEquals(List.of("ranking.enabled", "competitive"), added);

		// the owner's values and own keys are untouched
		assertEquals("lobby", user.getString("hub-command"));
		assertEquals(32, user.getInt("ranking.k-factor"));
		assertEquals(5, user.getInt("custom-key"));

		// a collection the owner has is NOT filled up with the example entries
		assertEquals(Set.of("0"), user.getConfigurationSection("ranking.xp-multipliers").getKeys(false));

		// a collection that is missing completely comes as a whole, with the comments
		assertTrue(user.getBoolean("ranking.enabled"));
		assertFalse(user.getBoolean("competitive.enabled"));
		assertEquals(Set.of("RED", "BLUE", "GREEN"), user.getConfigurationSection("competitive.colors").getKeys(false));
		assertEquals(List.of("off by default"), user.getInlineComments("competitive.enabled"));

		final String saved = user.saveToString();

		assertTrue(saved.contains("custom-key: 5"));
	}

	@Test
	void anUpToDateFileIsNotChanged() throws InvalidConfigurationException {
		final YamlConfiguration user = yaml(DEFAULTS.replace("spawn", "lobby"));

		assertTrue(ConfigUpdater.merge(user, yaml(DEFAULTS), Set.of()).isEmpty());
		assertEquals("lobby", user.getString("hub-command"));
	}

	@Test
	void aValueOfAnotherTypeIsKept() throws InvalidConfigurationException {
		final YamlConfiguration user = yaml("""
				ranking: false
				""");

		final List<String> added = ConfigUpdater.merge(user, yaml(DEFAULTS), Set.of());

		assertFalse(added.contains("ranking.enabled"));
		assertFalse(user.isConfigurationSection("ranking"));
	}

	private static YamlConfiguration yaml(final String text) throws InvalidConfigurationException {
		final YamlConfiguration yaml = new YamlConfiguration();

		yaml.loadFromString(text);

		return yaml;
	}
}
