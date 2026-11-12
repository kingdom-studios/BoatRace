package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.gui.GuiManager;

/**
 * /bre gui                    the default menu for yourself
 * /bre gui <menu>             a menu (unranked, ranked, your own ...) for yourself
 * /bre gui <menu> <player>    a menu for another player (boatrace.admin - NPCs, hub signs, command blocks, the console)
 * /bre gui <player>           the default menu for another player (as before menus existed)
 *
 * Silent: the menu just opens, nothing is sent to the chat - neither to the player nor to whoever ran the command.
 * Only a refusal (unknown menu / player, no permission, competitive matches off) is reported.
 */
final class GuiSub extends RaceSubCommand {

	GuiSub(final SimpleCommandGroup group) {
		super(group, "gui|menu", "Open a menu (admins: for another player).", "[menu] [player]", 0, false);
	}

	@Override
	protected void onCommand() {
		final GuiManager gui = this.plugin().getGui();

		if (this.args.length == 0) {
			gui.open(this.requirePlayer(), gui.defaultMenu(), this.getSender());
			return;
		}

		final boolean firstIsMenu = gui.hasMenu(this.args[0]);

		// "/bre gui <player>" (a name that is not a menu) = the default menu for that player
		final String menu = firstIsMenu ? this.args[0] : gui.defaultMenu();
		final String playerName = this.args.length > 1 ? this.args[1] : firstIsMenu ? null : this.args[0];

		if (playerName == null) {
			gui.open(this.requirePlayer(), menu, this.getSender());
			return;
		}

		if (!this.getSender().hasPermission(ADMIN_PERMISSION)) {
			this.fail("command.no-permission");
			return;
		}

		final Player target = Bukkit.getPlayerExact(playerName);

		if (target == null) {
			// a single unknown word may have been meant as a menu
			if (this.args.length == 1)
				this.fail("gui.unknown-menu", "menu", this.args[0], "menus", String.join(", ", gui.menuIds()));
			else
				this.fail("command.player-not-found", "player", playerName);

			return;
		}

		gui.open(target, menu, this.getSender());
	}

	@Override
	protected List<String> tabComplete() {
		final List<String> options = new ArrayList<>();
		final boolean admin = this.getSender().hasPermission(ADMIN_PERMISSION);

		if (this.args.length == 1) {
			options.addAll(this.plugin().getGui().menuIds());

			if (admin)
				for (final Player player : Bukkit.getOnlinePlayers())
					options.add(player.getName());

			return this.completeLastWord(options);
		}

		return this.args.length == 2 && admin ? this.completeLastWordPlayerNames() : options;
	}
}
