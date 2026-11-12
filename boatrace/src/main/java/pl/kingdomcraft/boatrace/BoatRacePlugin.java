package pl.kingdomcraft.boatrace;

import java.util.logging.Level;

import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.captainhook.CaptainHookHook;
import pl.kingdomcraft.boatrace.command.BoatRaceCommand;
import pl.kingdomcraft.boatrace.command.PartyCommand;
import pl.kingdomcraft.boatrace.gui.GuiManager;
import pl.kingdomcraft.boatrace.luckperms.LuckPermsHook;
import pl.kingdomcraft.boatrace.party.PartyManager;
import pl.kingdomcraft.boatrace.race.RaceListener;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.selection.SelectionManager;
import pl.kingdomcraft.boatrace.spectate.SpectateManager;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.tablist.TabListManager;
import pl.kingdomcraft.boatrace.util.ConfigUpdater;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * BoatRace - F1-style boat racing.
 *
 * Built on MineAcademy Foundation (shaded + relocated into pl.kingdomcraft.boatrace.lib at package time).
 */
public final class BoatRacePlugin extends SimplePlugin {

	private Settings settings;
	private ArenaManager arenas;
	private RecordStorage storage;
	private RaceManager races;
	private SelectionManager selections;
	private PartyManager parties;
	private GuiManager gui;
	private SpectateManager spectate;
	private CaptainHookHook captainHook;
	private LuckPermsHook luckPerms;
	private TabListManager tabList;

	/** Foundation convention: typed access to the singleton instance. */
	public static BoatRacePlugin getInstance() {
		return (BoatRacePlugin) SimplePlugin.getInstance();
	}

	@Override
	protected void onPluginStart() {
		this.saveDefaultConfig();

		// after an update: add the new settings / texts to the existing files, the owner's values stay
		ConfigUpdater.updateAll(this);

		this.reloadConfig();

		this.settings = new Settings();
		this.settings.load(this.getConfig());

		Msg.load(this);

		this.arenas = new ArenaManager(this);
		this.arenas.load();

		this.storage = new RecordStorage(this);

		try {
			this.storage.open(this.getConfig(), this.settings.serverName, this.settings.serverGroup, this.settings.recordsRefreshSeconds);
			this.getLogger().info("Connected to MariaDB as server '" + this.settings.serverName + "'.");

		} catch (final Exception ex) {
			this.getLogger().log(Level.SEVERE, "Could not connect to the MariaDB database (check Database in config.yml) - BoatRace is disabling itself.", ex);
			this.storage.close();
			this.storage = null;
			this.getServer().getPluginManager().disablePlugin(this);

			return;
		}

		this.applyRankingSettings();

		this.parties = new PartyManager(this.settings);
		this.registerEvents(this.parties);

		this.races = new RaceManager(this, this.settings, this.arenas, this.storage, this.parties);
		this.races.start();

		// CaptainHook (softdepend, loads before BoatRace): race stats for achievements / missions and season pass XP
		this.captainHook = new CaptainHookHook(this, this.settings);
		this.captainHook.init();
		this.races.setCaptainHook(this.captainHook);

		// LuckPerms (softdepend): the team tag of team races as a transient meta
		this.luckPerms = new LuckPermsHook(this);
		this.luckPerms.init(this.settings.teamLuckPerms);
		this.races.setLuckPerms(this.luckPerms);

		this.registerEvents(new RaceListener(this.races));

		this.spectate = new SpectateManager(this, this.races);
		this.races.setSpectate(this.spectate);
		this.registerEvents(this.spectate);

		// tab list: lobby players see the lobby, racers their own session (tablist.session-only)
		this.tabList = new TabListManager(this, this.settings, this.races, this.spectate);
		this.registerEvents(this.tabList);
		this.tabList.start();

		this.gui = new GuiManager(this, this.arenas, this.races);
		this.gui.load();
		this.registerEvents(this.gui);

		this.selections = new SelectionManager(this);
		this.registerEvents(this.selections);

		// /boatrace (alias /bre - /br is taken by WorldEdit)
		this.registerCommands(new BoatRaceCommand());

		// /party - the built-in party system
		this.registerCommands(new PartyCommand());

		// The PlaceholderAPI expansion (BoatRaceExpansion) is registered automatically by Foundation.
	}

	@Override
	protected void onPluginStop() {
		// everybody sees everybody again (unlisting is not saved anyway, but players stay online on a plugin reload)
		if (this.tabList != null)
			this.tabList.shutdown();

		if (this.spectate != null)
			this.spectate.shutdown();

		if (this.parties != null)
			this.parties.clear();

		// racers still on a track are reported while they are disposed, so CaptainHook goes last
		if (this.races != null)
			this.races.shutdown();

		if (this.captainHook != null)
			this.captainHook.shutdown();

		if (this.storage != null)
			this.storage.close();
	}

	/** /bre reload - config.yml + messages.yml + arenas.yml. Racers are sent to the hub first so nobody keeps a stale arena. */
	public void reloadAll() {
		if (this.races != null)
			this.races.leaveAllToHub();

		ConfigUpdater.updateAll(this);

		this.reloadConfig();
		this.settings.load(this.getConfig());
		Msg.load(this);
		this.arenas.load();
		this.applyRankingSettings();

		if (this.captainHook != null)
			this.captainHook.init();

		if (this.luckPerms != null)
			this.luckPerms.init(this.settings.teamLuckPerms);

		if (this.gui != null)
			this.gui.load();
	}

	private void applyRankingSettings() {
		if (this.storage != null)
			this.storage.configureRanking(this.settings.rankingStart, this.settings.rankingMin, this.settings.rankingLeaderboardMinGames);
	}

	public Settings getSettings() {
		return this.settings;
	}

	public ArenaManager getArenas() {
		return this.arenas;
	}

	public RecordStorage getStorage() {
		return this.storage;
	}

	public RaceManager getRaces() {
		return this.races;
	}

	public PartyManager getParties() {
		return this.parties;
	}

	public GuiManager getGui() {
		return this.gui;
	}

	public SpectateManager getSpectate() {
		return this.spectate;
	}

	public SelectionManager getSelections() {
		return this.selections;
	}
}
