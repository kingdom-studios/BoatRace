package pl.kingdomcraft.boatrace;

import java.util.logging.Level;

import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.boatrace.arena.ArenaManager;
import pl.kingdomcraft.boatrace.command.BoatRaceCommand;
import pl.kingdomcraft.boatrace.race.RaceListener;
import pl.kingdomcraft.boatrace.race.RaceManager;
import pl.kingdomcraft.boatrace.storage.RecordStorage;

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

		this.arenas = new ArenaManager(this);
		this.arenas.load();

		this.storage = new RecordStorage(this);

		try {
			this.storage.open();

		} catch (final Exception ex) {
			this.getLogger().log(Level.SEVERE, "Could not open the SQLite database - BoatRace is disabling itself.", ex);
			this.getServer().getPluginManager().disablePlugin(this);

			return;
		}

		this.races = new RaceManager(this, this.settings, this.arenas, this.storage);
		this.races.start();

		this.registerEvents(new RaceListener(this.races));

		// /boatrace (alias /br)
		this.registerCommands(new BoatRaceCommand());

		// The PlaceholderAPI expansion (BoatRaceExpansion) is registered automatically by Foundation.
	}

	@Override
	protected void onPluginStop() {
		if (this.races != null)
			this.races.shutdown();

		if (this.storage != null)
			this.storage.close();
	}

	/** /br reload - config.yml + arenas.yml. Racers are sent to the hub first so nobody keeps a stale arena. */
	public void reloadAll() {
		if (this.races != null)
			this.races.leaveAllToHub();

		this.reloadConfig();
		this.settings.load(this.getConfig());
		this.arenas.load();
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
}
