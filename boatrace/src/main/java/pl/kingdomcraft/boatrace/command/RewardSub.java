package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Arena.RewardMode;

/**
 * /bre reward <arena> <once|always|none> [command...]   sets the mode and optionally adds a reward command
 * /bre reward <arena> clear                            removes all reward commands
 * /bre reward <arena> info                             shows the current setup
 *
 * Commands run from the console. Placeholders: {player}, {uuid}, {arena}, {time}.
 */
final class RewardSub extends RaceSubCommand {

	RewardSub(final SimpleCommandGroup group) {
		super(group, "reward", "Configure the finish reward of an arena.", "<arena> <once|always|none|clear|info> [command]", 2, true);
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] {
				"/{label} reward <arena> once [command]   &7- only the first finish of each player",
				"/{label} reward <arena> always [command] &7- every finish",
				"/{label} reward <arena> none             &7- no reward",
				"/{label} reward <arena> clear            &7- remove all reward commands",
				"/{label} reward <arena> info             &7- show the current setup",
				"&7Commands run from the console. Placeholders: &f{player} {uuid} {arena} {time}",
				"&7Example: &f/{label} reward track1 once eco give {player} 500"
		};
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final String action = this.args[1].toLowerCase();

		if ("info".equals(action)) {
			this.showInfo(arena);
			return;
		}

		if ("clear".equals(action)) {
			arena.getRewardCommands().clear();
			this.plugin().getArenas().save();

			this.info("reward.cleared", "arena", arena.getName());
			return;
		}

		final RewardMode mode = RewardMode.parse(action);

		if (mode == null)
			this.fail("reward.unknown-mode", "value", this.args[1]);

		arena.setRewardMode(mode);

		if (this.args.length > 2) {
			String command = String.join(" ", Arrays.copyOfRange(this.args, 2, this.args.length)).trim();

			if (command.startsWith("/"))
				command = command.substring(1);

			arena.getRewardCommands().add(command);
		}

		this.plugin().getArenas().save();

		this.info("reward.mode-set", "arena", arena.getName(), "mode", mode.name());

		if (mode != RewardMode.NONE && arena.getRewardCommands().isEmpty())
			this.info("reward.no-command", "arena", arena.getName(), "mode", action);

		this.showInfo(arena);
	}

	private void showInfo(final Arena arena) {
		this.info("reward.info-header", "arena", arena.getName(), "mode", arena.getRewardMode().name());

		if (arena.getRewardCommands().isEmpty())
			this.info("reward.info-none");

		int index = 1;

		for (final String command : arena.getRewardCommands())
			this.info("reward.info-line", "number", index++, "command", command);
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2)
			return this.completeLastWord("once", "always", "none", "clear", "info");

		return new ArrayList<>();
	}
}
