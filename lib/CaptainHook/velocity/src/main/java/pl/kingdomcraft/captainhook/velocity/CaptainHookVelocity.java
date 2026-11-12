package pl.kingdomcraft.captainhook.velocity;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import pl.kingdomcraft.captainhook.velocity.achievement.AchievementDefinition;
import pl.kingdomcraft.captainhook.velocity.achievement.Achievements;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.command.ProxyCommand;
import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.ProxyConfig;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.messaging.BackendMessenger;
import pl.kingdomcraft.captainhook.velocity.messaging.RequestRouter;
import pl.kingdomcraft.captainhook.velocity.minigame.MinigameApi;
import pl.kingdomcraft.captainhook.velocity.mission.MissionSettings;
import pl.kingdomcraft.captainhook.velocity.mission.Missions;
import pl.kingdomcraft.captainhook.velocity.mission.MissionAdmin;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPass;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;
import pl.kingdomcraft.captainhook.velocity.reward.RewardDispatcher;
import pl.kingdomcraft.captainhook.velocity.stats.StatsService;
import pl.kingdomcraft.captainhook.velocity.website.DefinitionPublisher;

/**
 * Captain Hook on the proxy - the master of the network. It owns the only database connection and runs
 * everything network-wide: player stats, KingdomCoins, the minigame API, achievements and the season pass.
 * The Paper servers run the CaptainHook Bukkit plugin, a client that talks to this one over plugin messages.
 *
 * The plugin descriptor is src/main/resources/velocity-plugin.json (no @Plugin annotation, the version comes
 * from the pom).
 */
public final class CaptainHookVelocity {

	public static final String ADMIN_PERMISSION = "captainhook.admin";

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;
	private final List<ScheduledTask> tasks = new ArrayList<>();

	private Database database;
	private BackendMessenger messenger;
	private KingdomCoins coins;
	private SeasonPass seasonPass;
	private Achievements achievements;
	private StatsService stats;
	private MinigameApi minigames;
	private RewardDispatcher rewards;
	private Missions missions;
	private MissionAdmin missionAdmin;

	/**
	 * False when the plugin could not start (bad configuration, no database): it then does nothing at all.
	 */
	private volatile boolean enabled;

