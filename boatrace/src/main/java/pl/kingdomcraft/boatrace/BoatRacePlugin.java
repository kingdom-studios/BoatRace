package pl.kingdomcraft.boatrace;

import java.util.logging.Level;

import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.command.BoatRaceCommand;
import pl.kingdomcraft.boatrace.command.PartyCommand;
import pl.kingdomcraft.boatrace.gui.GuiManager;
import pl.kingdomcraft.boatrace.party.PartyManager;
import pl.kingdomcraft.boatrace.race.RaceListener;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.selection.SelectionManager;
import pl.kingdomcraft.boatrace.spectate.SpectateManager;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
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

	/** Foundation convention: typed access to the singleton instance. */
	public static BoatRacePlugin getInstance() {
		return (BoatRacePlugin) SimplePlugin.getInstance();
	}

	@Override
	protected void onPluginStart() {
		this.saveDefaultConfig();
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

		this.parties = new PartyManager(this.settings);
		this.registerEvents(this.parties);

		this.races = new RaceManager(this, this.settings, this.arenas, this.storage, this.parties);
		this.races.start();

		this.registerEvents(new RaceListener(this.races));

		this.spectate = new SpectateManager(this, this.races);
		this.races.setSpectate(this.spectate);
		this.registerEvents(this.spectate);

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
		if (this.spectate != null)
			this.spectate.shutdown();

		if (this.parties != null)
			this.parties.clear();

		if (this.races != null)
			this.races.shutdown();

		if (this.storage != null)
			this.storage.close();
	}

	/** /bre reload - config.yml + messages.yml + arenas.yml. Racers are sent to the hub first so nobody keeps a stale arena. */
	public void reloadAll() {
		if (this.races != null)
			this.races.leaveAllToHub();

		this.reloadConfig();
		this.settings.load(this.getConfig());
		Msg.load(this);
		this.arenas.load();

		if (this.gui != null)
			this.gui.load();
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
