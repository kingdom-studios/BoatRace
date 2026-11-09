package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

final class CreateSub extends RaceSubCommand {

	CreateSub(final SimpleCommandGroup group) {
		super(group, "create", "Utwórz nową arenę.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final String name = this.args[0];

		if (!name.matches("[A-Za-z0-9_\\-]{1,32}"))
			this.returnTell("&cNazwa areny: 1-32 znaków (litery, cyfry, _ i -).");

		if (this.plugin().getArenas().exists(name))
			this.returnTell("&cArena &f" + name + " &cjuż istnieje.");

		this.plugin().getArenas().create(name);

		this.info("&aUtworzono arenę &f" + name + "&a. Teraz ustaw: &f/br setspawn " + name + "&a, &f/br setstartblock " + name
				+ "&a, &f/br addcheckpoint " + name + "&a, &f/br setfinish " + name);
	}
}
