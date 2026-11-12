package pl.kingdomcraft.captainhook.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition;
import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition.SourceType;
import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.pass.Track;

class ConfigTest {

	@Test
	void bundledAchievementsAreValid() throws Exception {
		final Map<String, AchievementDefinition> definitions = AchievementDefinition.parseAll(bundled(AchievementDefinition.FILE));

		assertFalse(definitions.isEmpty());
		assertEquals(SourceType.NETWORK_STAT, definitions.get("first_blood").source().type());
		assertEquals("house-of-terror", definitions.get("terror_survivor").source().game());
		assertTrue(definitions.get("hot_escapist").isManual(), "HouseOfTerror feeds these through the API, so they cannot follow a stat");
		assertEquals(10, definitions.get("first_blood").reward().kingdomCoins());
	}

	@Test
	void bundledMissionsAreValid() throws Exception {
		final var missions = pl.kingdomcraft.captainhook.velocity.mission.MissionSettings.parse(bundled(pl.kingdomcraft.captainhook.velocity.mission.MissionSettings.FILE));

		assertTrue(missions.enabled());
		assertEquals("Europe/Warsaw", missions.zone().getId());
		assertTrue(missions.pool(pl.kingdomcraft.captainhook.velocity.mission.MissionPeriodType.DAILY).size() > missions.dailyCount());
		assertEquals(10, missions.pool(pl.kingdomcraft.captainhook.velocity.mission.MissionPeriodType.DAILY).get(0).xp(), "XP is a fifth of the design values");
	}

	@Test
	void missionMistakesNameTheKey() {
		assertMessage("missions.yml: pools.daily[0].objective must be one of", () -> pl.kingdomcraft.captainhook.velocity.mission.MissionSettings.parse(
				YamlFile.parse("missions.yml", new StringReader("pools:\n  daily:\n    - { key: a, objective: DANCE, title: { en: x } }\n"))));
		assertMessage("missions.yml: pools.daily[0].filter needs game and stat", () -> pl.kingdomcraft.captainhook.velocity.mission.MissionSettings.parse(
				YamlFile.parse("missions.yml", new StringReader("pools:\n  daily:\n    - { key: a, objective: MINIGAME_STAT, title: { en: x } }\n"))));
		assertMessage("missions.yml: pools.daily[1].key \"a\" is used twice", () -> pl.kingdomcraft.captainhook.velocity.mission.MissionSettings.parse(
				YamlFile.parse("missions.yml", new StringReader("pools:\n  daily:\n    - { key: a, objective: LOGIN, title: { en: x } }\n    - { key: a, objective: LOGIN, title: { en: y } }\n"))));
		assertMessage("missions.yml: timezone is not a time zone", () -> pl.kingdomcraft.captainhook.velocity.mission.MissionSettings.parse(
				YamlFile.parse("missions.yml", new StringReader("timezone: Mars/Olympus\n"))));
	}

	@Test
	void bundledSeasonPassIsValid() throws Exception {
		final SeasonPassSettings season = SeasonPassSettings.parse(bundled(SeasonPassSettings.FILE));

		assertTrue(season.isActive(System.currentTimeMillis() / 1000), "Empty dates mean no limit");
		assertEquals(5, season.maxLevel());
		assertEquals(2, season.levelOf(2999));
		assertEquals(5, season.levelOf(1_000_000));
		assertEquals(100, season.tiers().get(5).reward(Track.PREMIUM).kingdomCoins());
	}

	@Test
	void filesAreCreatedFromTheJarOnFirstStart(@TempDir final Path directory) throws Exception {
		final Path data = directory.resolve("captainhook");

		YamlFile.load(data, SeasonPassSettings.FILE);
		assertTrue(Files.exists(data.resolve(SeasonPassSettings.FILE)));

		Files.writeString(data.resolve(SeasonPassSettings.FILE), "enabled: false\nseason:\n  id: custom\n");
		assertEquals("custom", SeasonPassSettings.parse(YamlFile.load(data, SeasonPassSettings.FILE)).id(), "An existing file is never overwritten");
	}

	@Test
	void seasonDatesAreInclusiveInTheConfiguredZone() throws Exception {
		final ZoneId warsaw = ZoneId.of("Europe/Warsaw");

		// Quoted and unquoted dates mean the same (YAML reads an unquoted date as a timestamp)
		for (final String ends : new String[] { "\"2026-12-31\"", "2026-12-31" }) {
			final SeasonPassSettings season = season("season:\n  id: s1\n  starts: \"2026-10-01\"\n  ends: " + ends + "\n");

			assertFalse(season.isActive(LocalDate.of(2026, 10, 1).atStartOfDay(warsaw).toEpochSecond() - 1));
			assertTrue(season.isActive(LocalDate.of(2026, 10, 1).atStartOfDay(warsaw).toEpochSecond()));
			assertTrue(season.isActive(LocalDate.of(2027, 1, 1).atStartOfDay(warsaw).toEpochSecond() - 1), "The end date is the last day");
			assertFalse(season.isActive(LocalDate.of(2027, 1, 1).atStartOfDay(warsaw).toEpochSecond()));
		}

		assertFalse(season("enabled: false\nseason:\n  id: s1\n").isActive(System.currentTimeMillis() / 1000));
	}