	@Inject
	public CaptainHookVelocity(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	/* ------------------------------------------------------------------------------- */
	/* Lifecycle */
	/* ------------------------------------------------------------------------------- */

	@Subscribe
	public void onProxyInitialize(final ProxyInitializeEvent event) {
		try {
			this.start();

		} catch (final ConfigException ex) {
			this.logger.error("CaptainHook is disabled, fix the configuration and restart the proxy: {}", ex.getMessage());

		} catch (final SQLException ex) {
			this.logger.error("CaptainHook is disabled, the database could not be opened", ex);
		}
	}

	private void start() throws ConfigException, SQLException {
		final ProxyConfig config = ProxyConfig.load(this.dataDirectory);
		final SeasonPassSettings season = this.loadSeasonPass();
		final Map<String, AchievementDefinition> definitions = this.loadAchievements();
		final MissionSettings missionSettings = MissionSettings.load(this.dataDirectory);

		this.database = Database.open(config.database(), this.dataDirectory, this.logger);
		this.messenger = new BackendMessenger(this.proxy, this.logger);
		this.coins = new KingdomCoins(this.database, this.messenger, this.logger);
		this.seasonPass = new SeasonPass(this.database, this.coins, this.messenger, season);
		this.achievements = new Achievements(this.database, this.seasonPass, this.messenger, definitions);
		this.stats = new StatsService(this.database, this.achievements, this.messenger, this.logger);
		this.minigames = new MinigameApi(this.database, this.achievements, this.messenger, this.logger, config.pendingJoinTtlSeconds());
		this.rewards = new RewardDispatcher(this.database, this.messenger, this.logger, config.rewardRetrySeconds());
		this.missions = new Missions(this.database, this.seasonPass, this.messenger, this.logger, missionSettings);
		this.missionAdmin = new MissionAdmin(this.missions, this.coins, this::reload);
		this.minigames.setMissions(this.missions);
		this.publishDefinitions(season, definitions);

		// Nobody is online before the proxy is up; a crash or kill left the flags set
		this.stats.markAllOffline().whenComplete((players, error) -> {
			if (error != null)
				this.logger.warn("Could not reset online flags: {}", error.toString());
			else if (players > 0)
				this.logger.info("Marked {} player(s) offline that were left online by the last shutdown.", players);
		});

		this.messenger.start(new RequestRouter(this.messenger, this.database, this.coins, this.stats, this.seasonPass, this.achievements, this.minigames,
				this.rewards, this.missions, this.missionAdmin, this.logger, this.getVersion()));
		this.proxy.getEventManager().register(this, this.messenger);

		if (new VoteListener(this.proxy, this.missions, this.coins, this.logger).register(this))
			this.logger.info("NuVotifier found: votes count for VOTE missions.");

		this.missions.rotate();

		this.tasks.add(this.proxy.getScheduler().buildTask(this, this.coins::refresh)
				.delay(1, TimeUnit.SECONDS).repeat(config.coinsRefreshSeconds(), TimeUnit.SECONDS).schedule());
		this.tasks.add(this.proxy.getScheduler().buildTask(this, this.minigames::purgeExpiredJoins)
				.delay(1, TimeUnit.MINUTES).repeat(1, TimeUnit.MINUTES).schedule());
		this.tasks.add(this.proxy.getScheduler().buildTask(this, this.rewards::tick)
				.delay(config.rewardDispatchSeconds(), TimeUnit.SECONDS).repeat(config.rewardDispatchSeconds(), TimeUnit.SECONDS).schedule());

		// The draw of a new period (midnight, Monday) is picked up within seconds
		this.tasks.add(this.proxy.getScheduler().buildTask(this, this.missions::rotate)
				.delay(15, TimeUnit.SECONDS).repeat(15, TimeUnit.SECONDS).schedule());
		this.tasks.add(this.proxy.getScheduler().buildTask(this, this.missions::flush)
				.delay(missionSettings.flushSeconds(), TimeUnit.SECONDS).repeat(missionSettings.flushSeconds(), TimeUnit.SECONDS).schedule());

		final CommandManager commands = this.proxy.getCommandManager();

		commands.register(commands.metaBuilder(ProxyCommand.LABEL).aliases(ProxyCommand.ALIAS).plugin(this).build(), new ProxyCommand(this));

		this.enabled = true;
		this.logger.info("Loaded {} achievement(s), season pass: {}.", definitions.size(), describe(season));
	}

	@Subscribe
	public void onProxyShutdown(final ProxyShutdownEvent event) {
		if (!this.enabled)
			return;

		this.enabled = false;
		this.tasks.forEach(ScheduledTask::cancel);
		this.messenger.stop();

		// Queued behind everything still pending, the database thread finishes it before the connection closes
		this.missions.flush();
		this.stats.markAllOffline();

		if (!this.database.shutdown())
			this.logger.warn("Database queries did not finish within 10 seconds, some changes may be lost!");
	}

	/**
	 * Re-reads achievements.yml, seasonpass.yml and the minigame settings of config.yml, and publishes the first two
	 * for the website again. Database settings and the intervals need a proxy restart.
	 *
	 * @return a one-line summary for whoever asked
	 */
	public String reload() throws ConfigException {
		final ProxyConfig config = ProxyConfig.load(this.dataDirectory);
		final SeasonPassSettings season = this.loadSeasonPass();
		final Map<String, AchievementDefinition> definitions = this.loadAchievements();
		final MissionSettings missionSettings = MissionSettings.load(this.dataDirectory);

		// Applied only after all four files parsed, so a typo leaves the running configuration untouched
		this.missions.reload(missionSettings);
		this.missions.rotate();
		this.seasonPass.reload(season);
		this.achievements.reload(definitions);
		this.minigames.setDefaultTtlSeconds(config.pendingJoinTtlSeconds());
		this.publishDefinitions(season, definitions);

		return definitions.size() + " achievement(s), season pass: " + describe(season) + ", missions: "
				+ (missionSettings.enabled() ? missionSettings.pools().values().stream().mapToInt(List::size).sum() + " in the pools" : "disabled");
	}

	/**
	 * Copies the season pass and the achievements into the database for the website, see {@link DefinitionPublisher}.
	 */
	private void publishDefinitions(final SeasonPassSettings season, final Map<String, AchievementDefinition> definitions) {
		final boolean sqlite = this.database.isSqlite();

		this.warnUnknownServers(season, definitions);

		this.database.submit(connection -> {
			DefinitionPublisher.publish(connection, sqlite, season, definitions);

			return null;

		}).whenComplete((ignored, error) -> {
			if (error != null)
				this.logger.warn("Could not publish the season pass and achievements for the website: {}", error.toString());
		});
	}

	/**
	 * A typo in require-server would keep those rewards queued forever, so say so on start and reload.
	 */
	private void warnUnknownServers(final SeasonPassSettings season, final Map<String, AchievementDefinition> definitions) {
		final java.util.Set<String> required = new java.util.TreeSet<>();

		season.tiers().values().forEach(tier -> {
			if (tier.free().requireServer() != null)
				required.add(tier.free().requireServer());

			if (tier.premium().requireServer() != null)
				required.add(tier.premium().requireServer());
		});

		definitions.values().forEach(definition -> {
			if (definition.reward().requireServer() != null)
				required.add(definition.reward().requireServer());
		});

		for (final String server : required)
			if (this.proxy.getServer(server).isEmpty())
				this.logger.warn("require-server \"{}\" is not a server in velocity.toml: rewards for it will wait in the queue until it exists.", server);
	}

	private SeasonPassSettings loadSeasonPass() throws ConfigException {
		return SeasonPassSettings.parse(YamlFile.load(this.dataDirectory, SeasonPassSettings.FILE));
	}

	private Map<String, AchievementDefinition> loadAchievements() throws ConfigException {
		return AchievementDefinition.parseAll(YamlFile.load(this.dataDirectory, AchievementDefinition.FILE));
	}

	private static String describe(final SeasonPassSettings season) {
		if (!season.enabled())
			return "disabled";

		return "\"" + season.id() + "\" with " + season.tiers().size() + " tier(s)" + (season.isActive(System.currentTimeMillis() / 1000) ? "" : ", not active today");
	}

	/* ------------------------------------------------------------------------------- */
	/* Players */
	/* ------------------------------------------------------------------------------- */

	@Subscribe
	public void onPostLogin(final PostLoginEvent event) {
		if (!this.enabled)
			return;

		final UUID uuid = event.getPlayer().getUniqueId();
		final String name = event.getPlayer().getUsername();

		this.messenger.playerJoined(uuid);
		this.missions.onLogin(uuid).whenComplete((ignored, error) -> {
			if (error != null)
				this.logger.warn("Could not count the login of {}: {}", name, error.toString());
		});

		// Creates the KingdomCoins account, also for players whose first server does not run the Bukkit plugin
		this.coins.load(uuid, name).whenComplete((balance, error) -> {
			if (error != null)
				this.logger.warn("Could not load KingdomCoins of {}: {}", name, error.toString());
		});
	}

	@Subscribe
	public void onServerConnected(final ServerConnectedEvent event) {
		if (!this.enabled)
			return;

		final UUID uuid = event.getPlayer().getUniqueId();
		final String server = event.getServer().getServerInfo().getName();
		final String previous = this.messenger.playerConnected(uuid, server);

		if (previous != null && !previous.equals(server))
			this.stats.playerLeft(this.messenger.getServerId(previous), uuid);
	}

	@Subscribe
	public void onDisconnect(final DisconnectEvent event) {
		if (!this.enabled)
			return;

		final UUID uuid = event.getPlayer().getUniqueId();
		final String last = this.messenger.playerQuit(uuid);

		this.coins.unload(uuid);
		this.missions.flush(); // what the player did is counted now, not at the next interval

		if (last != null)
			this.stats.playerLeft(this.messenger.getServerId(last), uuid);
	}

	/* ------------------------------------------------------------------------------- */
	/* For the admin command and other Velocity plugins */
	/* ------------------------------------------------------------------------------- */

	public boolean isEnabled() {
		return this.enabled;
	}

	public String getVersion() {
		return this.proxy.getPluginManager().fromInstance(this).map(PluginContainer::getDescription).flatMap(description -> description.getVersion()).orElse("unknown");
	}

	public Path getDataDirectory() {
		return this.dataDirectory;
	}

	public Logger getLogger() {
		return this.logger;
	}

	public Database getDatabase() {
		return this.database;
	}

	public BackendMessenger getMessenger() {
		return this.messenger;
	}

	public KingdomCoins getKingdomCoins() {
		return this.coins;
	}

	public SeasonPass getSeasonPass() {
		return this.seasonPass;
	}

	public Achievements getAchievements() {
		return this.achievements;
	}

	public MinigameApi getMinigameApi() {
		return this.minigames;
	}

	public Missions getMissions() {
		return this.missions;
	}

	public MissionAdmin getMissionAdmin() {
		return this.missionAdmin;
	}

	public RewardDispatcher getRewardDispatcher() {
		return this.rewards;
	}
}
