package pl.kingdomcraft.captainhook.command.coins;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.captainhook.Permissions;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.settings.Localization.Coins;

/**
 * /kc balance [player]
 */
final class BalanceSubCommand extends CoinsSubCommand {

	BalanceSubCommand(final SimpleCommandGroup parent) {
		super(parent, "balance|bal|saldo", "[gracz]", Coins.DESCRIPTION_BALANCE, null);
	}

	@Override
	protected void onCommand() {
		if (args.length == 0) {
			checkConsole();
			tell(getOwnBalanceMessage(getPlayer()));

			return;
		}

		checkPerm(Permissions.COINS_BALANCE_OTHERS);

		final CommandSender sender = this.sender;

		findAccount(args[0], account -> Common.tell(sender, replace(Coins.BALANCE_OTHER, "player", account.getName(), "balance", account.getBalance())));
	}

	/**
	 * Served from the cache, no database query.
	 */
	static String getOwnBalanceMessage(final Player player) {
		final long balance = KingdomCoins.getCachedBalance(player.getUniqueId());

		return balance < 0 ? Coins.LOADING : replace(Coins.BALANCE, "balance", balance);
	}
}
