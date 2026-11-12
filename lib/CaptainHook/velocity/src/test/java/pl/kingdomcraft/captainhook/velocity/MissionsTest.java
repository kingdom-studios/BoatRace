package pl.kingdomcraft.captainhook.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.database.Tables;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi.StatChange;
import pl.kingdomcraft.captainhook.velocity.minigame.StatOperation;
import pl.kingdomcraft.captainhook.velocity.mission.Assignment;
import pl.kingdomcraft.captainhook.velocity.mission.MissionAdmin;
import pl.kingdomcraft.captainhook.velocity.mission.MissionDefinition;
import pl.kingdomcraft.captainhook.velocity.mission.MissionFilter;
import pl.kingdomcraft.captainhook.velocity.mission.MissionPeriod;
import pl.kingdomcraft.captainhook.velocity.mission.MissionPeriodType;
import pl.kingdomcraft.captainhook.velocity.mission.MissionSelector;
import pl.kingdomcraft.captainhook.velocity.mission.MissionSettings;
import pl.kingdomcraft.captainhook.velocity.mission.MissionStore;
import pl.kingdomcraft.captainhook.velocity.mission.Missions;
import pl.kingdomcraft.captainhook.velocity.mission.Objective;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.pass.Track;

/**
 * Missions: the draw, the periods, counting and the exactly-once XP, on SQLite (or MySQL, see TestDatabases).
 */
class MissionsTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(MissionsTest.class);
	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	private static final String MISSIONS_YAML = """
			timezone: "Europe/Warsaw"
			daily-count: 3
			weekly-count: 2
			pools:
			  daily:
			    - { key: login, always: true, objective: LOGIN, target: 1, xp: 10, icon: login, title: { en: "Log in", pl: "Wejdź" } }
			    - { key: play, objective: PLAYTIME_MINUTES, target: 3, xp: 30, icon: clock, unit: { en: "min" }, title: { en: "Play", pl: "Graj" }, weight: 10 }
			    - { key: mine, objective: BLOCK_BREAK, target: 5, xp: 20, filter: { material: [STONE, DEEPSLATE] }, title: { en: "Mine" } }
			    - { key: quests, objective: ROLEPLAY_QUEST_COMPLETE, target: 2, xp: 40, filter: { server: roleplay }, title: { en: "Quests" } }
			    - { key: vote, objective: VOTE, target: 1, xp: 20, icon: vote, title: { en: "Vote" } }
			    - { key: hot, objective: MINIGAME_STAT, target: 4, xp: 25, filter: { game: house-of-terror, stat: kills }, title: { en: "Kill" } }
			  weekly:
			    - { key: days, objective: DISTINCT_DAYS, target: 2, xp: 80, title: { en: "Days" } }
			    - { key: play-week, objective: PLAYTIME_MINUTES, target: 10, xp: 90, title: { en: "Play more" } }
			  season:
			    - { key: season-days, objective: DISTINCT_DAYS, target: 3, xp: 300, title: { en: "Season days" } }
			    - { key: fast, objective: MINIGAME_STAT, target: 2, xp: 40, filter: { game: formula-boat-racing, stat: top_avg_speed, min-value: 500 }, title: { en: "Fast" } }
			""";

	private static final String SEASON_YAML = """
			season: { id: "ms", name: "Missions season" }
			xp-per-level: 1000
			tiers:
			  1: { free: { kingdomcoins: 5 } }
			  2: { free: { kingdomcoins: 5 } }
			""";

	@TempDir
	Path directory;

	private Database database;
	private FakeNetwork network;
	private KingdomCoins coins;
	private SeasonPass seasonPass;
	private Missions missions;
	private MinigameApi minigames;
	private final AtomicLong clock = new AtomicLong(ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, WARSAW).toEpochSecond()); // Monday

	@BeforeEach
	void start() throws Exception {
		this.database = TestDatabases.open(this.directory);
		this.network = new FakeNetwork();
		this.coins = new KingdomCoins(this.database, this.network, LOGGER);
		this.seasonPass = new SeasonPass(this.database, this.coins, this.network, season(SEASON_YAML));

		final Achievements achievements = new Achievements(this.database, this.seasonPass, this.network, java.util.Map.of());

		this.missions = new Missions(this.database, this.seasonPass, this.network, LOGGER, settings(MISSIONS_YAML), this.clock::get);
		this.minigames = new MinigameApi(this.database, achievements, this.network, LOGGER, 60);
		this.minigames.setMissions(this.missions);
	}

	@AfterEach
	void stop() {
		this.database.shutdown();
	}

	/* ------------------------------------------------------------------------------- */
	/* Pure logic */
	/* ------------------------------------------------------------------------------- */

	@Test
	void theDrawOnlyDependsOnThePoolAndTheSeed() throws Exception {
		final List<MissionDefinition> pool = new ArrayList<>(settings(MISSIONS_YAML).pool(MissionPeriodType.DAILY));
		final List<String> first = keys(MissionSelector.select(pool, 3, MissionSelector.seed("ms|daily|2026-10-12")));

		assertEquals("login", first.get(0), "Missions marked always come first");
		assertEquals(3, first.size());
		assertEquals(3, first.stream().distinct().count());

		java.util.Collections.reverse(pool);
		assertEquals(first, keys(MissionSelector.select(pool, 3, MissionSelector.seed("ms|daily|2026-10-12"))), "The order in the file does not change the draw");

		final java.util.Set<List<String>> draws = new java.util.HashSet<>();

		for (int day = 1; day <= 30; day++)
			draws.add(keys(MissionSelector.select(pool, 3, MissionSelector.seed("ms|daily|2026-11-" + String.format("%02d", day)))));

		assertTrue(draws.size() > 3, "Different days draw different missions");
		assertEquals(6, keys(MissionSelector.select(pool, 99, 1)).size(), "Never more than the pool holds");
		assertEquals(List.of("login"), keys(MissionSelector.select(pool, 0, 1)).subList(0, 1), "Always missions are taken even above the count");
	}

	@Test
	void periodsFollowTheClockOfWarsawIncludingClockChanges() {
		final long monday = ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, WARSAW).toEpochSecond();

		final MissionPeriod day = MissionPeriod.of(MissionPeriodType.DAILY, monday + 3600, WARSAW);

		assertEquals("2026-10-12", day.key());
		assertEquals(monday, day.startsAt());
		assertEquals(monday + 86_400, day.endsAt());

		assertEquals("2026-10-12", MissionPeriod.of(MissionPeriodType.DAILY, monday + 86_399, WARSAW).key(), "Last second of the day");
		assertEquals("2026-10-13", MissionPeriod.of(MissionPeriodType.DAILY, monday + 86_400, WARSAW).key(), "Midnight starts the next day");

		// Sunday night belongs to the old week, Monday 00:00 starts the new one
		final MissionPeriod week = MissionPeriod.of(MissionPeriodType.WEEKLY, monday + 6 * 86_400 + 80_000, WARSAW);

		assertEquals("2026-W42", week.key());
		assertEquals(monday, week.startsAt());
		assertEquals("2026-W43", MissionPeriod.of(MissionPeriodType.WEEKLY, monday + 7 * 86_400, WARSAW).key());

		// 2026-03-29: clocks go forward, the day is 23 hours; 2026-10-25: they go back, 25 hours
		final MissionPeriod spring = MissionPeriod.of(MissionPeriodType.DAILY, ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, WARSAW).toEpochSecond(), WARSAW);
		final MissionPeriod autumn = MissionPeriod.of(MissionPeriodType.DAILY, ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, WARSAW).toEpochSecond(), WARSAW);

		assertEquals(23 * 3600, spring.endsAt() - spring.startsAt());
		assertEquals(25 * 3600, autumn.endsAt() - autumn.startsAt());
		assertEquals("2026-03-30", MissionPeriod.of(MissionPeriodType.DAILY, spring.endsAt(), WARSAW).key());

		// ISO weeks: 2026 has 53, 2027 starts in the middle of week 53
		assertEquals("2026-W53", MissionPeriod.of(MissionPeriodType.WEEKLY, ZonedDateTime.of(2026, 12, 29, 12, 0, 0, 0, WARSAW).toEpochSecond(), WARSAW).key());
		assertEquals("2026-W53", MissionPeriod.of(MissionPeriodType.WEEKLY, ZonedDateTime.of(2027, 1, 2, 12, 0, 0, 0, WARSAW).toEpochSecond(), WARSAW).key());
		assertEquals("2027-W01", MissionPeriod.of(MissionPeriodType.WEEKLY, ZonedDateTime.of(2027, 1, 4, 0, 0, 0, 0, WARSAW).toEpochSecond(), WARSAW).key());

		assertEquals("2026-10-12", MissionPeriod.baseKey("2026-10-12.3"));
		assertEquals(3, MissionPeriod.revision("2026-10-12.3"));
		assertEquals(0, MissionPeriod.revision("2026-10-12"));
	}

	@Test
	void theXpCurveGrowsAndTiersCanOverrideIt() throws Exception {
		final SeasonPassSettings flat = season(SEASON_YAML);

		assertEquals(List.of(1000L, 2000L), flat.thresholds());

		final SeasonPassSettings curved = season("""
				season: { id: c }
				xp-per-level: 1000
				xp-increase-per-level: 250
				max-level: 5
				tiers:
				  3: { xp: 4000 }
				""");

		// 1000, +1250, +4000 (override of level 3), +1750, +2000
		assertEquals(List.of(1000L, 2250L, 6250L, 8000L, 10_000L), curved.thresholds());
		assertEquals(0, curved.levelOf(999));
		assertEquals(1, curved.levelOf(1000));
		assertEquals(2, curved.levelOf(6249));
		assertEquals(3, curved.levelOf(6250));
		assertEquals(5, curved.levelOf(1_000_000), "XP above the last level changes nothing");
		assertEquals(250, curved.xpIntoLevel(2500));
		assertEquals(4000, curved.xpForNextLevel(2500));
		assertEquals(0, curved.xpIntoLevel(10_000), "No progress bar at the highest level");
	}

	/* ------------------------------------------------------------------------------- */
	/* Rotation */
	/* ------------------------------------------------------------------------------- */

	@Test
	void everyPeriodIsDrawnOnceAndStaysTheSame() throws Exception {
		await(this.missions.rotate());

		final List<String> first = this.activeKeys();

		assertEquals(3 + 2 + 2, this.missions.getActive().size());
		assertEquals(3, this.missions.getActive().stream().filter(assignment -> assignment.type() == MissionPeriodType.DAILY).count());
		assertEquals("2026-10-12", this.missions.getActive().get(0).periodKey());

		// A second proxy start, a reload, a crash: the same rows, no duplicates
		await(this.missions.rotate());
		this.missions.reload(settings(MISSIONS_YAML));
		await(this.missions.rotate());

		final Missions restarted = new Missions(this.database, this.seasonPass, this.network, LOGGER, settings(MISSIONS_YAML), this.clock::get);

		await(restarted.rotate());
		assertEquals(first, this.activeKeys());
		assertEquals(first, restarted.getActive().stream().map(a -> a.type() + ":" + a.missionKey()).sorted().collect(Collectors.toList()));
		assertEquals(7, Long.parseLong(this.text("SELECT COUNT(*) FROM " + Tables.MISSION_ASSIGNMENTS))); // 3 daily + 2 weekly + every season mission (2)

		// Midnight: a new day is drawn, the week and the season stay
		this.clock.addAndGet(14 * 3600); // 00:00 Tuesday
		await(this.missions.rotate());

		assertEquals(List.of("2026-10-13"), this.missions.getActive().stream().filter(a -> a.type() == MissionPeriodType.DAILY).map(Assignment::periodKey).distinct().toList());
		assertEquals("2026-W42", this.missions.getActive().stream().filter(a -> a.type() == MissionPeriodType.WEEKLY).findFirst().get().periodKey());

		// Monday midnight: a new week
		this.clock.addAndGet(6 * 86_400L);
		await(this.missions.rotate());
		assertEquals("2026-W43", this.missions.getActive().stream().filter(a -> a.type() == MissionPeriodType.WEEKLY).findFirst().get().periodKey());
		assertEquals(3, this.missions.getActive().stream().filter(a -> a.type() == MissionPeriodType.SEASON).findFirst().get().id() > 0 ? 3 : 0, "The season missions never change");
	}

	@Test
	void anAdminCanDrawNewMissionsAndOnlyTheNewOnesCount() throws Exception {
		await(this.missions.rotate());

		final List<String> before = this.missions.getActive().stream().filter(a -> a.type() == MissionPeriodType.DAILY).map(Assignment::missionKey).sorted().toList();
		final UUID alice = this.player("Alice");

		this.missions.record(alice, Objective.LOGIN, MissionFilter.Event.ofServer(null), 1);
		await(this.missions.reroll(MissionPeriodType.DAILY));

		final List<Assignment> daily = this.missions.getActive().stream().filter(a -> a.type() == MissionPeriodType.DAILY).toList();

		assertEquals(List.of("2026-10-12.1"), daily.stream().map(Assignment::periodKey).distinct().toList());
		assertEquals(3, daily.size());
		assertEquals("login", daily.stream().map(Assignment::missionKey).sorted().toList().get(0), "Always missions stay");
		assertTrue(before.size() == 3);

		assertThrows(ServiceException.class, () -> this.missions.reroll(MissionPeriodType.SEASON));

		// A restart keeps the re-rolled set
		final Missions restarted = new Missions(this.database, this.seasonPass, this.network, LOGGER, settings(MISSIONS_YAML), this.clock::get);

		await(restarted.rotate());
		assertEquals(List.of("2026-10-12.1"), restarted.getActive().stream().filter(a -> a.type() == MissionPeriodType.DAILY).map(Assignment::periodKey).distinct().toList());
	}

	/* ------------------------------------------------------------------------------- */
	/* Counting and XP */
	/* ------------------------------------------------------------------------------- */

	@Test
	void completingAMissionGrantsItsXpExactlyOnce() throws Exception {
		this.useOnly("play");

		final UUID alice = this.player("Alice");
		final Assignment play = this.assignment("play");

		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("survival"), 2);
		await(this.missions.flush());
		assertEquals(0, this.xp(alice));
		assertEquals(2L, this.progress(alice, play));

		// 1 more minute reaches the target of 3, 5 more are capped
		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("survival"), 1);
		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("survival"), 5);
		await(this.missions.flush());

		assertEquals(30, this.xp(alice));
		assertEquals(3, this.progress(alice, play));
		assertTrue(this.completed(alice, play));

		// Counting on after the completion, a repeated flush and a restarted proxy change nothing
		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("survival"), 10);
		await(this.missions.flush());
		await(this.missions.flush());

		final Missions restarted = new Missions(this.database, this.seasonPass, this.network, LOGGER, settings(MISSIONS_YAML), this.clock::get);

		await(restarted.rotate());
		restarted.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("survival"), 10);
		await(restarted.flush());

		// 10 more minutes finish the weekly "play 10 minutes" mission (90 XP), but the daily one pays nothing more
		assertEquals(30 + 90, this.xp(alice));
		assertEquals(30, Long.parseLong(this.text("SELECT COALESCE(SUM(amount), 0) FROM " + Tables.XP_LEDGER + " WHERE source_id = '" + play.id() + "'")));
		assertEquals(120, Long.parseLong(this.text("SELECT COALESCE(SUM(amount), 0) FROM " + Tables.XP_LEDGER + " WHERE uuid = '" + alice + "'")));
		assertEquals(2, this.network.sent(alice, Protocol.MISSION_COMPLETED).size(), "The player is told once per mission");

		final JsonObject done = this.network.sent(alice, Protocol.MISSION_COMPLETED).stream().filter(d -> Json.getString(d, "id", "").equals("play")).findFirst().get();

		assertEquals("play", Json.getString(done, "id", ""));
		assertEquals(30, Json.getLong(done, "xp", 0));
		assertEquals("Graj", Json.getString(done, "titlePl", ""));
		assertEquals(30, Json.getLong(this.network.sent(alice, Protocol.PASS_UPDATE).get(0), "xp", 0));
		assertEquals(30, Json.getLong(this.network.sent(alice, Protocol.PASS_UPDATE).get(0), "gained", 0));

		// The ledger itself refuses a second grant of the same mission
		assertFalse(this.database.submit(connection -> MissionStore.insertLedger(connection, alice, "ms", 30, "mission", String.valueOf(play.id()), this.database.isSqlite())).get(10, TimeUnit.SECONDS));
	}

	@Test
	void progressFromManyServersAddsUpAndCrashesDoNotLoseIt() throws Exception {
		this.useOnly("play");

		final UUID alice = this.player("Alice");
		final Assignment play = this.assignment("play");

		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("survival"), 1);
		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("skyblock"), 1);
		await(this.missions.flush());

		assertEquals(2L, this.progress(alice, play));

		// A flush that fails keeps the progress for the next one
		this.missions.record(alice, Objective.PLAYTIME_MINUTES, MissionFilter.Event.ofServer("hub"), 1);

		final Database broken = this.database;
		final Missions failing = new Missions(broken, this.seasonPass, this.network, LOGGER, settings(MISSIONS_YAML), this.clock::get);

		await(failing.rotate());
		failing.record(alice, Objective.BLOCK_BREAK, new MissionFilter.Event("hub", "world", "STONE", null, null, null), 2);
		broken.submit(connection -> {
			throw new java.sql.SQLException("simulated outage");
		}).handle((a, b) -> null).get(5, TimeUnit.SECONDS);

		await(this.missions.flush());
		assertEquals(3, this.progress(alice, play));
		assertTrue(this.completed(alice, play));
	}

	@Test
	void filtersDecideWhatCounts() throws Exception {
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");

		this.useOnly("mine", "quests");

		final Assignment mine = this.assignment("mine");
		final Assignment quests = this.assignment("quests");

		for (final String material : new String[] { "STONE", "stone", "DEEPSLATE", "DIRT" })
			this.missions.record(alice, Objective.BLOCK_BREAK, new MissionFilter.Event("survival", "world", material, null, null, null), 1);

		this.missions.record(alice, Objective.ROLEPLAY_QUEST_COMPLETE, MissionFilter.Event.ofServer("survival"), 2); // wrong server
		this.missions.record(alice, Objective.ROLEPLAY_QUEST_COMPLETE, MissionFilter.Event.ofServer("roleplay"), 1);
		this.missions.record(alice, Objective.MOB_KILL, MissionFilter.Event.ofServer("roleplay"), 9); // wrong objective
		await(this.missions.flush());

		assertEquals(3L, this.progress(alice, mine), "STONE (any case) and DEEPSLATE count, DIRT does not");
		assertEquals(1L, this.progress(alice, quests));
	}

	@Test
	void minValueMissionsCountReportsThatReachTheThreshold() throws Exception {
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");

		this.useOnly("fast");

		final Assignment fast = this.assignment("fast");

		// max reports the value of THIS race, even when it is below the stored best
		await(this.minigames.recordStats("formula-boat-racing", List.of(new StatChange(alice, "top_avg_speed", StatOperation.MAX, 620))));
		await(this.minigames.recordStats("formula-boat-racing", List.of(new StatChange(alice, "top_avg_speed", StatOperation.MAX, 480),
				new StatChange(alice, "races", StatOperation.ADD, 900))));
		await(this.missions.flush());

		assertEquals(1L, this.progress(alice, fast));

		await(this.minigames.recordStats("formula-boat-racing", List.of(new StatChange(alice, "top_avg_speed", StatOperation.MAX, 500))));
		await(this.missions.flush());

		assertEquals(40, this.xp(alice));
	}

	@Test
	void minigameCountersFeedMissionsAndOtherOperationsDoNot() throws Exception {
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");

		this.useOnly("hot");

		final Assignment hot = this.assignment("hot");

		// The id of the House of Terror plugin has hyphens
		await(this.minigames.recordStats("house-of-terror", List.of(new StatChange(alice, "kills", StatOperation.ADD, 3), new StatChange(alice, "kills", StatOperation.MAX, 50),
				new StatChange(alice, "wins", StatOperation.ADD, 9))));
		await(this.missions.flush());

		assertEquals(3L, this.progress(alice, hot));
		assertEquals(Map3.of("kills", 50L, "wins", 9L), await(this.minigames.getStats("house-of-terror", alice)));

		await(this.minigames.recordStats("house-of-terror", List.of(new StatChange(alice, "kills", StatOperation.ADD, 1))));
		await(this.missions.flush());

		assertEquals(25, this.xp(alice));
		assertThrows(ServiceException.class, () -> this.minigames.recordStats("House Of Terror", List.of()), "Games still may not contain spaces or capitals");
	}

	@Test
	void loginCountsOncePerDayInWarsawTime() throws Exception {
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");

		this.useOnly("login", "days", "season-days");

		final Assignment login = this.assignment("login");
		final Assignment days = this.assignment("days");

		await(this.missions.onLogin(alice));
		await(this.missions.onLogin(alice));
		await(this.missions.flush());

		assertEquals(1L, this.progress(alice, login), "Logging in again the same day does not count again");
		assertEquals(1L, this.progress(alice, days));
		assertEquals(10, this.xp(alice));

		// 23:30 and 00:30 are different days even though they are an hour apart
		this.clock.set(ZonedDateTime.of(2026, 10, 12, 23, 30, 0, 0, WARSAW).toEpochSecond());
		await(this.missions.onLogin(alice));
		this.clock.set(ZonedDateTime.of(2026, 10, 13, 0, 30, 0, 0, WARSAW).toEpochSecond());
		await(this.missions.rotate());
		await(this.missions.onLogin(alice));
		await(this.missions.flush());

		assertEquals(2L, this.progress(alice, days), "A second day in the week");
		assertEquals(10 + 80 + 10, this.xp(alice), "The weekly mission of 2 days completes, and the new day has its own login mission");
	}

	@Test
	void nothingCountsOutsideASeason() throws Exception {
		final LocalDate yesterday = LocalDate.now().minusDays(1);

		this.seasonPass.reload(season("season: { id: over, ends: \"" + yesterday + "\" }\ntiers:\n  1: { free: { kingdomcoins: 1 } }\n"));
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");

		assertTrue(this.missions.getActive().isEmpty());
		this.missions.record(alice, Objective.LOGIN, MissionFilter.Event.ofServer(null), 1);
		await(this.missions.onLogin(alice));
		await(this.missions.flush());
		assertEquals(0, Long.parseLong(this.text("SELECT COUNT(*) FROM " + Tables.MISSION_PROGRESS)));
	}

	@Test
	void theVoteEventOfNuVotifierCountsForTheVoter() throws Exception {
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");

		this.useOnly("vote");

		final ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ProxyServer.class }, (object, method, args) -> {
			if (method.getName().equals("getPlayer"))
				return Optional.empty();

			throw new UnsupportedOperationException(method.getName());
		});
		final VoteListener listener = new VoteListener(proxy, this.missions, this.coins, LOGGER);

		listener.onVote(new FakeVotifierEvent("alice")); // offline voter: found by name
		listener.onVote(new FakeVotifierEvent("Nobody")); // never joined: ignored
		listener.onVote(new Object()); // not a vote: logged, no crash
		this.flushAll();
		await(this.missions.flush());

		assertEquals(20, this.xp(alice));
		assertFalse(listener.register(new Object(), "no.such.Event"), "Without NuVotifier there is nothing to register");
	}

	/* ------------------------------------------------------------------------------- */
	/* Admin, claim window */
	/* ------------------------------------------------------------------------------- */

	@Test
	void adminActionsGoThroughTheLedger() throws Exception {
		await(this.missions.rotate());

		final UUID alice = this.player("Alice");
		final MissionAdmin admin = new MissionAdmin(this.missions, this.coins, () -> "ok");

		assertTrue(await(admin.run("xp_add", "alice", "250", 0)).get("message").getAsString().contains("250 XP"));
		await(admin.run("xp_add", "Alice", "50", 0));
		await(admin.run("xp_set", "Alice", "100", 0));
		assertEquals(100, this.xp(alice));
		assertEquals(100, Long.parseLong(this.text("SELECT COALESCE(SUM(amount), 0) FROM " + Tables.XP_LEDGER + " WHERE uuid = '" + alice + "'")), "250 + 50 - 200: the ledger adds up to the XP");

		this.useOnly("vote");
		await(admin.run("complete", "ALICE", "vote", 0));
		assertEquals(120, this.xp(alice));
		await(admin.run("complete", "Alice", "vote", 0));
		assertEquals(120, this.xp(alice), "Completing twice grants once");

		await(admin.run("progress", "Alice", "PLAYTIME_MINUTES", 0 + 3)); // unconditional counters only
		assertThrows(IllegalArgumentException.class, () -> admin.run("explode", "Alice", null, 0));
		assertThrows(IllegalArgumentException.class, () -> admin.run("rotate", null, "monthly", 0));
		assertThrows(java.util.concurrent.ExecutionException.class, () -> admin.run("complete", "Alice", "nope", 0).get(5, TimeUnit.SECONDS));
		assertThrows(java.util.concurrent.ExecutionException.class, () -> admin.run("xp_add", "Ghost", "5", 0).get(5, TimeUnit.SECONDS));
	}

	@Test
	void rewardsCanBeClaimedForSevenDaysAfterTheEndButNoXpIsEarned() throws Exception {
		final LocalDate endedThreeDaysAgo = LocalDate.now().minusDays(3);
		final String yaml = "season: { id: gone, ends: \"" + endedThreeDaysAgo + "\", claim-grace-days: 7 }\nxp-per-level: 100\ntiers:\n  1: { free: { kingdomcoins: 7, pass-xp: 50 } }\n";
		final SeasonPassSettings ended = season(yaml);
		final long now = System.currentTimeMillis() / 1000;

		assertFalse(ended.isActive(now));
		assertTrue(ended.isClaimable(now));
		assertFalse(ended.isClaimable(ended.endsAt() + 7 * 86_400L), "Seven days after the end it is over");
		assertTrue(ended.isClaimable(ended.endsAt() + 7 * 86_400L - 1));
		assertFalse(season(yaml.replace("claim-grace-days: 7", "claim-grace-days: 0")).isClaimable(now));

		// XP was earned while the season ran
		final UUID alice = this.player("Alice");

		this.seasonPass.reload(season(yaml.replace("ends: \"" + endedThreeDaysAgo + "\", ", "")));
		await(this.seasonPass.addXp(alice, 150));
		this.seasonPass.reload(ended);

		assertThrows(ServiceException.class, () -> this.seasonPass.addXp(alice, 10), "No XP after the end");
		assertEquals(SeasonPass.ClaimStatus.SUCCESS, await(this.seasonPass.claim(alice, 1, Track.FREE, "game")).status(), "But the reward can be claimed");
		assertEquals(7, await(this.coins.find("Alice")).balance());
		assertEquals("150", this.text("SELECT xp FROM " + Tables.SEASON_PASS + " WHERE uuid = '" + alice + "'"), "The pass XP of the reward is not added after the end");
	}

	/* ------------------------------------------------------------------------------- */
	/* Helpers */
	/* ------------------------------------------------------------------------------- */

	/** Maps in assertions without importing Map twice. */
	private static final class Map3 {
		static java.util.Map<String, Long> of(final String k1, final long v1, final String k2, final long v2) {
			return java.util.Map.of(k1, v1, k2, v2);
		}
	}

	/** Looks like NuVotifier's VotifierEvent: getVote().getUsername(). */
	public static final class FakeVotifierEvent {
		private final Vote vote;

		FakeVotifierEvent(final String username) {
			this.vote = new Vote(username);
		}

		public Vote getVote() {
			return this.vote;
		}

		public static final class Vote {
			private final String username;

			Vote(final String username) {
				this.username = username;
			}

			public String getUsername() {
				return this.username;
			}
		}
	}

	private static MissionSettings settings(final String yaml) throws Exception {
		return MissionSettings.parse(YamlFile.parse("missions.yml", new StringReader(yaml)));
	}

	private static SeasonPassSettings season(final String yaml) throws Exception {
		return SeasonPassSettings.parse(YamlFile.parse("seasonpass.yml", new StringReader(yaml)));
	}

	private static List<String> keys(final List<MissionDefinition> missions) {
		return missions.stream().map(MissionDefinition::key).toList();
	}

	private List<String> activeKeys() {
		return this.missions.getActive().stream().map(a -> a.type() + ":" + a.missionKey()).sorted().collect(Collectors.toList());
	}

	private Assignment assignment(final String key) {
		return this.missions.getActive().stream().filter(a -> a.missionKey().equals(key)).findFirst().orElseThrow(() -> new AssertionError("No active mission " + key + " in " + this.activeKeys()));
	}

	/**
	 * Makes sure these missions are active (the draw may have left them out) by storing them for the current periods.
	 */
	private void useOnly(final String... keys) throws Exception {
		await(this.missions.rotate());

		final List<String> wanted = List.of(keys);
		final boolean sqlite = this.database.isSqlite();
		final long now = this.clock.get();
		final MissionSettings config = settings(MISSIONS_YAML);

		this.database.submit(connection -> {
			for (final MissionPeriodType type : MissionPeriodType.values()) {
				final List<MissionDefinition> missing = config.pool(type).stream().filter(m -> wanted.contains(m.key())).toList();

				if (!missing.isEmpty()) {
					final MissionPeriod period = type == MissionPeriodType.SEASON ? MissionPeriod.ofSeason(0, Long.MAX_VALUE) : MissionPeriod.of(type, now, WARSAW);
					final String existing = type == MissionPeriodType.SEASON ? period.key() : period.key();

					MissionStore.insertAssignments(connection, "ms", new MissionPeriod(type, this.latestKey(connection, type, existing), period.startsAt(), period.endsAt()), missing, sqlite);
				}
			}

			return null;
		}).get(10, TimeUnit.SECONDS);

		this.missions.reload(config);
		await(this.missions.rotate());
	}

	private String latestKey(final java.sql.Connection connection, final MissionPeriodType type, final String base) throws java.sql.SQLException {
		return MissionStore.periodKeys(connection, "ms", type, base).stream().max(java.util.Comparator.comparingInt(MissionPeriod::revision)).orElse(base);
	}

	private UUID player(final String name) throws Exception {
		final UUID uuid = this.network.join("survival");

		await(this.coins.load(uuid, name));
		return uuid;
	}

	private long xp(final UUID uuid) throws Exception {
		return await(this.seasonPass.getProgress(uuid)).state().xp();
	}

	/**
	 * @return the progress of the player's row, 0 without a row
	 */
	private long progress(final UUID uuid, final Assignment assignment) throws Exception {
		final String row = this.text("SELECT progress FROM " + Tables.MISSION_PROGRESS + " WHERE uuid = '" + uuid + "' AND assignment_id = " + assignment.id());

		return row.isEmpty() ? 0 : Long.parseLong(row);
	}

	private boolean completed(final UUID uuid, final Assignment assignment) throws Exception {
		return !this.text("SELECT completed_at FROM " + Tables.MISSION_PROGRESS + " WHERE uuid = '" + uuid + "' AND assignment_id = " + assignment.id() + " AND completed_at IS NOT NULL").isEmpty();
	}

	private void flushAll() throws Exception {
		this.database.submit(connection -> null).get(10, TimeUnit.SECONDS);
	}

	private String text(final String sql) throws Exception {
		return this.database.submit(connection -> {
			final List<String> rows = new ArrayList<>();

			try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
				while (result.next())
					rows.add(result.getString(1));
			}

			return String.join(",", rows);
		}).get(10, TimeUnit.SECONDS);
	}

	private static <T> T await(final CompletableFuture<T> future) throws Exception {
		return future.get(10, TimeUnit.SECONDS);
	}
}
