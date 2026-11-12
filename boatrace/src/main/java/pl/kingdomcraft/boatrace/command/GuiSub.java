package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

/**
 * /bre gui           opens the map menu for yourself
 * /bre gui <player>  opens it for another player (boatrace.admin - NPCs, hub signs, the console)
 *
 * Silent: the menu just opens, nothing is sent to the chat (neither to the player nor to whoever ran the command).
 */
final class GuiSub extends RaceSubCommand {

	GuiSub(final SimpleCommandGroup group) {
		super(group, "gui|menu", "Open the map menu (admins: for another player).", "[player]", 0, false);
	}

	@Override
	protected void onCommand() {
		if (this.args.length == 0) {
			this.plugin().getGui().open(this.requirePlayer());
			return;
		}

		if (!this.getSender().hasPermission(ADMIN_PERMISSION)) {
			this.fail("command.no-permission");
			return;
		}

		final Player target = Bukkit.getPlayerExact(this.args[0]);

		if (target == null) {
			this.fail("command.player-not-found", "player", this.args[0]);
			return;
		}

		this.plugin().getGui().open(target);
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 && this.getSender().hasPermission(ADMIN_PERMISSION) ? this.completeLastWordPlayerNames() : new ArrayList<>();
	}
}
