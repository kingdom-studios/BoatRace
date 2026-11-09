package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

/** /boatrace (alias /br) - the main command group. */
public final class BoatRaceCommand extends SimpleCommandGroup {

	public BoatRaceCommand() {
		super("boatrace|br");
	}

	@Override
	protected void registerSubcommands() {
		// player
		this.registerSubcommand(new JoinSub(this));
		this.registerSubcommand(new LeaveSub(this));
		this.registerSubcommand(new ResetSub(this));
		this.registerSubcommand(new ListSub(this));

		// admin setup
		this.registerSubcommand(new CreateSub(this));
		this.registerSubcommand(new SetSpawnSub(this));
		this.registerSubcommand(new SetStartBlockSub(this));
		this.registerSubcommand(new AddCheckpointSub(this));
		this.registerSubcommand(new SetFinishSub(this));
		this.registerSubcommand(new AdminSub(this));
		this.registerSubcommand(new ReloadSub(this));
	}

	@Override
	protected String getCredits() {
		return "";
	}
}
