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
			this.fail("arena.invalid-name");

		if ("random".equalsIgnoreCase(name))
			this.fail("command.reserved-name");

		if (this.plugin().getArenas().exists(name))
			this.fail("arena.exists", "arena", name);

		this.plugin().getArenas().create(name);

		this.info("arena.created", "arena", name);
	}
}
