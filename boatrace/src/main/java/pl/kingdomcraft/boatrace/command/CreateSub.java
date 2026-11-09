package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class CreateSub extends RaceSubCommand {

	CreateSub(final SimpleCommandGroup group) {
		super(group, "create", "Create a new arena.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final String name = this.args[0];

		if (!name.matches("[A-Za-z0-9_\\-]{1,32}"))
			this.returnTell("&cArena name: 1-32 characters (letters, digits, _ and -).");

		if (this.plugin().getArenas().exists(name))
			this.returnTell("&cArena &f" + name + " &calready exists.");

		this.plugin().getArenas().create(name);

		this.info("&aCreated arena &f" + name + "&a. Next steps:");
		this.info(" &f1. /bre setspawn " + name + " &7- stand a few blocks BEFORE the start line, facing the track");
		this.info(" &f2. /bre wand &7+ select the start line, then &f/bre setstart " + name);
		this.info(" &f3. &7select each checkpoint zone in order, then &f/bre addcheckpoint " + name);
		this.info(" &f4. &7(optional) &f/bre setfinish " + name + " &7- otherwise the start line is the finish");
		this.info(" &f5. &7(optional) &f/bre lights " + name + " add|set &7- F1 start lamps");
		this.info(" &f6. &7(optional) &f/bre reward " + name + " once|always <command>");
	}
}
