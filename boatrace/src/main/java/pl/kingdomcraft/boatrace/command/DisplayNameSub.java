package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/**
 * /bre displayname <arena> <readable name...>   how the map is called on the website (instead of its technical name)
 * /bre displayname <arena> clear                back to the default (the arena name with _ and - as spaces)
 */
final class DisplayNameSub extends RaceSubCommand {

	DisplayNameSub(final SimpleCommandGroup group) {
		super(group, "displayname|setname", "Set how the map is called on the website, e.g. 'Monaco Grand Prix' ('clear' = default).", "<arena> <name|clear>", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);
		final String name = String.join(" ", Arrays.copyOfRange(this.args, 1, this.args.length)).trim();

		if (name.equalsIgnoreCase("clear") || name.equalsIgnoreCase("reset")) {
			arena.setDisplayName(null);
			this.plugin().getArenas().save();

			this.info("arena.display-name-cleared", "arena", arena.getName(), "name", arena.getDisplayName());
			return;
		}

		// the website shows plain text, in a column of limited width
		if (name.isEmpty() || name.length() > Arena.DISPLAY_NAME_MAX || name.indexOf('&') >= 0 || name.indexOf('§') >= 0 || name.chars().anyMatch(Character::isISOControl)) {
			this.fail("arena.display-name-invalid", "max", Arena.DISPLAY_NAME_MAX);
			return;
		}

		arena.setDisplayName(name);
		this.plugin().getArenas().save();

		this.info("arena.display-name-set", "arena", arena.getName(), "name", arena.getDisplayName());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		return this.args.length == 2 ? this.completeLastWord("clear") : new ArrayList<>();
	}
}
