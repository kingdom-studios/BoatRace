package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

/** /party - the built-in party system of BoatRace. */
public final class PartyCommand extends SimpleCommandGroup {

	public PartyCommand() {
		super("party");
	}

	@Override
	protected void registerSubcommands() {
		for (final PartySub.Action action : PartySub.Action.values())
			this.registerSubcommand(new PartySub(this, action));
	}

	@Override
	protected String getCredits() {
		return "";
	}
}
