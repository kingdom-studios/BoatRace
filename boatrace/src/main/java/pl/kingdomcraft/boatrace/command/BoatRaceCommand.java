package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

/** /boatrace (alias /bre - not /br, which collides with WorldEdit's brush command) - the main command group. */
public final class BoatRaceCommand extends SimpleCommandGroup {

	public BoatRaceCommand() {
		super("boatrace|bre");
	}

	@Override
	protected void registerSubcommands() {
		// player
		this.registerSubcommand(new JoinSub(this));
		this.registerSubcommand(new LeaveSub(this));
		this.registerSubcommand(new ResetSub(this));
		this.registerSubcommand(new ListSub(this));

		// admin: selection
		this.registerSubcommand(new WandSub(this));
		this.registerSubcommand(new PosSub(this, 0));
		this.registerSubcommand(new PosSub(this, 1));

		// admin: arena setup
		this.registerSubcommand(new CreateSub(this));
		this.registerSubcommand(new InfoSub(this));
		this.registerSubcommand(new SetSpawnSub(this));
		this.registerSubcommand(new SetStartSub(this));
		this.registerSubcommand(new SetFinishSub(this));
		this.registerSubcommand(new AddCheckpointSub(this));
		this.registerSubcommand(new DelCheckpointSub(this));
		this.registerSubcommand(new LightsSub(this));
		this.registerSubcommand(new RewardSub(this));

		// admin: management
		this.registerSubcommand(new AdminSub(this));
		this.registerSubcommand(new ReloadSub(this));
	}

	@Override
	protected String getCredits() {
		return "&7BoatRace by &fAlek_Sialek";
	}
}