	@Test
	void mistakesNameTheFileAndTheKey() {
		assertMessage("seasonpass.yml: season.id is required", () -> season("xp-per-level: 100\n"));
		assertMessage("seasonpass.yml: season.id may only contain", () -> season("season:\n  id: \"Sezon 1\"\n"));
		assertMessage("seasonpass.yml: xp-per-level must be at least 1", () -> season("season:\n  id: s1\nxp-per-level: 0\n"));
		assertMessage("seasonpass.yml: tiers.one must be a level number", () -> season("season:\n  id: s1\ntiers:\n  one: {}\n"));
		assertMessage("seasonpass.yml: tiers.1.free.kingdomcoins must be a whole number", () -> season("season:\n  id: s1\ntiers:\n  1:\n    free:\n      kingdomcoins: lots\n"));
		assertMessage("seasonpass.yml: season.ends must not be before starts", () -> season("season:\n  id: s1\n  starts: \"2026-02-01\"\n  ends: \"2026-01-01\"\n"));
		assertMessage("seasonpass.yml: max-level must be between the highest tier (2)", () -> season("season:\n  id: s1\nmax-level: 1\ntiers:\n  2: {}\n"));

		assertMessage("achievements.yml: achievements.Bad-ID is not a valid ID", () -> achievements("achievements:\n  Bad-ID: {}\n"));
		assertMessage("achievements.yml: achievements.a.goal must be at least 1", () -> achievements("achievements:\n  a:\n    goal: 0\n"));
		assertMessage("achievements.yml: achievements.a.source.stat must be one of", () -> achievements("achievements:\n  a:\n    source: { type: network-stat, stat: balance }\n"));
		assertMessage("achievements.yml: achievements.a.source.game is required", () -> achievements("achievements:\n  a:\n    source: { type: minigame-stat, stat: wins }\n"));
		assertMessage("achievements.yml: achievements.a.source.type must be manual", () -> achievements("achievements:\n  a:\n    source: { type: magic }\n"));
		assertMessage("achievements.yml is not valid YAML", () -> achievements("achievements: [unclosed\n"));
		assertMessage("achievements.yml: achievements.a.rewards.require-server only applies to commands",
				() -> achievements("achievements:\n  a:\n    rewards: { kingdomcoins: 5, require-server: survival }\n"));
		assertMessage("achievements.yml: achievements.a.rewards.require-server must be a server name",
				() -> achievements("achievements:\n  a:\n    rewards: { commands: [\"say hi\"], require-server: \"two words\" }\n"));
	}

	@Test
	void rewardCommandsLoseTheirSlash() throws Exception {
		final AchievementDefinition definition = achievements("achievements:\n  a:\n    rewards:\n      commands: \"/give {player} diamond 1\"\n").get("a");

		assertEquals(java.util.List.of("give {player} diamond 1"), definition.reward().commands());
		assertTrue(achievements("achievements:\n  a: {}\n").get("a").reward().isEmpty());
		assertTrue(achievements("achievements:\n  a: {}\n").get("a").isManual(), "The default source is manual");
	}

	private static SeasonPassSettings season(final String yaml) throws ConfigException {
		return SeasonPassSettings.parse(YamlFile.parse(SeasonPassSettings.FILE, new StringReader(yaml)));
	}

	private static Map<String, AchievementDefinition> achievements(final String yaml) throws ConfigException {
		return AchievementDefinition.parseAll(YamlFile.parse(AchievementDefinition.FILE, new StringReader(yaml)));
	}

	private static YamlFile bundled(final String name) throws Exception {
		try (Reader reader = new InputStreamReader(ConfigTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8)) {
			return YamlFile.parse(name, reader);
		}
	}

	@FunctionalInterface
	private interface Parsing {
		void run() throws ConfigException;
	}

	private static void assertMessage(final String expected, final Parsing parsing) {
		final ConfigException error = assertThrows(ConfigException.class, parsing::run);

		assertTrue(error.getMessage().startsWith(expected), "Expected a message starting with \"" + expected + "\", got \"" + error.getMessage() + "\"");
	}
}
