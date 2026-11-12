package pl.kingdomcraft.captainhook.command.coins;

import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.captainhook.coins.CoinAccount;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.settings.Localization.Coins;

/**
 * /kc top - served from the cache refreshed every kingdomcoins.refresh-interval-seconds.
 */
final class TopSubCommand extends CoinsSubCommand {

	TopSubCommand(final SimpleCommandGroup parent) {
		super(parent, "top|ranking", "", Coins.DESCRIPTION_TOP, null);
	}

	@Override
	protected void onCommand() {
		final List<CoinAccount> top = KingdomCoins.getTop();

		checkBoolean(!top.isEmpty(), Coins.TOP_EMPTY);
		tellNoPrefix(Coins.TOP_HEADER);

		for (int i = 0; i < top.size(); i++)
			tellNoPrefix(replace(Coins.TOP_LINE, "position", String.valueOf(i + 1), "player", top.get(i).getName(), "balance", top.get(i).getBalance()));
	}

	@Override
	protected List<String> tabComplete() {
		return NO_COMPLETE;
	}
}
